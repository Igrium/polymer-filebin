package com.igrium.polymerfilebin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import eu.pb4.polymer.common.impl.CommonImplUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.net.URI;
import java.util.Optional;

import static net.minecraft.commands.Commands.literal;

public final class FilebinCommand {
    private FilebinCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("polymer-filebin")
                .requires(CommonImplUtils.permission("filebin.command", 3))
                .then(literal("status").executes(FilebinCommand::status))
                .then(literal("reupload").executes(FilebinCommand::reupload))
                .then(literal("regenerate").executes(FilebinCommand::regenerate))
                .then(literal("resend").executes(FilebinCommand::resend)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var host = PolymerFilebin.getHost();
        if (host == null) {
            source.sendFailure(Component.literal("Polymer Filebin is disabled"));
            return 0;
        }
        var state = host.getState();
        source.sendSuccess(() -> Component.literal("State: " + state + (host.getError() != null ? " (" + host.getError() + ")" : "")), false);
        var uploaded = host.getUploaded();
        if (uploaded != null) {
            source.sendSuccess(() -> Component.literal("Pack: " + uploaded.filename() + ", bin expires " + uploaded.expiry()), false);
        }
        var binUrl = host.getBinUrl();
        source.sendSuccess(() -> Component.literal("Bin: ").append(link(binUrl)), false);
        var url = host.getDownloadUrl();
        if (url != null) {
            source.sendSuccess(() -> Component.literal("Download link: ").append(link(url)), false);
        }
        return 1;
    }

    private static int reupload(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var host = PolymerFilebin.getHost();
        if (host == null) {
            source.sendFailure(Component.literal("Polymer Filebin is disabled"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Re-uploading resource pack..."), true);
        host.reupload().whenComplete((_, e) -> source.getServer().execute(() -> {
            if (e != null || host.getState() != PackHost.State.READY) {
                source.sendFailure(Component.literal("Upload failed: " + (e != null ? e.getMessage() : host.getError())));
            } else {
                source.sendSuccess(() -> Component.literal("Resource pack uploaded"), true);
            }
        }));
        return 1;
    }

    private static int regenerate(CommandContext<CommandSourceStack> ctx) {
        if (PolymerFilebin.getHost() == null) {
            ctx.getSource().sendFailure(Component.literal("Polymer Filebin is disabled"));
            return 0;
        }
        PolymerFilebin.generatePack(ctx.getSource().getServer());
        return 1;
    }

    private static int resend(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        if (!PolymerFilebin.isReady()) {
            source.sendFailure(Component.literal("The resource pack isn't ready yet"));
            return 0;
        }
        var packs = PolymerFilebin.getPacks();
        var players = source.getServer().getPlayerList().getPlayers();
        for (var player : players) {
            for (var pack : packs) {
                player.connection.send(new ClientboundResourcePackPushPacket(pack.id(), pack.url(), pack.hash(), pack.isRequired(), Optional.ofNullable(pack.prompt())));
            }
        }
        source.sendSuccess(() -> Component.literal("Sent " + packs.size() + " resource pack(s) to " + players.size() + " player(s)"), true);
        return players.size();
    }

    private static Component link(String url) {
        return Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
    }
}
