package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalSodiumSectionAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumPreparedSectionsMixin implements PortalSodiumSectionAccess {
    @Shadow private boolean needsGraphUpdate;
    @Shadow private boolean needsRenderListUpdate;
    @Shadow private SectionTree renderTree;
    @Unique private long wormholes$graphRevision;
    @Unique private long wormholes$pendingRevision;
    @Unique private final SectionTree[] wormholes$trees = new SectionTree[CullType.values().length];
    @Unique private final long[] wormholes$treeRevisions = new long[CullType.values().length];
    @Unique private long wormholes$listRevision = -1;

    @Override
    @Invoker("getRenderSection")
    public abstract RenderSection wormholes$terrainSection(int x, int y, int z);

    @Override
    public boolean wormholes$visibilityReady() {
        return !needsGraphUpdate && !needsRenderListUpdate && wormholes$listRevision == wormholes$graphRevision;
    }

    @Inject(method = "markGraphDirty", at = @At("HEAD"))
    private void wormholes$graphChanged(CallbackInfo callback) {
        wormholes$graphRevision++;
    }

    @Inject(method = "scheduleAsyncWork", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/async/CullTask;submitTo(Ljava/util/concurrent/ExecutorService;)V"))
    private void wormholes$scheduledVisibility(Viewport viewport, FogParameters fog, boolean spectator, CallbackInfo callback) {
        wormholes$pendingRevision = wormholes$graphRevision;
    }

    @WrapOperation(method = "consumeCullTaskResults", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object wormholes$cacheVisibility(Map<CullType, SectionTree> cache, Object key, Object value,
                                           Operation<Object> original) {
        CullType type = (CullType) key;
        SectionTree tree = (SectionTree) value;
        Object previous = original.call(cache, key, value);
        wormholes$trees[type.ordinal()] = tree;
        wormholes$treeRevisions[type.ordinal()] = wormholes$pendingRevision;
        return previous;
    }

    @Inject(method = "readRenderListFromTree", at = @At(value = "FIELD",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderTree:Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/SectionTree;",
        opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void wormholes$selectedVisibility(Viewport viewport, FogParameters fog, CallbackInfo callback) {
        wormholes$listRevision = -1;
        for (int index = 0; index < wormholes$trees.length; index++) {
            if (wormholes$trees[index] == renderTree && renderTree != null) {
                wormholes$listRevision = wormholes$treeRevisions[index];
                return;
            }
        }
    }

    @Inject(method = "renderOutOfGraph", at = @At(value = "FIELD",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;renderTree:Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/SectionTree;",
        opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void wormholes$fallbackVisibility(Viewport viewport, FogParameters fog, CallbackInfo callback) {
        wormholes$listRevision = wormholes$graphRevision;
    }
}
