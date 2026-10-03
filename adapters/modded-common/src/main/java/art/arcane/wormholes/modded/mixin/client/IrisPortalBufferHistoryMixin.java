package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisBufferHistory;
import art.arcane.wormholes.modded.client.render.PortalIrisHistory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBuffer", remap = false)
public abstract class IrisPortalBufferHistoryMixin implements PortalIrisHistory.State, PortalIrisBufferHistory.Writer {
    @Shadow @Final protected BuiltShaderStorageInfo info;
    @Shadow @Final protected ByteBuffer content;
    @Shadow protected int id;
    @Unique private PortalIrisBufferHistory wormholes$history;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$capture(CallbackInfo callback) {
        if (PortalIrisHistory.capturing()) {
            wormholes$history = new PortalIrisBufferHistory(info.relative() ? null : content);
            PortalIrisHistory.register(this);
        }
    }

    @WrapOperation(method = {"createStatic", "resizeIfRelative"}, at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;bufferStorage(IJI)V"))
    private void wormholes$allocated(int target, long bytes, int flags, Operation<Void> original) {
        original.call(target, bytes, flags);
        if (wormholes$history != null) {
            wormholes$history.allocated(bytes);
        }
    }

    @Override
    public void wormholes$resetHistory() {
        int previous = GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, id);
        try {
            wormholes$history.reset(this);
        } finally {
            GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, previous);
        }
    }

    @Override
    public void clear(long bytes) {
        IrisRenderSystem.clearBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_R8, 0, bytes,
            GL43C.GL_RED, GL43C.GL_BYTE, new int[]{0});
    }

    @Override
    public void restore(byte[] bytes, int length) {
        ByteBuffer buffer = MemoryUtil.memAlloc(length);
        try {
            buffer.put(bytes, 0, length).flip();
            GlStateManager._glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Override
    public void close() {
        if (wormholes$history != null) {
            wormholes$history.close();
        }
    }
}
