package art.arcane.wormholes.modded.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.world.level.CardinalLighting;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(Lighting.class)
public abstract class PortalLightingMixin {
    @Shadow @Final private GpuBuffer buffer;
    @Shadow @Final private long paddedSize;

    @Unique
    private final GpuBufferSlice[] wormholes$levelSlices = new GpuBufferSlice[CardinalLighting.Type.values().length];

    @Unique
    private GpuBufferSlice wormholes$levelSlice;

    @Unique
    private boolean wormholes$closed;

    @WrapMethod(method = "close")
    private void wormholes$closeLevelSlices(Operation<Void> original) {
        wormholes$closed = true;
        wormholes$levelSlice = null;
        Throwable failure = null;
        try {
            original.call();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (int index = 0; index < wormholes$levelSlices.length; index++) {
            GpuBufferSlice slice = wormholes$levelSlices[index];
            if (slice == null) {
                continue;
            }
            wormholes$levelSlices[index] = null;
            try {
                slice.buffer().close();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    @Redirect(method = "updateLevel", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/Lighting;updateBuffer(Lcom/mojang/blaze3d/platform/Lighting$Entry;Lorg/joml/Vector3fc;Lorg/joml/Vector3fc;)V"),
        require = 2, allow = 2)
    private void wormholes$selectLevelSlice(Lighting lighting, Lighting.Entry entry, Vector3fc first, Vector3fc second,
                                          CardinalLighting.Type type) {
        if (wormholes$closed) {
            throw new IllegalStateException("Lighting is closed");
        }
        boolean restore = wormholes$ownsLevelSlice(RenderSystem.getShaderLights());
        int index = type.ordinal();
        GpuBufferSlice slice = wormholes$levelSlices[index];
        if (slice == null) {
            slice = wormholes$createLevelSlice(type, first, second);
            wormholes$levelSlices[index] = slice;
        }
        wormholes$levelSlice = slice;
        if (restore) {
            RenderSystem.setShaderLights(slice);
        }
    }

    @Inject(method = "setupFor", at = @At("HEAD"), cancellable = true)
    private void wormholes$setupLevelSlice(Lighting.Entry entry, CallbackInfo callback) {
        if (entry == Lighting.Entry.LEVEL && wormholes$levelSlice != null) {
            RenderSystem.setShaderLights(wormholes$levelSlice);
            callback.cancel();
        }
    }

    @Unique
    private GpuBufferSlice wormholes$createLevelSlice(CardinalLighting.Type type, Vector3fc first, Vector3fc second) {
        GpuBuffer created = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, Lighting.UBO_SIZE).putVec3(first).putVec3(second).get();
            created = RenderSystem.getDevice().createBuffer(() -> "Wormholes LEVEL lighting " + type.getSerializedName(),
                GpuBuffer.USAGE_UNIFORM, data);
            return created.slice(0L, Lighting.UBO_SIZE);
        } catch (RuntimeException | Error failure) {
            if (created != null) {
                try {
                    created.close();
                } catch (RuntimeException | Error exception) {
                    if (failure != exception) {
                        failure.addSuppressed(exception);
                    }
                }
            }
            throw failure;
        }
    }

    @Unique
    private boolean wormholes$ownsLevelSlice(GpuBufferSlice selected) {
        if (selected == null || selected.length() != Lighting.UBO_SIZE) {
            return false;
        }
        if (selected.buffer() == buffer && selected.offset() == Lighting.Entry.LEVEL.ordinal() * paddedSize) {
            return true;
        }
        for (GpuBufferSlice slice : wormholes$levelSlices) {
            if (slice != null && selected.buffer() == slice.buffer() && selected.offset() == slice.offset()) {
                return true;
            }
        }
        return false;
    }
}
