package com.igrium.polymerfilebin.filebin;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Minimal client for the Filebin API (see filebin-api.yaml).
 */
public class FilebinClient {
    /**
     * Filebin skips its browser verification page for user agents containing "curl", "Wget" or "VLC".
     * The vanilla client's user agent isn't on that list, so the server resolves download redirects itself.
     */
    public static final String USER_AGENT = "polymer-filebin (curl-compatible)";

    private static final Gson GSON = new Gson();

    private final URI baseUrl;
    private final HttpClient http;

    public FilebinClient(String baseUrl) {
        this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public URI fileUri(String bin, String filename) {
        return baseUrl.resolve(encode(bin) + "/" + encode(filename));
    }

    public URI binUri(String bin) {
        return baseUrl.resolve(encode(bin));
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5));
    }

    /**
     * Upload a file to a bin. The bin is created if it doesn't exist.
     */
    public UploadResponse upload(String bin, String filename, Path file, String sha256) throws IOException, InterruptedException {
        var req = request(fileUri(bin, filename))
                .header("Content-Type", "application/octet-stream")
                .header("Content-SHA256", sha256)
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 201) {
            throw new FilebinException("Upload failed", res.statusCode(), res.body());
        }
        return GSON.fromJson(res.body(), UploadResponse.class);
    }

    /**
     * Fetch bin metadata. Returns empty if the bin doesn't exist (or has expired).
     */
    public Optional<BinResponse> getBin(String bin) throws IOException, InterruptedException {
        var req = request(binUri(bin))
                .header("Accept", "application/json")
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw new FilebinException("Failed to query bin", res.statusCode(), res.body());
        }
        return Optional.of(GSON.fromJson(res.body(), BinResponse.class));
    }

    public void deleteFile(String bin, String filename) throws IOException, InterruptedException {
        var req = request(fileUri(bin, filename)).DELETE().build();
        var res = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() != 200 && res.statusCode() != 404) {
            throw new FilebinException("Failed to delete file", res.statusCode(), null);
        }
    }

    /**
     * Resolve the (time-limited) direct download URL of a file through the official download endpoint.
     * The resulting URL can be downloaded by any client, regardless of user agent.
     */
    public URI resolveDownloadUrl(String bin, String filename) throws IOException, InterruptedException {
        var req = request(fileUri(bin, filename)).GET().build();
        var res = http.send(req, HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() / 100 == 3) {
            var location = res.headers().firstValue("Location")
                    .orElseThrow(() -> new FilebinException("Redirect without location", res.statusCode(), null));
            return req.uri().resolve(location);
        }
        throw new FilebinException("Unexpected download response (expected redirect)", res.statusCode(), null);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static class FilebinException extends IOException {
        public final int status;

        public FilebinException(String message, int status, String body) {
            super(message + " (HTTP " + status + ")" + (body != null && !body.isBlank() ? ": " + body.strip() : ""));
            this.status = status;
        }
    }

    public static class BinInfo {
        public String id;
        public boolean readonly;
        public long bytes;
        public int files;
        @SerializedName("expired_at")
        public String expiredAt;

        public Optional<Instant> expiry() {
            try {
                return Optional.of(Instant.parse(expiredAt));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    public static class FileInfo {
        public String filename;
        public long bytes;
        public String sha1;
        public String sha256;
    }

    public static class UploadResponse {
        public BinInfo bin;
        public FileInfo file;
    }

    public static class BinResponse {
        public BinInfo bin;
        public List<FileInfo> files;
    }
}
