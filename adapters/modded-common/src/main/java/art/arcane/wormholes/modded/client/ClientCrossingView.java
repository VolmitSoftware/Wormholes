/*
 * View-bobbing damping and the cross-portal camera view are derived from Immersive Portals' RenderStates.updateViewBobbingFactor
 * and CrossPortalViewRendering (https://github.com/iPortalTeam/ImmersivePortalsMod), Copyright 2020 qouteall,
 * licensed under the Apache License, Version 2.0. Modified for Wormholes.
 */
package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Box;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.CameraPoseAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldCameraAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Collection;

public final class ClientCrossingView {
    private static final double BOB_FULL_DISTANCE = 2.0D;
    private static final double BOB_NONE_DISTANCE = 1.0D;
    private static final double BOB_RECOVERY = 0.1D;
    private static final double DETACHED_CLEARANCE = 0.1D;

    private final ResidentLevels residents;
    private double bobFactor = 1.0D;
    private View applied;

    ClientCrossingView(ResidentLevels residents) {
        this.residents = residents;
    }

    public double bobFactor() {
        return bobFactor;
    }

    public boolean active() {
        return applied != null;
    }

    public void update(Camera camera, DeltaTracker tracker, Collection<TravelMessage.TravelBegin> arms) {
        applied = null;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || !camera.isInitialized() || camera.entity() != player) {
            bobFactor = 1.0D;
            return;
        }
        Vec3 eye = player.getEyePosition(camera.getCameraEntityPartialTicks(tracker));
        Vec3 position = camera.position();
        String dimension = minecraft.level.dimension().identifier().toString();
        double nearest = Double.POSITIVE_INFINITY;
        double throughDistance = Double.POSITIVE_INFINITY;
        TravelMessage.TravelBegin through = null;
        for (TravelMessage.TravelBegin arm : arms) {
            if (!arm.sourceWorld().equals(dimension)) {
                continue;
            }
            ApertureDescriptor geometry = arm.sourceGeometry();
            nearest = Math.min(nearest, distance(geometry, eye));
            double eyeDistance = Math.abs(geometry.signedDistance(eye.x, eye.y, eye.z));
            if (eyeDistance < throughDistance && ClientSeamlessTravel.crossed(geometry, eye, position)) {
                through = arm;
                throughDistance = eyeDistance;
            }
        }
        bobFactor = dampedBob(bobFactor, nearest);
        if (through != null) {
            enter(minecraft, camera, player, through, eye);
        }
    }

    public void within(Runnable action) {
        View current = applied;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel source = minecraft.level;
        if (current == null || source == null || current.level() == source) {
            action.run();
            return;
        }
        ClientLevel destination = current.level();
        ClientWorldLoader.WorldRenderer world = ClientWorldLoader.worldRenderer(destination);
        GameRenderer gameRenderer = minecraft.gameRenderer;
        Camera camera = gameRenderer.mainCamera();
        LevelRenderState detached = world.state();
        EnvironmentAttributeProbe probe = camera.attributeProbe();
        world.attach(gameRenderer.mainRenderTarget());
        bind(world, gameRenderer.gameRenderState().levelRenderState);
        ((ClientWorldCameraAccess) camera).wormholes$attributeProbe(ClientWorldLoader.probe(destination));
        gameRenderer.setLevel(destination);
        lightmapChanged(gameRenderer);
        minecraft.level = destination;
        try {
            ClientWorldLoader.withWorldRenderer(destination, action);
        } finally {
            minecraft.level = source;
            gameRenderer.setLevel(source);
            ((ClientWorldCameraAccess) camera).wormholes$attributeProbe(probe);
            bind(world, detached);
            lightmapChanged(gameRenderer);
        }
        world.rendered();
        ClientWorldLoader.renderedThroughPortal(destination, camera.position());
    }

    public void finish(Camera camera) {
        View current = applied;
        if (current == null) {
            return;
        }
        applied = null;
        CameraPoseAccess access = (CameraPoseAccess) camera;
        access.wormholes$rotation(current.yaw(), current.pitch());
        access.wormholes$position(current.position());
    }

    public void clear() {
        applied = null;
        bobFactor = 1.0D;
    }

    static double dampedBob(double current, double portalDistance) {
        double target = portalDistance >= BOB_FULL_DISTANCE ? 1.0D
            : portalDistance < BOB_NONE_DISTANCE ? 0.0D : portalDistance - BOB_NONE_DISTANCE;
        return target < current ? target : current + (target - current) * BOB_RECOVERY;
    }

    static double distance(ApertureDescriptor geometry, Vec3 point) {
        Box area = geometry.apertureArea();
        double plane = geometry.planeCoordinate();
        double x = Math.clamp(point.x, area.getXa(), area.getXb());
        double y = Math.clamp(point.y, area.getYa(), area.getYb());
        double z = Math.clamp(point.z, area.getZa(), area.getZb());
        switch (geometry.facingDirection().getAxis()) {
            case X -> x = plane;
            case Y -> y = plane;
            case Z -> z = plane;
        }
        return point.distanceTo(new Vec3(x, y, z));
    }

    private void enter(Minecraft minecraft, Camera camera, LocalPlayer player, TravelMessage.TravelBegin arm, Vec3 eye) {
        ClientLevel level = arm.resident() ? residents.level(arm.levelHandle()) : minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 position = camera.position();
        Similarity toward = arm.sourceToDestination();
        Vec3 mapped = camera.isDetached() ? detachedPosition(level, player, arm, eye, position) : ClientTravelMotion.point(toward, position);
        Angles.Look look = ClientTravelMotion.look(toward.rigid(), camera.yRot(), camera.xRot());
        applied = new View(level, position, camera.yRot(), camera.xRot());
        CameraPoseAccess access = (CameraPoseAccess) camera;
        access.wormholes$position(mapped);
        access.wormholes$rotation(look.yaw(), Math.clamp(look.pitch(), -90.0F, 90.0F));
        access.wormholes$cullFrustum(camera.getViewRotationMatrix(new Matrix4f()), access.wormholes$cullingProjection(), mapped);
    }

    private static Vec3 detachedPosition(ClientLevel level, LocalPlayer player, TravelMessage.TravelBegin arm, Vec3 eye, Vec3 position) {
        ApertureDescriptor geometry = arm.sourceGeometry();
        Similarity toward = arm.sourceToDestination();
        double before = geometry.signedDistance(eye.x, eye.y, eye.z);
        double after = geometry.signedDistance(position.x, position.y, position.z);
        Vec3 start = ClientTravelMotion.point(toward, eye.lerp(position, before / (before - after)));
        double distance = player.getAttributeValue(Attributes.CAMERA_DISTANCE) * player.getScale();
        Vec3 end = ClientTravelMotion.point(toward, eye.add(position.subtract(eye).normalize().scale(distance)));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return end;
        }
        Vec3 reach = hit.getLocation().subtract(start);
        double length = reach.length() - DETACHED_CLEARANCE;
        return length <= 0.0D ? start : start.add(reach.normalize().scale(length));
    }

    private static void bind(ClientWorldLoader.WorldRenderer world, LevelRenderState state) {
        ((ClientWorldLevelRendererAccess) world.renderer()).wormholes$levelRenderState(state);
        ((ClientWorldExtractorAccess) world.extractor()).wormholes$levelRenderState(state);
    }

    private static void lightmapChanged(GameRenderer gameRenderer) {
        ((ClientWorldLightmapAccess) ((ClientWorldGameRendererAccess) gameRenderer).wormholes$lightmapExtractor()).wormholes$needsUpdate(true);
    }

    private record View(ClientLevel level, Vec3 position, float yaw, float pitch) {
    }
}
