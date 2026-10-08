/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer camera of MyGameRenderer as a 26.x Camera whose view rotation may roll or reflect.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

final class PortalCamera extends Camera {
    private static final float DEGREES = (float) (180.0D / Math.PI);

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Quaternionf orientation = new Quaternionf();
    private final Vector3f forwards = new Vector3f(0.0F, 0.0F, -1.0F);
    private final Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
    private final Vector3f left = new Vector3f(-1.0F, 0.0F, 0.0F);
    private final BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
    private Vec3 position = Vec3.ZERO;
    private ClientLevel level;
    private Entity entity;
    private Frustum frustum;
    private EnvironmentAttributeProbe probe;
    private boolean detached;
    private float pitch;
    private float yaw;

    void place(ClientLevel level, Entity entity, Vec3 position, Matrix4fc view, Matrix4fc projection, Frustum frustum,
               EnvironmentAttributeProbe probe, boolean detached) {
        this.level = level;
        this.entity = entity;
        this.position = position;
        this.view.set(view);
        this.projection.set(projection);
        this.frustum = frustum;
        this.probe = probe;
        this.detached = detached;
        block.set(position.x, position.y, position.z);
        Matrix4f inverse = new Matrix4f(view).transpose3x3();
        inverse.transformDirection(0.0F, 0.0F, -1.0F, forwards);
        inverse.transformDirection(0.0F, 1.0F, 0.0F, up);
        inverse.transformDirection(-1.0F, 0.0F, 0.0F, left);
        orientation.set(PortalLayerMath.orientation(view));
        pitch = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -forwards.y))) * DEGREES;
        yaw = (float) Math.atan2(-forwards.x, forwards.z) * DEGREES;
        setLevel(level);
        setEntity(entity);
        setPosition(position);
        setRotation(yaw, pitch);
    }

    @Override
    public Vec3 position() {
        return position;
    }

    @Override
    public BlockPos blockPosition() {
        return block;
    }

    @Override
    public float xRot() {
        return pitch;
    }

    @Override
    public float yRot() {
        return yaw;
    }

    @Override
    public Quaternionf rotation() {
        return orientation;
    }

    @Override
    public Matrix4f getViewRotationMatrix(Matrix4f dest) {
        return dest.set(view);
    }

    @Override
    public Matrix4f getViewRotationProjectionMatrix(Matrix4f dest) {
        return dest.set(projection).mul(view);
    }

    @Override
    public Frustum getCullFrustum() {
        return frustum;
    }

    @Override
    public Frustum getCapturedFrustum() {
        return frustum;
    }

    @Override
    public Entity entity() {
        return entity;
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isDetached() {
        return detached;
    }

    @Override
    public EnvironmentAttributeProbe attributeProbe() {
        return probe;
    }

    @Override
    public Vector3fc forwardVector() {
        return forwards;
    }

    @Override
    public Vector3fc panoramicForwards() {
        return forwards;
    }

    @Override
    public Vector3fc upVector() {
        return up;
    }

    @Override
    public Vector3fc leftVector() {
        return left;
    }

    @Override
    public FogType getFluidInCamera() {
        FluidState fluid = level.getFluidState(block);
        if (fluid.is(FluidTags.WATER) && position.y < block.getY() + fluid.getHeightForCamera(level, block)) {
            return FogType.WATER;
        }
        if (fluid.is(FluidTags.LAVA) && position.y <= block.getY() + fluid.getHeightForCamera(level, block)) {
            return FogType.LAVA;
        }
        return level.getBlockState(block).is(Blocks.POWDER_SNOW) ? FogType.POWDER_SNOW : FogType.NONE;
    }
}
