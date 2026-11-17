/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer camera of MyGameRenderer as a 1.20.1 Camera whose view rotation may roll or reflect.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 1.20.1 cameras orient billboards with {@link #rotation()} mapping +Z to the look direction, the opposite of the 26.x
 * convention, so the inverse view rotation is turned half a revolution about Y.
 */
final class PortalCamera extends Camera {
    private static final float DEGREES = (float) (180.0D / Math.PI);

    private final Matrix4f view = new Matrix4f();
    private final Quaternionf orientation = new Quaternionf();
    private final Vector3f forwards = new Vector3f(0.0F, 0.0F, -1.0F);
    private final BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
    private Vec3 position = Vec3.ZERO;
    private ClientLevel level;
    private Entity entity;
    private boolean detached;
    private float pitch;
    private float yaw;

    void place(ClientLevel level, Entity entity, Vec3 position, Matrix4fc view, boolean detached) {
        this.level = level;
        this.entity = entity;
        this.position = position;
        this.view.set(view);
        this.detached = detached;
        block.set(position.x, position.y, position.z);
        Matrix4f inverse = new Matrix4f(view).transpose3x3();
        inverse.transformDirection(0.0F, 0.0F, -1.0F, forwards).normalize();
        orientation.set(PortalLayerMath.orientation(view)).rotateY((float) Math.PI);
        pitch = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, -forwards.y))) * DEGREES;
        yaw = (float) Math.atan2(-forwards.x, forwards.z) * DEGREES;
        setPosition(position);
        setRotation(yaw, pitch);
    }

    Matrix4f view() {
        return view;
    }

    @Override
    public Vec3 getPosition() {
        return position;
    }

    @Override
    public BlockPos getBlockPosition() {
        return block;
    }

    @Override
    public float getXRot() {
        return pitch;
    }

    @Override
    public float getYRot() {
        return yaw;
    }

    @Override
    public Quaternionf rotation() {
        return orientation;
    }

    @Override
    public Entity getEntity() {
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
    public FogType getFluidInCamera() {
        FluidState fluid = level.getFluidState(block);
        if (fluid.is(FluidTags.WATER) && position.y < block.getY() + fluid.getHeight(level, block)) {
            return FogType.WATER;
        }
        if (fluid.is(FluidTags.LAVA) && position.y <= block.getY() + fluid.getHeight(level, block)) {
            return FogType.LAVA;
        }
        return level.getBlockState(block).is(Blocks.POWDER_SNOW) ? FogType.POWDER_SNOW : FogType.NONE;
    }
}
