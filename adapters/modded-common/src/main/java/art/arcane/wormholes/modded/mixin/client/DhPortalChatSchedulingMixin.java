package art.arcane.wormholes.modded.mixin.client;

import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper", remap = false)
public abstract class DhPortalChatSchedulingMixin {
    @Redirect(method = "sendChatMessage", at = @At(value = "INVOKE",
        target = "Lcom/seibel/distanthorizons/core/render/RenderThreadTaskHandler;queueRunningOnRenderThread(Ljava/lang/String;Ljava/lang/Runnable;)V"))
    private void wormholes$scheduleChat(RenderThreadTaskHandler handler, String label, Runnable task) {
        Minecraft.getInstance().schedule(task);
    }
}
