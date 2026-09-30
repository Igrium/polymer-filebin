package com.igrium.polymerfilebin;

import com.igrium.polymerfilebin.filebin.FilebinClient;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Uploads the generated pack to Filebin and keeps a valid download link for it around.
 * All network work happens on a dedicated background thread.
 */
public class PackHost {
    public enum State {
        /** Waiting for Polymer to finish generating the pack. */
        WAITING_FOR_PACK,
        UPLOADING,
        READY,
        FAILED
    }

    public record UploadedPack(String filename, String sha1, @Nullable Instant expiry) {}

    public record DownloadLink(String url, Instant expiresAt) {
        public boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private static final String FILE_PREFIX = "pack-";
    /** Re-upload when the bin expires within this window. */
    private static final Duration EXPIRY_MARGIN = Duration.ofHours(24);

    private final FilebinConfig config;
    private final FilebinClient client;
    private final ScheduledExecutorService executor;

    private volatile State state = State.WAITING_FOR_PACK;
    private volatile @Nullable String error;
    private volatile @Nullable Path packPath;
    private volatile @Nullable UploadedPack uploaded;
    private volatile @Nullable DownloadLink link;
    private @Nullable CompletableFuture<DownloadLink> pendingLink;

    public PackHost(FilebinConfig config) {
        this.config = config;
        this.client = new FilebinClient(config.filebinUrl);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "Polymer Filebin");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        long interval = Math.max(1, config.recheckIntervalMinutes);
        executor.scheduleWithFixedDelay(this::recheck, interval, interval, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(this::retryIfFailed, 1, 1, TimeUnit.MINUTES);
        // Keep the download link fresh so joining players never wait on a request.
        executor.scheduleWithFixedDelay(this::refreshLinkIfNeeded, 30, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    public State getState() {
        return state;
    }

    public @Nullable String getError() {
        return error;
    }

    public @Nullable UploadedPack getUploaded() {
        return uploaded;
    }

    public @Nullable String getBinUrl() {
        return client.binUri(config.bin).toString();
    }

    /**
     * Whether the pack can be sent to players right now.
     */
    public boolean isReady() {
        var link = this.link;
        return state == State.READY && link != null && link.isValid();
    }

    /**
     * The current download link, or null if not ready.
     */
    public @Nullable String getDownloadUrl() {
        var link = this.link;
        return state == State.READY && link != null && link.isValid() ? link.url() : null;
    }

    /**
     * Called when Polymer starts regenerating the pack.
     */
    public void onGenerationStarted() {
        state = State.WAITING_FOR_PACK;
        error = null;
    }

    /**
     * Called when Polymer finishes generating the pack.
     */
    public void onPackGenerated(OutputGenerator.Result result) {
        packPath = result.path();
        executor.execute(() -> upload(result.path(), result.hash(), false));
    }

    /**
     * Force the current pack to be uploaded again.
     */
    public CompletableFuture<Void> reupload() {
        return CompletableFuture.runAsync(() -> {
            var path = packPath;
            if (path == null) {
                throw new IllegalStateException("No resource pack has been generated yet");
            }
            upload(path, null, true);
        }, executor);
    }

    /**
     * Resolve a new download link (e.g. after a client failed to download with the old one).
     * Concurrent callers share the same request.
     */
    public synchronized CompletableFuture<DownloadLink> requestNewLink() {
        if (pendingLink != null && !pendingLink.isDone()) {
            return pendingLink;
        }
        pendingLink = CompletableFuture.supplyAsync(() -> {
            var pack = uploaded;
            if (pack == null) {
                throw new IllegalStateException("Pack isn't uploaded");
            }
            return resolveLink(pack, true);
        }, executor);
        return pendingLink;
    }

    // ---- Background thread ----

    private void upload(Path path, @Nullable String sha1, boolean force) {
        state = State.UPLOADING;
        error = null;
        link = null;
        try {
            if (sha1 == null) {
                sha1 = hash(path, "SHA-1");
            }
            sha1 = sha1.toLowerCase(Locale.ROOT);
            var filename = FILE_PREFIX + sha1 + ".zip";

            UploadedPack pack = null;
            if (!force) {
                pack = findExisting(filename, sha1);
                if (pack != null) {
                    PolymerFilebin.LOGGER.info("Resource pack {} is already on Filebin, skipping upload", filename);
                }
            }

            if (pack == null) {
                PolymerFilebin.LOGGER.info("Uploading resource pack to {}", client.fileUri(config.bin, filename));
                var res = client.upload(config.bin, filename, path, hash(path, "SHA-256"));
                if (res.file != null && res.file.sha1 != null && !res.file.sha1.equalsIgnoreCase(sha1)) {
                    throw new IOException("Filebin reported SHA-1 " + res.file.sha1 + ", expected " + sha1);
                }
                pack = new UploadedPack(filename, sha1, res.bin != null ? res.bin.expiry().orElse(null) : null);
                PolymerFilebin.LOGGER.info("Uploaded resource pack ({} bytes)", Files.size(path));
            }

            uploaded = pack;
            if (config.deleteOldFiles) {
                deleteOldFiles(filename);
            }
            resolveLink(pack, false);
            state = State.READY;
            PolymerFilebin.LOGGER.info("Resource pack is ready: {}", client.fileUri(config.bin, filename));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            error = e.getMessage();
            state = State.FAILED;
            PolymerFilebin.LOGGER.error("Failed to upload resource pack to Filebin", e);
        }
    }

    private @Nullable UploadedPack findExisting(String filename, String sha1) throws IOException, InterruptedException {
        var bin = client.getBin(config.bin).orElse(null);
        if (bin == null || bin.files == null || bin.bin == null) {
            return null;
        }
        var expiry = bin.bin.expiry().orElse(null);
        if (expiry != null && expiry.isBefore(Instant.now().plus(EXPIRY_MARGIN))) {
            return null;
        }
        for (var file : bin.files) {
            if (filename.equals(file.filename) && sha1.equalsIgnoreCase(file.sha1)) {
                return new UploadedPack(filename, sha1, expiry);
            }
        }
        return null;
    }

    private void deleteOldFiles(String keep) {
        try {
            var bin = client.getBin(config.bin).orElse(null);
            if (bin == null || bin.files == null) return;
            for (var file : bin.files) {
                if (file.filename != null && file.filename.startsWith(FILE_PREFIX) && !file.filename.equals(keep)) {
                    client.deleteFile(config.bin, file.filename);
                    PolymerFilebin.LOGGER.info("Deleted old resource pack {} from Filebin", file.filename);
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            PolymerFilebin.LOGGER.warn("Failed to delete old resource packs from Filebin", e);
        }
    }

    private DownloadLink resolveLink(UploadedPack pack, boolean allowReupload) {
        try {
            var url = resolveWithRetry(pack.filename(), !allowReupload);
            var newLink = new DownloadLink(url.toString(), Instant.now().plusSeconds(Math.max(30, config.downloadLinkLifetime)));
            link = newLink;
            return newLink;
        } catch (FilebinClient.FilebinException e) {
            var path = packPath;
            if (e.status == 404 && allowReupload && path != null) {
                // The file (or whole bin) is gone; put it back.
                PolymerFilebin.LOGGER.warn("Resource pack is missing from Filebin, re-uploading");
                upload(path, pack.sha1(), true);
                var current = link;
                if (state == State.READY && current != null) return current;
            }
            throw new RuntimeException("Failed to resolve download link", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to resolve download link", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Filebin briefly answers 404 right after a file is (re-)uploaded, so retry for a few seconds in that case.
     */
    private URI resolveWithRetry(String filename, boolean justUploaded) throws IOException, InterruptedException {
        int attempts = justUploaded ? 6 : 1;
        for (int i = 1; ; i++) {
            try {
                return client.resolveDownloadUrl(config.bin, filename);
            } catch (FilebinClient.FilebinException e) {
                if (e.status != 404 || i >= attempts) throw e;
                Thread.sleep(500L * i);
            }
        }
    }

    private void refreshLinkIfNeeded() {
        var pack = uploaded;
        var current = link;
        if (state != State.READY || pack == null) return;
        // Refresh a minute before the cached link is retired.
        if (current == null || Instant.now().plusSeconds(60).isAfter(current.expiresAt())) {
            try {
                resolveLink(pack, true);
            } catch (Exception e) {
                PolymerFilebin.LOGGER.warn("Failed to refresh resource pack download link", e);
            }
        }
    }

    private void retryIfFailed() {
        var path = packPath;
        if (state == State.FAILED && path != null) {
            PolymerFilebin.LOGGER.info("Retrying resource pack upload");
            upload(path, null, false);
        }
    }

    private void recheck() {
        var pack = uploaded;
        var path = packPath;
        if (state != State.READY || pack == null || path == null) return;
        try {
            var bin = client.getBin(config.bin).orElse(null);
            boolean present = bin != null && bin.files != null
                    && bin.files.stream().anyMatch(f -> pack.filename().equals(f.filename));
            var expiry = bin != null && bin.bin != null ? bin.bin.expiry().orElse(null) : null;
            if (!present) {
                PolymerFilebin.LOGGER.warn("Resource pack is missing from Filebin, re-uploading");
                upload(path, pack.sha1(), true);
            } else if (expiry != null && expiry.isBefore(Instant.now().plus(EXPIRY_MARGIN))) {
                PolymerFilebin.LOGGER.info("Filebin bin expires soon ({}), re-uploading resource pack", expiry);
                upload(path, pack.sha1(), true);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            PolymerFilebin.LOGGER.warn("Failed to check resource pack on Filebin", e);
        }
    }

    private static String hash(Path path, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance(algorithm);
            var buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
