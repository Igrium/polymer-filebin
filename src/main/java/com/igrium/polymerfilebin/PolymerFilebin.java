package com.igrium.polymerfilebin;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.resourcepack.api.OutputGenerator;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PolymerFilebin implements ModInitializer {
	public static final String MOD_ID = "polymer-filebin";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static FilebinConfig config = new FilebinConfig();
	private static @Nullable PackHost host;
	private static @Nullable String mainPackHash;

	public static @Nullable Component message;
	public static Component disconnectMessage = Component.empty();
	public static Component dialogTitle = Component.empty();
	public static Component dialogDefaultBody = Component.empty();
	public static Component dialogHeader = Component.empty();

	@Override
	public void onInitialize() {
		PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(() -> {
			mainPackHash = null;
			if (host != null) host.onGenerationStarted();
		});

		PolymerResourcePackUtils.RESOURCE_PACK_FINISHED_EVENT.register(result -> {
			if (result instanceof OutputGenerator.Result r && host != null) {
				mainPackHash = r.hash();
				host.onPackGenerated(r);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				FilebinCommand.register(dispatcher));
	}

	/**
	 * Called once the server is starting up.
	 */
	public static void init(MinecraftServer server) {
		config = FilebinConfig.load();
		if (!config.enabled) {
			return;
		}

		message = parseComponent(config.message, null);
		disconnectMessage = parseComponent(config.disconnectMessage, Component.translatable("multiplayer.texturePrompt.failure.line1"));
		dialogTitle = parseComponent(config.dialogTitle, Component.literal("The server's resource pack is still being prepared!"));
		dialogDefaultBody = parseComponent(config.dialogDefaultBody, Component.literal("Waiting..."));
		dialogHeader = parseComponent(config.dialogHeader, Component.literal("This server requires a resource pack, which isn't ready yet..."));

		warnIfAutoHostEnabled();

		host = new PackHost(config);
		host.start();
		generatePack(server);
	}

	public static void end(MinecraftServer server) {
		if (host != null) {
			host.stop();
			host = null;
		}
	}

	public static void generatePack(MinecraftServer server) {
		try {
			PolymerResourcePackMod.generateAndCall(server, true, server::sendSystemMessage, _ -> {});
		} catch (Throwable e) {
			LOGGER.warn("Failed to generate the resource pack!", e);
		}
	}

	public static FilebinConfig getConfig() {
		return config;
	}

	public static boolean isEnabled() {
		return config.enabled && host != null;
	}

	public static @Nullable PackHost getHost() {
		return host;
	}

	public static boolean isReady() {
		return host != null && host.isReady();
	}

	public static boolean isRequired() {
		return config.require || (config.modOverride && PolymerResourcePackUtils.isRequired());
	}

	public static MinecraftServer.@Nullable ServerResourcePackInfo getMainPack() {
		var url = host != null ? host.getDownloadUrl() : null;
		var hash = mainPackHash;
		if (url == null || hash == null) return null;
		return new MinecraftServer.ServerResourcePackInfo(PolymerResourcePackUtils.getMainUuid(), url, hash, isRequired(), message);
	}

	/**
	 * All packs to send to a player: the main pack (if ready) and configured external packs.
	 */
	public static List<MinecraftServer.ServerResourcePackInfo> getPacks() {
		var list = new ArrayList<MinecraftServer.ServerResourcePackInfo>();
		var main = getMainPack();
		if (main != null) list.add(main);

		for (var x : config.externalResourcePacks) {
			if (x.url == null) continue;
			var id = x.id != null ? x.id : UUID.nameUUIDFromBytes(x.url.getBytes(StandardCharsets.UTF_8));
			list.add(new MinecraftServer.ServerResourcePackInfo(id, x.url, x.hash != null ? x.hash : "", isRequired(), message));
		}
		return list;
	}

	private static void warnIfAutoHostEnabled() {
		if (!FabricLoader.getInstance().isModLoaded("polymer-autohost")) return;
		var path = FabricLoader.getInstance().getConfigDir().resolve("polymer").resolve("auto-host.json");
		try {
			if (Files.exists(path) && Files.readString(path).matches("(?s).*\"enabled\"\\s*:\\s*true.*")) {
				LOGGER.warn("polymer-autohost is enabled as well! Players will be sent the resource pack twice. Disable it in {}", path);
			}
		} catch (Exception ignored) {
		}
	}

	private static Component parseComponent(JsonElement json, Component fallback) {
		try {
			return ComponentSerialization.CODEC.decode(JsonOps.INSTANCE, json).getOrThrow().getFirst();
		} catch (Exception e) {
			return fallback;
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
