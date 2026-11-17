package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.AvatarDataAccessor;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSweep;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.entity.EntityProjection;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class ClientReflectionEntity {
    private static final long UUID_PREFIX = 0x57484d4952524f52L;
    private static final double BODY_SAMPLE_LIFT = 0.5D;
    private static final String UPSIDE_DOWN_NAME = "Dinnerbone";
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

    private final Int2ObjectOpenHashMap<Reflection> reflections;
    private final IntOpenHashSet seen;
    private final IntOpenHashSet meshIds = new IntOpenHashSet();
    private final IntArrayList keys;
    private final double[] point;

    public ClientReflectionEntity() {
        this.reflections = new Int2ObjectOpenHashMap<>(2);
        this.seen = new IntOpenHashSet(2);
        this.keys = new IntArrayList(2);
        this.point = new double[3];
    }

    public int size() {
        return reflections.size();
    }

    public boolean hasMeshEntities() {
        return !meshIds.isEmpty();
    }

    public boolean meshEntity(int entityId) {
        return meshIds.contains(entityId);
    }

    public Entity entity(int portalKey) {
        Reflection reflection = reflections.get(portalKey);
        return reflection == null ? null : reflection.entity;
    }

    public void tick(ClientLevel level, LocalPlayer player, ClientPacketListener connection, ClientViewSession session, ClientViewTick tick,
                     boolean enabled) {
        if (!enabled || level == null || player == null || connection == null || !tick.attached() || player.isSpectator()) {
            clear();
            return;
        }
        seen.clear();
        meshIds.clear();
        keys.clear();
        tick.mirrorKeys(keys);
        for (ClientPortal portal : session.portals().values()) {
            if (portal.geometry().mirror() && session.meshes().view(portal.portalKey()) != null && !keys.contains(portal.portalKey())) {
                keys.add(portal.portalKey());
            }
        }
        for (int index = 0; index < keys.size(); index++) {
            int portalKey = keys.getInt(index);
            ClientPortal portal = session.portal(portalKey);
            ClientMirrorBuilder mirror = tick.mirror(portalKey);
            boolean mesh = session.meshes().view(portalKey) != null;
            if (portal == null || !mesh && (mirror == null || !portal.ready() || !visible(portal, mirror.transform(), player))) {
                continue;
            }
            OpticTransform space = mesh ? PortalSurface.mirrorTransform(portal.geometry()) : mirror.transform();
            Reflection reflection = reflections.get(portalKey);
            if (reflection == null || reflection.entity.isRemoved() || reflection.entity.level() != level) {
                if (reflection != null) {
                    remove(reflection);
                }
                reflections.remove(portalKey);
                reflection = spawn(level, player, connection, space);
                if (reflection == null) {
                    continue;
                }
                reflections.put(portalKey, reflection);
            }
            seen.add(portalKey);
            if (mesh) {
                meshIds.add(reflection.entity.getId());
            }
            follow(reflection, player, mesh ? OpticTransform.IDENTITY : space, !mesh && upsideDown(portal.geometry()), mesh);
        }
        ObjectIterator<Int2ObjectMap.Entry<Reflection>> iterator = reflections.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Reflection> entry = iterator.next();
            if (!seen.contains(entry.getIntKey())) {
                remove(entry.getValue());
                iterator.remove();
            }
        }
    }

    public void clear() {
        meshIds.clear();
        ObjectIterator<Reflection> iterator = reflections.values().iterator();
        while (iterator.hasNext()) {
            remove(iterator.next());
        }
        reflections.clear();
    }

    private boolean visible(ClientPortal portal, OpticTransform space, LocalPlayer player) {
        ClientSweep sweep = portal.sweep();
        if (sweep.eyeFrontSide() != portal.geometry().frontSide() || sweep.appliedCount() == 0) {
            return false;
        }
        Face up = MinecraftGravity.frame(player).up();
        double height = player.getEyeHeight();
        return sampleApplied(sweep, space, player.getX() + up.x() * BODY_SAMPLE_LIFT, player.getY() + up.y() * BODY_SAMPLE_LIFT,
            player.getZ() + up.z() * BODY_SAMPLE_LIFT)
            || sampleApplied(sweep, space, player.getX() + up.x() * height, player.getY() + up.y() * height, player.getZ() + up.z() * height);
    }

    private boolean sampleApplied(ClientSweep sweep, OpticTransform space, double x, double y, double z) {
        space.pointInto(x, y, z, point);
        return sweep.applied((int) Math.floor(point[0]), (int) Math.floor(point[1]), (int) Math.floor(point[2]));
    }

    private Reflection spawn(ClientLevel level, LocalPlayer player, ClientPacketListener connection, OpticTransform space) {
        int id = ClientEntityIds.freeReflection(this::taken);
        if (id == ClientEntityIds.NONE) {
            return null;
        }
        EntityProjection.feetInto(space, player.getX(), player.getY(), player.getZ(), player.getBbHeight(), point);
        float yaw = mirroredYaw(space, player.getYRot(), player.getXRot());
        float pitch = mirroredPitch(space, player.getYRot(), player.getXRot());
        float headYaw = mirroredYaw(space, player.getYHeadRot(), 0.0F);
        GameProfile profile = new GameProfile(new UUID(UUID_PREFIX, id), player.getGameProfile().getName());
        profile.getProperties().putAll(player.getGameProfile().getProperties());
        ReflectionPlayer mirror = new ReflectionPlayer(level, profile, connection.getPlayerInfo(player.getUUID()));
        mirror.setId(id);
        mirror.moveTo(point[0], point[1], point[2], yaw, pitch);
        mirror.setYHeadRot(headYaw);
        mirror.setYBodyRot(yaw);
        mirror.noPhysics = true;
        mirror.setSilent(true);
        level.addPlayer(id, mirror);
        if (level.getEntity(id) != mirror) {
            return null;
        }
        return new Reflection(mirror);
    }

    private void follow(Reflection reflection, LocalPlayer player, OpticTransform space, boolean upsideDown, boolean nativeMesh) {
        ReflectionPlayer entity = reflection.entity;
        if (nativeMesh) {
            entity.tick();
            followNativePose(entity, player);
        } else {
            float height = player.getBbHeight();
            EntityProjection.feetInto(space, player.xOld, player.yOld, player.zOld, height, point);
            Vec3 previous = new Vec3(point[0], point[1], point[2]);
            setOldPosAndRot(entity, previous, mirroredYaw(space, player.yRotO, player.xRotO), mirroredPitch(space, player.yRotO, player.xRotO));
            EntityProjection.feetInto(space, player.getX(), player.getY(), player.getZ(), height, point);
            entity.setPos(point[0], point[1], point[2]);
            entity.setYRot(mirroredYaw(space, player.getYRot(), player.getXRot()));
            entity.setXRot(mirroredPitch(space, player.getYRot(), player.getXRot()));
            entity.yHeadRotO = mirroredYaw(space, player.yHeadRotO, 0.0F);
            entity.yHeadRot = mirroredYaw(space, player.yHeadRot, 0.0F);
            entity.yBodyRotO = mirroredYaw(space, player.yBodyRotO, 0.0F);
            entity.yBodyRot = mirroredYaw(space, player.yBodyRot, 0.0F);
        }
        entity.noPhysics = true;
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setPose(player.getPose());
        entity.setShiftKeyDown(player.isShiftKeyDown());
        entity.setInvisible(player.isInvisible());
        if (!nativeMesh) {
            entity.setMainArm(player.getMainArm().getOpposite());
        }
        byte parts = player.getEntityData().get(AvatarDataAccessor.wormholesModelParts());
        if (upsideDown) {
            parts = (byte) (parts | PlayerModelPart.CAPE.getMask());
        }
        entity.getEntityData().set(AvatarDataAccessor.wormholesModelParts(), parts);
        if (upsideDown != reflection.upsideDown) {
            reflection.upsideDown = upsideDown;
            entity.upsideDown = upsideDown;
        }
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = player.getItemBySlot(slot);
            if (!ItemStack.matches(entity.getItemBySlot(slot), worn)) {
                entity.setItemSlot(slot, worn.copy());
            }
        }
        followAnimation(entity, player, ((LivingEntityUseState) entity).wormholesLivingFlags());
        copyWalk((WalkAnimationView) player.walkAnimation, (WalkAnimationView) entity.walkAnimation);
        if (player.swinging && (!reflection.swinging || player.swingTime < reflection.swingTime)) {
            entity.swing(player.swingingArm == null ? InteractionHand.MAIN_HAND : player.swingingArm, false);
        }
        reflection.swinging = player.swinging;
        reflection.swingTime = player.swingTime;
    }

    static void followAnimation(LivingEntity entity, LocalPlayer player, EntityDataAccessor<Byte> flags) {
        entity.getEntityData().set(flags, player.getEntityData().get(flags));
        LivingEntityUseState use = (LivingEntityUseState) entity;
        use.wormholesUseItem(player.isUsingItem() ? entity.getItemInHand(player.getUsedItemHand()) : ItemStack.EMPTY);
        use.wormholesUseItemRemaining(player.getUseItemRemainingTicks());
        entity.hurtTime = player.hurtTime;
        entity.hurtDuration = player.hurtDuration;
        entity.deathTime = player.deathTime;
    }

    static void followNativePose(Player entity, LocalPlayer player) {
        MinecraftGravity.set(entity, MinecraftGravity.frame(player));
        setOldPosAndRot(entity, new Vec3(player.xOld, player.yOld, player.zOld), player.yRotO, player.xRotO);
        entity.setPos(player.getX(), player.getY(), player.getZ());
        entity.setYRot(player.getYRot());
        entity.setXRot(player.getXRot());
        entity.yHeadRotO = player.yHeadRotO;
        entity.yHeadRot = player.yHeadRot;
        entity.yBodyRotO = player.yBodyRotO;
        entity.yBodyRot = player.yBodyRot;
        entity.setMainArm(player.getMainArm());
    }

    private static void copyWalk(WalkAnimationView source, WalkAnimationView target) {
        target.wormholesSpeedOld(source.wormholesSpeedOld());
        target.wormholesSpeed(source.wormholesSpeed());
        target.wormholesPosition(source.wormholesPosition());
    }

    private static void setOldPosAndRot(Entity entity, Vec3 position, float yaw, float pitch) {
        entity.xo = position.x;
        entity.yo = position.y;
        entity.zo = position.z;
        entity.xOld = position.x;
        entity.yOld = position.y;
        entity.zOld = position.z;
        entity.yRotO = yaw;
        entity.xRotO = pitch;
    }

    private boolean taken(int id) {
        for (Reflection reflection : reflections.values()) {
            if (reflection.entity.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private static float mirroredYaw(OpticTransform space, float yaw, float pitch) {
        return space.look(new Look(yaw, pitch)).yaw();
    }

    private static float mirroredPitch(OpticTransform space, float yaw, float pitch) {
        return space.look(new Look(yaw, pitch)).pitch();
    }

    private static boolean upsideDown(ApertureDescriptor mirror) {
        return PortalSurface.mirrorTransform(mirror).flipsWorldUp();
    }

    private static void remove(Reflection reflection) {
        ReflectionPlayer entity = reflection.entity;
        if (entity.level() instanceof ClientLevel owner && owner.getEntity(entity.getId()) == entity) {
            owner.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private static final class Reflection {
        private final ReflectionPlayer entity;
        private boolean swinging;
        private int swingTime;
        private boolean upsideDown;

        private Reflection(ReflectionPlayer entity) {
            this.entity = entity;
        }
    }

    private static class ReflectionPlayer extends RemotePlayer {
        private static final Component UPSIDE_DOWN = Component.literal(UPSIDE_DOWN_NAME);

        private final PlayerInfo info;
        private boolean upsideDown;

        private ReflectionPlayer(ClientLevel level, GameProfile profile, PlayerInfo info) {
            super(level, profile);
            this.info = info;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            return info;
        }

        @Override
        public Component getName() {
            return upsideDown ? UPSIDE_DOWN : super.getName();
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        @Override
        protected void pushEntities() {
        }

        @Override
        public boolean isInvisibleTo(Player viewer) {
            return !isInvisible() || super.isInvisibleTo(viewer);
        }
    }
}
