/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the clip equation upload of MixinIrisSodiumShader and FrontClipping, applied to every clipped Iris
 * program with the clip distance that program's transformation chose.
 */
package art.arcane.wormholes.modded.client.render.iris;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayDeque;

public final class IrisClipPlanes {
    private static final int MAX_DISTANCES = 8;
    private static final ArrayDeque<Vector4fc> PLANES = new ArrayDeque<>();
    private static int enabled = -1;

    private IrisClipPlanes() {
    }

    public static State capture() {
        int distances = 0;
        for (int distance = 0; distance < MAX_DISTANCES; distance++) {
            if (GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + distance)) {
                distances |= 1 << distance;
            }
        }
        return new State(distances, enabled);
    }

    public static void apply(int location, int distance) {
        apply(location, distance, PLANES.peek());
    }

    public static void apply(int location, int distance, Vector4fc plane) {
        if (plane == null || location < 0) {
            release();
            return;
        }
        if (enabled != distance) {
            release();
            GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + distance);
            enabled = distance;
        }
        GL20C.glUniform4f(location, plane.x(), plane.y(), plane.z(), plane.w());
    }

    public static void release() {
        if (enabled >= 0) {
            GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + enabled);
            enabled = -1;
        }
    }

    static void push(Vector4fc plane) {
        PLANES.push(new Vector4f(plane));
    }

    static void pop() {
        PLANES.pop();
        release();
    }

    public record State(int distances, int enabledDistance) {
        public void restore() {
            for (int distance = 0; distance < MAX_DISTANCES; distance++) {
                if ((distances & (1 << distance)) != 0) {
                    GL11C.glEnable(GL30C.GL_CLIP_DISTANCE0 + distance);
                } else {
                    GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
                }
            }
            enabled = enabledDistance;
        }
    }
}
