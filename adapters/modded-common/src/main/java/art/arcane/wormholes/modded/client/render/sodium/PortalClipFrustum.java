/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the inner portal culling of FrustumCuller and MixinSodiumViewport as a Sodium frustum that also
 * rejects everything behind the destination plane, in the camera-relative space Sodium culls in.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4dc;

public final class PortalClipFrustum implements Frustum {
    public static final Frustum OPEN = new Open();
    private static final float SECTION_RADIUS = 9.125F;

    private final Frustum base;
    private final float x;
    private final float y;
    private final float z;
    private final float w;
    private final float extent;

    private PortalClipFrustum(Frustum base, float x, float y, float z, float w) {
        this.base = base;
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        extent = Math.abs(x) + Math.abs(y) + Math.abs(z);
    }

    public static PortalClipFrustum clipped(Frustum base, Vector4dc worldPlane, double cameraX, double cameraY, double cameraZ) {
        double relative = worldPlane.w() + worldPlane.x() * cameraX + worldPlane.y() * cameraY + worldPlane.z() * cameraZ;
        return new PortalClipFrustum(base, (float) worldPlane.x(), (float) worldPlane.y(), (float) worldPlane.z(), (float) relative);
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return farthest(minX, minY, minZ, maxX, maxY, maxZ) >= 0.0F && base.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (farthest(minX, minY, minZ, maxX, maxY, maxZ) < 0.0F) {
            return FrustumIntersection.OUTSIDE;
        }
        int result = base.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
        if (result == FrustumIntersection.INSIDE && nearest(minX, minY, minZ, maxX, maxY, maxZ) < 0.0F) {
            return FrustumIntersection.INTERSECT;
        }
        return result;
    }

    @Override
    public boolean testSection(float centerX, float centerY, float centerZ) {
        return reaches(centerX, centerY, centerZ, SECTION_RADIUS) && base.testSection(centerX, centerY, centerZ);
    }

    @Override
    public boolean testSectionExpanded(float centerX, float centerY, float centerZ, float extend) {
        return reaches(centerX, centerY, centerZ, SECTION_RADIUS + extend) && base.testSectionExpanded(centerX, centerY, centerZ, extend);
    }

    private boolean reaches(float centerX, float centerY, float centerZ, float radius) {
        return x * centerX + y * centerY + z * centerZ + w + radius * extent >= 0.0F;
    }

    private float farthest(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return x * (x >= 0.0F ? maxX : minX) + y * (y >= 0.0F ? maxY : minY) + z * (z >= 0.0F ? maxZ : minZ) + w;
    }

    private float nearest(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return x * (x >= 0.0F ? minX : maxX) + y * (y >= 0.0F ? minY : maxY) + z * (z >= 0.0F ? minZ : maxZ) + w;
    }

    private static final class Open implements Frustum {
        @Override
        public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return true;
        }

        @Override
        public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            return FrustumIntersection.INSIDE;
        }

        @Override
        public boolean testSection(float centerX, float centerY, float centerZ) {
            return true;
        }

        @Override
        public boolean testSectionExpanded(float centerX, float centerY, float centerZ, float extend) {
            return true;
        }
    }
}
