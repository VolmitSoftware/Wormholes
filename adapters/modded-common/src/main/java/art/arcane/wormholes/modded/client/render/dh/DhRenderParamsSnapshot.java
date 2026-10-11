package art.arcane.wormholes.modded.client.render.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

final class DhRenderParamsSnapshot {
    private final RenderParams target;
    private final DhApiRenderParam parameters = new DhApiRenderParam();
    private final DhApiRenderParam apiCopy = new DhApiRenderParam();
    private final IDhClientWorld world;
    private final IDhClientLevel level;
    private final IClientLevelWrapper wrapper;
    private final ILightMapWrapper lightmap;
    private final RenderBufferHandler buffers;
    private final IDhGenericRenderer generic;
    private final DhVec3d camera;
    private final boolean fog;
    private final boolean validated;

    DhRenderParamsSnapshot(RenderParams target) {
        this.target = target;
        copy(parameters, target);
        copy(apiCopy, target.apiCopy);
        world = target.dhClientWorld;
        level = target.dhClientLevel;
        wrapper = target.clientLevelWrapper;
        lightmap = target.lightmap;
        buffers = target.renderBufferHandler;
        generic = target.genericRenderer;
        camera = target.exactCameraPosition;
        fog = target.vanillaFogEnabled;
        validated = target.hasBeenValidated;
    }

    void restore() {
        copy(target, parameters);
        copy(target.apiCopy, apiCopy);
        target.dhClientWorld = world;
        target.dhClientLevel = level;
        target.clientLevelWrapper = wrapper;
        target.lightmap = lightmap;
        target.renderBufferHandler = buffers;
        target.genericRenderer = generic;
        target.exactCameraPosition = camera;
        target.vanillaFogEnabled = fog;
        target.hasBeenValidated = validated;
    }

    private static void copy(DhApiRenderParam target, DhApiRenderParam source) {
        target.renderPass = source.renderPass;
        target.partialTicks = source.partialTicks;
        target.nearClipPlane = source.nearClipPlane;
        target.farClipPlane = source.farClipPlane;
        target.worldYOffset = source.worldYOffset;
        target.clientLevelWrapper = source.clientLevelWrapper;
        target.mcProjectionMatrix.set(source.mcProjectionMatrix);
        target.mcModelViewMatrix.set(source.mcModelViewMatrix);
        target.mcInverseMvmProjectionMatrix.set(source.mcInverseMvmProjectionMatrix);
        target.dhProjectionMatrix.set(source.dhProjectionMatrix);
        target.dhModelViewMatrix.set(source.dhModelViewMatrix);
        target.dhMvmProjMatrix.set(source.dhMvmProjMatrix);
        target.dhInverseMvmProjectionMatrix.set(source.dhInverseMvmProjectionMatrix);
    }
}
