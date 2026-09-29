package com.igrium.polymerfilebin.mixin;

import com.igrium.polymerfilebin.FilebinPackTask;
import com.igrium.polymerfilebin.PolymerFilebin;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImpl {
    @Shadow @Final private Queue<ConfigurationTask> configurationTasks;
    @Shadow private @Nullable ConfigurationTask currentTask;

    @Shadow protected abstract void finishCurrentTask(ConfigurationTask.Type key);
    @Shadow protected abstract GameProfile playerProfile();

    public ServerConfigurationPacketListenerImplMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Inject(method = "addOptionalTasks", at = @At("TAIL"))
    private void polymerFilebin$addTask(CallbackInfo ci) {
        if (PolymerFilebin.isEnabled() && !this.server.isSingleplayerOwner(new NameAndId(this.playerProfile()))) {
            this.configurationTasks.add(new FilebinPackTask((ServerConfigurationPacketListenerImpl) (Object) this));
        }
    }

    @Inject(method = "handleResourcePackResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerCommonPacketListenerImpl;handleResourcePackResponse(Lnet/minecraft/network/protocol/common/ServerboundResourcePackPacket;)V", shift = At.Shift.AFTER), cancellable = true)
    private void polymerFilebin$onStatus(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        if (this.currentTask instanceof FilebinPackTask task) {
            task.onStatus(packet.id(), packet.action());
            if (task.isDone() && this.currentTask == task) {
                this.finishCurrentTask(FilebinPackTask.KEY);
            }
            ci.cancel();
        }
    }

    /**
     * Vanilla finishes its resource pack task on any terminal status; only do that for the vanilla pack.
     */
    @WrapWithCondition(method = "handleResourcePackResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;finishCurrentTask(Lnet/minecraft/server/network/ConfigurationTask$Type;)V"))
    private boolean polymerFilebin$checkType(ServerConfigurationPacketListenerImpl instance, ConfigurationTask.Type key, @Local(argsOnly = true) ServerboundResourcePackPacket packet) {
        return key != ServerResourcePackConfigurationTask.TYPE
                || (this.server.getServerResourcePack().isPresent() && this.server.getServerResourcePack().get().id().equals(packet.id()));
    }
}
