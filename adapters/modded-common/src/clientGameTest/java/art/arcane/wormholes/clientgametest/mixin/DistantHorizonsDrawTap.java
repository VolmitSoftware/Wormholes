package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.DistantHorizonsTap;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.lwjgl.ILWJGLService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram", remap = false)
public abstract class DistantHorizonsDrawTap {
    @Inject(method = "render", at = @At("HEAD"))
    private void wormholesTest$terrain(RenderParams params, boolean opaque, SortedArraySet<LodBufferContainer> buffers,
                                       IProfilerWrapper profiler, CallbackInfo callback) {
        DistantHorizonsTap.beginTerrain(params);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wormholesTest$terrainEnd(CallbackInfo callback) {
        DistantHorizonsTap.endTerrain();
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/api/interfaces/override/rendering/IDhApiShaderProgram;setModelOffsetPos(Lcom/seibel/distanthorizons/api/objects/math/DhApiVec3f;)V"))
    private void wormholesTest$offset(IDhApiShaderProgram program, DhApiVec3f position) {
        DistantHorizonsTap.modelOffset(position);
        program.setModelOffsetPos(position);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/util/objects/SortedArraySet;get(I)Ljava/lang/Object;"))
    private Object wormholesTest$buffer(SortedArraySet<LodBufferContainer> buffers, int index) {
        LodBufferContainer container = buffers.get(index);
        DistantHorizonsTap.buffer(container);
        return container;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/lwjgl/ILWJGLService;glDrawElements(IIII)V"))
    private void wormholesTest$draw(ILWJGLService service, int mode, int count, int type, int indices) {
        int query = DistantHorizonsTap.beforeDraw(count);
        try {
            service.glDrawElements(mode, count, type, indices);
        } finally {
            DistantHorizonsTap.afterDraw(query);
        }
    }
}
