package com.igrium.polymerfilebin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stored at {@code config/polymer-filebin.json}. Mirrors polymer-autohost's options where they make sense.
 */
public class FilebinConfig {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String BIN_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    public String _c1 = "Enables hosting Polymer's resource pack on Filebin";
    public boolean enabled = true;
    public String _c2 = "Base url of the Filebin instance";
    @SerializedName("filebin_url")
    public String filebinUrl = "https://filebin.net";
    public String _c3 = "Bin used to store the pack. Anyone who knows it can delete files from it! Generated randomly when empty.";
    public String bin = "";
    public String _c4 = "Deletes previously uploaded packs from the bin after uploading a new one";
    @SerializedName("delete_old_files")
    public boolean deleteOldFiles = true;
    public String _c5 = "How often (in minutes) to check that the pack is still present on Filebin, re-uploading it if missing or about to expire";
    @SerializedName("recheck_interval_minutes")
    public int recheckIntervalMinutes = 60;
    public String _c6 = "How long (in seconds) a resolved download link is reused before requesting a new one. filebin.net links expire after 900 seconds.";
    @SerializedName("download_link_lifetime")
    public int downloadLinkLifetime = 600;
    public String _c7 = "How many times a failed client download is retried with a fresh link";
    @SerializedName("download_retries")
    public int downloadRetries = 2;

    public String _c8 = "Marks resource pack as required";
    @SerializedName("required")
    public boolean require = false;
    public String _c9 = "Mods may override the above setting and make the resource pack required, set this to false to disable that.";
    @SerializedName("mod_override")
    public boolean modOverride = true;
    public String _c10 = "Message sent to clients before pack is loaded";
    public JsonElement message = new JsonPrimitive("This server uses resource pack to enhance gameplay with custom textures and models. It might be unplayable without them.");
    public String _c11 = "Disconnect message in case of failure";
    @SerializedName("disconnect_message")
    public JsonElement disconnectMessage = translatable("multiplayer.texturePrompt.failure.line1");
    public String _c12 = "Show more information when disconnecting the player";
    @SerializedName("informative_disconnect")
    public boolean informativeDisconnect = true;
    public String _c13 = "Additional resource packs. Objects with 'id' (uuid), 'url' and 'hash' (SHA1).";
    @SerializedName("external_resource_packs")
    public List<ExternalResourcePack> externalResourcePacks = new ArrayList<>();
    public String _c14 = "Moves resource pack generation earlier. Might break some mods.";
    @SerializedName("setup_early")
    public boolean loadEarly = false;
    public String _c15 = "Shows a dialog while the resource pack is still being generated/uploaded";
    @SerializedName("resource_pack_status_dialog")
    public boolean dialog = true;
    @SerializedName("dialog_title")
    public JsonElement dialogTitle = new JsonPrimitive("The server's resource pack is still being prepared!");
    @SerializedName("dialog_default_body")
    public JsonElement dialogDefaultBody = new JsonPrimitive("Waiting...");
    @SerializedName("dialog_body_header")
    public JsonElement dialogHeader = new JsonPrimitive("This server requires a resource pack, which hasn't finished generating or uploading yet...\nIt might take a while for it to finish!");
    @SerializedName("dialog_show_status")
    public boolean dialogShowStatus = true;
    @SerializedName("dialog_show_dots")
    public boolean dialogShowDots = true;
    public String _c16 = "Clears all client-side resource packs before sending the server's ones";
    @SerializedName("clear_all_client_resource_packs")
    public boolean clearResourcePacks = false;

    public static class ExternalResourcePack {
        public UUID id;
        public String url;
        public String hash;
    }

    public static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("polymer-filebin.json");
    }

    /**
     * Load the config, filling in defaults (and a random bin id) and writing it back.
     */
    public static FilebinConfig load() {
        var path = getPath();
        FilebinConfig config = null;
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                config = GSON.fromJson(reader, FilebinConfig.class);
            } catch (Exception e) {
                PolymerFilebin.LOGGER.error("Failed to read {}, using defaults", path, e);
            }
        }
        if (config == null) {
            config = new FilebinConfig();
        }
        if (config.bin == null || config.bin.isBlank()) {
            config.bin = randomBin();
        }
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.writeString(getPath(), GSON.toJson(this));
        } catch (IOException e) {
            PolymerFilebin.LOGGER.error("Failed to save config", e);
        }
    }

    private static String randomBin() {
        var random = new SecureRandom();
        var sb = new StringBuilder("polymer-");
        for (int i = 0; i < 16; i++) {
            sb.append(BIN_CHARS.charAt(random.nextInt(BIN_CHARS.length())));
        }
        return sb.toString();
    }

    private static JsonElement translatable(String key) {
        var obj = new JsonObject();
        obj.addProperty("translate", key);
        return obj;
    }
}
