package com.igrium.polymerfilebin.mixin;

import com.igrium.polymerfilebin.FilebinConfig;
import com.igrium.polymerfilebin.PolymerFilebin;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique
    private boolean polymerFilebin$started;

    @Inject(method = "runServer", at = @At("HEAD"))
    private void polymerFilebin$initEarly(CallbackInfo ci) {
        try {
            if (FilebinConfig.load().loadEarly) {
                PolymerFilebin.init((MinecraftServer) (Object) this);
                this.polymerFilebin$started = true;
            }
        } catch (Throwable e) {
            PolymerFilebin.LOGGER.error("Failed to initialize", e);
        }
    }

    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()Lnet/minecraft/network/protocol/status/ServerStatus;"))
    private void polymerFilebin$init(CallbackInfo ci) {
        if (!this.polymerFilebin$started) {
            this.polymerFilebin$started = true;
            PolymerFilebin.init((MinecraftServer) (Object) this);
        }
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void polymerFilebin$end(CallbackInfo ci) {
        PolymerFilebin.end((MinecraftServer) (Object) this);
    }
}
