package com.igrium.polymerfilebin;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Configuration-phase task that sends the resource packs, waiting (with a dialog) until the pack is uploaded,
 * and retrying failed downloads with a freshly resolved link.
 */
public class FilebinPackTask implements ConfigurationTask {
    public static final Type KEY = new Type("polymer-filebin:send_packs");
    public static final Identifier DISCONNECT = PolymerFilebin.id("disconnect");

    private final ServerConfigurationPacketListenerImpl handler;
    private final Map<UUID, MinecraftServer.ServerResourcePackInfo> packs = new HashMap<>();
    private final Set<UUID> waitingFor = new HashSet<>();
    private final Map<UUID, Integer> retries = new HashMap<>();
    private final Map<UUID, CompletableFuture<PackHost.DownloadLink>> pendingRetries = new HashMap<>();

    private Consumer<Packet<?>> sender = _ -> {};
    private boolean waitingForPack;
    private boolean done;
    private int tick = 0;

    public FilebinPackTask(ServerConfigurationPacketListenerImpl handler) {
        this.handler = handler;
    }

    @Override
    public void start(Consumer<Packet<?>> sender) {
        this.sender = sender;
        if (PolymerFilebin.getConfig().clearResourcePacks) {
            sender.accept(new ClientboundResourcePackPopPacket(Optional.empty()));
        }

        if (PolymerFilebin.isReady()) {
            sendPacks();
        } else {
            waitingForPack = true;
            sendDialog();
        }
    }

    private void sendPacks() {
        for (var pack : PolymerFilebin.getPacks()) {
            packs.put(pack.id(), pack);
            waitingFor.add(pack.id());
            push(pack);
        }
        done = waitingFor.isEmpty();
    }

    private void push(MinecraftServer.ServerResourcePackInfo pack) {
        sender.accept(new ClientboundResourcePackPushPacket(pack.id(), pack.url(), pack.hash(), pack.isRequired(), Optional.ofNullable(pack.prompt())));
    }

    @Override
    public boolean tick() {
        if (waitingForPack) {
            if (PolymerFilebin.isReady()) {
                waitingForPack = false;
                if (PolymerFilebin.getConfig().dialog) {
                    sender.accept(ClientboundClearDialogPacket.INSTANCE);
                }
                sendPacks();
            } else if (++tick % 2 == 0) {
                sendDialog();
            }
        }

        var it = pendingRetries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var future = entry.getValue();
            if (!future.isDone()) continue;
            it.remove();

            var id = entry.getKey();
            var old = packs.get(id);
            var link = future.exceptionally(_ -> null).join();
            if (link != null && old != null) {
                var pack = new MinecraftServer.ServerResourcePackInfo(id, link.url(), old.hash(), old.isRequired(), old.prompt());
                packs.put(id, pack);
                push(pack);
            } else {
                PolymerFilebin.LOGGER.warn("Couldn't get a new download link for {}", handler.getOwner().name());
                fail(id, ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD);
                waitingFor.remove(id);
                checkDone();
            }
        }

        return done;
    }

    /**
     * Handle a resource pack status from the client.
     */
    public void onStatus(UUID id, ServerboundResourcePackPacket.Action status) {
        if ((status == ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD || status == ServerboundResourcePackPacket.Action.INVALID_URL)
                && id.equals(PolymerResourcePackUtils.getMainUuid()) && packs.containsKey(id)) {
            int attempt = retries.merge(id, 1, Integer::sum);
            var host = PolymerFilebin.getHost();
            if (attempt <= PolymerFilebin.getConfig().downloadRetries && host != null) {
                PolymerFilebin.LOGGER.info("{} failed to download the resource pack ({}), retrying with a new link ({}/{})",
                        handler.getOwner().name(), status, attempt, PolymerFilebin.getConfig().downloadRetries);
                pendingRetries.put(id, host.requestNewLink());
                return;
            }
        }

        switch (status) {
            case DECLINED, FAILED_RELOAD, FAILED_DOWNLOAD, INVALID_URL -> fail(id, status);
            default -> {}
        }

        if (status.isTerminal()) {
            waitingFor.remove(id);
        }
        checkDone();
    }

    private void checkDone() {
        if (waitingFor.isEmpty() && pendingRetries.isEmpty() && !waitingForPack) {
            done = true;
        }
    }

    private void fail(UUID id, ServerboundResourcePackPacket.Action status) {
        var pack = packs.get(id);
        if (pack == null || !pack.isRequired()) return;

        Component text = PolymerFilebin.disconnectMessage;
        if (PolymerFilebin.getConfig().informativeDisconnect) {
            var packInfo = (pack.hash().isEmpty() ? "<NO HASH>" : pack.hash()) + "\n" + pack.url();
            var main = PolymerResourcePackUtils.getMainUuid().equals(id) ? " (Main)" : "";
            text = Component.empty().append(text)
                    .append("\n\n")
                    .append(Component.literal(status.name() + " > " + id + main + "\n" + packInfo).withStyle(ChatFormatting.GRAY));
        }
        handler.disconnect(text);
    }

    private void sendDialog() {
        var config = PolymerFilebin.getConfig();
        if (!config.dialog) return;

        var list = new ArrayList<DialogBody>(4);
        list.add(new PlainMessage(PolymerFilebin.dialogHeader, 300));

        if (config.dialogShowDots) {
            var sb = new StringBuilder();
            var index = ((this.tick - 1) / 2) % 10;
            if (((this.tick - 1) / 2) % 20 >= 10) {
                index = 9 - index;
            }
            for (int i = 1; i < index; i++) sb.append('_');
            if (index > 0) sb.append('o');
            sb.append('O');
            if (sb.length() < 10) {
                sb.append('o');
                while (sb.length() < 10) sb.append('_');
            }
            list.add(new PlainMessage(Component.literal(sb.toString()).withStyle(ChatFormatting.GRAY), 200));
        }

        list.add(new PlainMessage(config.dialogShowStatus ? statusText() : PolymerFilebin.dialogDefaultBody, 300));

        sender.accept(new ClientboundShowDialogPacket(Holder.direct(new NoticeDialog(
                new CommonDialogData(PolymerFilebin.dialogTitle, Optional.empty(), false, false, DialogAction.CLOSE, list, List.of()),
                new ActionButton(new CommonButtonData(Component.translatable("menu.disconnect"), 150),
                        Optional.of(new CustomAll(DISCONNECT, Optional.empty())))
        ))));
    }

    private Component statusText() {
        var host = PolymerFilebin.getHost();
        var state = host != null ? host.getState() : null;
        if (state == PackHost.State.UPLOADING) {
            return Component.literal("Uploading resource pack...");
        } else if (state == PackHost.State.FAILED) {
            return Component.literal("Failed to upload the resource pack! Please contact the server administrator.");
        }

        var status = PolymerResourcePackMod.STATUS;
        if (status.isEmpty()) return PolymerFilebin.dialogDefaultBody;
        return Component.literal(String.join("\n", status.subList(Math.max(status.size() - 6, 0), status.size())));
    }

    public boolean isDone() {
        return done;
    }

    @Override
    public Type type() {
        return KEY;
    }
}
