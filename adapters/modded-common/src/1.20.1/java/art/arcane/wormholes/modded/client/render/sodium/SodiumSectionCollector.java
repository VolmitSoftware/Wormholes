/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the inner portal culling of FrustumCuller and MixinSodiumRenderSectionManager for Sodium 0.5,
 * a nearest-first sweep of every loaded section that skips the occlusion graph, since a portal camera usually sits in
 * a section the graph cannot start from, and rejects sections behind the destination plane.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4dc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class SodiumSectionCollector {
    private static final int SECTION_SIZE = 16;

    private SodiumSectionCollector() {
    }

    /**
     * Collects the sections within {@code distance} of the viewport, nearest first. A section is drawn when it passes the
     * frustum (if {@code cullFrustum}) and reaches the kept side of {@code plane}; every in-range section with a pending
     * update is queued for building.
     */
    static Result collect(SodiumPortalSections sections, Viewport viewport, boolean cullFrustum, @Nullable Vector4dc plane, float distance,
                          int frame, boolean unlimitedQueues) {
        CameraTransform camera = viewport.getTransform();
        List<Candidate> candidates = new ArrayList<>();
        for (RenderSection section : sections.wormholes$storage().values()) {
            if (section.isDisposed() || !withinDistance(camera, section, distance)) {
                continue;
            }
            candidates.add(new Candidate(section, section.getSquaredDistance((float) camera.x, (float) camera.y, (float) camera.z)));
        }
        candidates.sort((left, right) -> Float.compare(left.distance(), right.distance()));
        SortedRenderLists.Builder lists = new SortedRenderLists.Builder(frame);
        Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuilds = new EnumMap<>(ChunkUpdateType.class);
        for (ChunkUpdateType type : ChunkUpdateType.values()) {
            rebuilds.put(type, new ArrayDeque<>());
        }
        boolean complete = true;
        for (Candidate candidate : candidates) {
            RenderSection section = candidate.section();
            if ((!cullFrustum || OcclusionCuller.isWithinFrustum(viewport, section)) && (plane == null || reaches(plane, section))) {
                section.setLastVisibleFrame(frame);
                lists.add(section);
            }
            ChunkUpdateType pending = section.getPendingUpdate();
            if (pending != null && section.getBuildCancellationToken() == null) {
                ArrayDeque<RenderSection> queue = rebuilds.get(pending);
                if (unlimitedQueues || queue.size() < pending.getMaximumQueueSize()) {
                    queue.add(section);
                }
            }
            complete &= complete(section);
        }
        return new Result(lists.build(), rebuilds, complete);
    }

    /** Built, uploaded and with nothing queued or running. */
    static boolean complete(@Nullable RenderSection section) {
        return section != null && !section.isDisposed() && section.isBuilt() && section.getPendingUpdate() == null
            && section.getBuildCancellationToken() == null;
    }

    static boolean pending(Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuilds) {
        for (ArrayDeque<RenderSection> queue : rebuilds.values()) {
            if (!queue.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean reaches(Vector4dc plane, RenderSection section) {
        double x = section.getOriginX() + (plane.x() >= 0.0 ? SECTION_SIZE : 0);
        double y = section.getOriginY() + (plane.y() >= 0.0 ? SECTION_SIZE : 0);
        double z = section.getOriginZ() + (plane.z() >= 0.0 ? SECTION_SIZE : 0);
        return plane.x() * x + plane.y() * y + plane.z() * z + plane.w() >= 0.0;
    }

    private static boolean withinDistance(CameraTransform camera, RenderSection section, float distance) {
        int x = section.getOriginX() - camera.intX;
        int y = section.getOriginY() - camera.intY;
        int z = section.getOriginZ() - camera.intZ;
        float nearestX = nearestToZero(x, x + SECTION_SIZE) - camera.fracX;
        float nearestY = nearestToZero(y, y + SECTION_SIZE) - camera.fracY;
        float nearestZ = nearestToZero(z, z + SECTION_SIZE) - camera.fracZ;
        return nearestX * nearestX + nearestZ * nearestZ < distance * distance && Math.abs(nearestY) < distance;
    }

    private static int nearestToZero(int min, int max) {
        return min > 0 ? min : Math.min(max, 0);
    }

    record Result(SortedRenderLists lists, Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuilds, boolean complete) {
    }

    private record Candidate(RenderSection section, float distance) {
    }
}
