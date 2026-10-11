package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import art.arcane.wormholes.modded.mixin.client.DhPortalBlazeTemporalAccess;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

public final class NativeDhTemporalAssertions {
    private static final ArrayDeque<Parent> PENDING = new ArrayDeque<>();
    private static final Map<DhBlazeTemporalViews.Scope, Parent> SCOPES = new IdentityHashMap<>();
    private static boolean verified;

    private NativeDhTemporalAssertions() {
    }

    public static void reset() {
        PENDING.clear();
        SCOPES.clear();
        verified = false;
    }

    public static void opening(DhPortalBlazeTemporalAccess access) {
        PENDING.push(Parent.capture(access));
    }

    public static void opened(RenderParams parameters, DhPortalBlazeTemporalAccess access, DhBlazeTemporalViews.Scope scope) {
        SCOPES.put(scope, PENDING.pop());
        if (verified) {
            return;
        }
        verified = true;
        Parent parent = Parent.capture(access);
        IllegalStateException expected = new IllegalStateException("dh-temporal-scope-exit");
        try {
            try (DhBlazeTemporalViews.Scope child = DhBlazeTemporalViews.open(parameters, access)) {
                access.wormholes$historyA(!access.wormholes$historyA());
                access.wormholes$previousMatrix(new DhMat4f());
                access.wormholes$previousCamera(new DhVec3d(22.0D, 33.0D, 44.0D));
                throw expected;
            }
        } catch (IllegalStateException failure) {
            if (failure != expected) {
                throw new AssertionError("Distant Horizons temporal scope failed during its exceptional exit", failure);
            }
        }
        parent.verify();
        System.out.println("WORMHOLES_DH_TAA_SCOPE_PASS parent=true nested=true exceptional=true");
    }

    public static void closed(DhBlazeTemporalViews.Scope scope) {
        Parent parent = SCOPES.remove(scope);
        if (parent != null) {
            parent.verify();
        }
    }

    private record Parent(DhPortalBlazeTemporalAccess access, BlazeTextureWrapper colorA, BlazeTextureWrapper colorB,
                          BlazeTextureWrapper depth, boolean historyA, DhMat4f matrix, DhVec3d camera,
                          DhMat4f matrixValues, DhVec3d cameraValues) {
        private static Parent capture(DhPortalBlazeTemporalAccess access) {
            return new Parent(access, access.wormholes$colorA(), access.wormholes$colorB(), access.wormholes$depth(),
                access.wormholes$historyA(), access.wormholes$previousMatrix(), access.wormholes$previousCamera(),
                access.wormholes$previousMatrix().copy(), access.wormholes$previousCamera().copy());
        }

        private void verify() {
            if (access.wormholes$colorA() != colorA || access.wormholes$colorB() != colorB || access.wormholes$depth() != depth
                || access.wormholes$historyA() != historyA || access.wormholes$previousMatrix() != matrix
                || access.wormholes$previousCamera() != camera || !matrix.equals(matrixValues)
                || camera.x != cameraValues.x || camera.y != cameraValues.y || camera.z != cameraValues.z) {
                throw new AssertionError("Distant Horizons temporal scope changed its parent's history textures, flip, matrix or camera");
            }
        }
    }
}
