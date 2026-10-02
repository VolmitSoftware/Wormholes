package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.AvatarDataAccessor;
import art.arcane.wormholes.modded.mixin.client.ReflectionDataAccessor;
import art.arcane.wormholes.render.EntityVisualProjection;
import art.arcane.wormholes.render.PortalCoordMap;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientSpace;
import art.arcane.wormholes.render.client.ClientViewSweep;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
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
    private final double[] direction;

    public ClientReflectionEntity() {
        this.reflections = new Int2ObjectOpenHashMap<>(2);
        this.seen = new IntOpenHashSet(2);
        this.keys = new IntArrayList(2);
        this.point = new double[3];
        this.direction = new double[3];
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
            clear(level, connection);
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
            if (portal == null || !mesh && (mirror == null || !portal.ready() || !visible(portal, mirror.space(), player))) {
                continue;
            }
            ClientSpace space = mesh ? ClientSpace.mirror(portal.geometry()) : mirror.space();
            Reflection reflection = reflections.get(portalKey);
            if (reflection == null || reflection.entity.isRemoved() || reflection.entity.level() != level) {
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
            follow(reflection, player, mesh ? ClientSpace.IDENTITY : space, !mesh && upsideDown(portal.geometry()), mesh);
        }
        ObjectIterator<Int2ObjectMap.Entry<Reflection>> iterator = reflections.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Reflection> entry = iterator.next();
            if (!seen.contains(entry.getIntKey())) {
                remove(level, connection, entry.getValue());
                iterator.remove();
            }
        }
    }

    public void clear(ClientLevel level, ClientPacketListener connection) {
        meshIds.clear();
        ObjectIterator<Reflection> iterator = reflections.values().iterator();
        while (iterator.hasNext()) {
            remove(level, connection, iterator.next());
        }
        reflections.clear();
    }

    private boolean visible(ClientPortal portal, ClientSpace space, LocalPlayer player) {
        ClientViewSweep sweep = portal.sweep();
        if (sweep.eyeFrontSide() != portal.geometry().frontSide() || sweep.appliedCount() == 0) {
            return false;
        }
        return sampleApplied(sweep, space, player.getX(), player.getY() + BODY_SAMPLE_LIFT, player.getZ())
            || sampleApplied(sweep, space, player.getX(), player.getEyeY(), player.getZ());
    }

    private boolean sampleApplied(ClientViewSweep sweep, ClientSpace space, double x, double y, double z) {
        space.toDisplay(x, y, z, point);
        return sweep.applied((int) Math.floor(point[0]), (int) Math.floor(point[1]), (int) Math.floor(point[2]));
    }

    private Reflection spawn(ClientLevel level, LocalPlayer player, ClientPacketListener connection, ClientSpace space) {
        int id = ClientEntityIds.freeReflection(this::taken);
        if (id == ClientEntityIds.NONE) {
            return null;
        }
        space.entityToDisplay(player.getX(), player.getY(), player.getZ(), player.getBbHeight(), point);
        float yaw = mirroredYaw(space, player.getYRot(), player.getXRot());
        float pitch = mirroredPitch(space, player.getYRot(), player.getXRot());
        float headYaw = mirroredYaw(space, player.getYHeadRot(), 0.0F);
        new ClientboundAddEntityPacket(id, new UUID(UUID_PREFIX, id), point[0], point[1], point[2], pitch, yaw, EntityTypes.MANNEQUIN, 0,
            Vec3.ZERO, headYaw).handle(connection);
        if (!(level.getEntity(id) instanceof Mannequin mannequin)) {
            return null;
        }
        List<SynchedEntityData.DataValue<?>> values = List.of(
            SynchedEntityData.DataValue.create(ReflectionDataAccessor.wormholesProfile(), ResolvableProfile.createResolved(player.getGameProfile())),
            SynchedEntityData.DataValue.create(ReflectionDataAccessor.wormholesDescription(), Optional.<Component>empty()));
        new ClientboundSetEntityDataPacket(id, values).handle(connection);
        mannequin.noPhysics = true;
        mannequin.setSilent(true);
        return new Reflection(mannequin);
    }

    private void follow(Reflection reflection, LocalPlayer player, ClientSpace space, boolean upsideDown, boolean nativeMesh) {
        Mannequin entity = reflection.entity;
        if (nativeMesh) {
            entity.commonTick();
            entity.tick();
            followNativePose(entity, player);
        } else {
            float height = player.getBbHeight();
            space.entityToDisplay(player.xOld, player.yOld, player.zOld, height, point);
            Vec3 previous = new Vec3(point[0], point[1], point[2]);
            entity.setOldPosAndRot(previous, mirroredYaw(space, player.yRotO, player.xRotO), mirroredPitch(space, player.yRotO, player.xRotO));
            space.entityToDisplay(player.getX(), player.getY(), player.getZ(), height, point);
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
            entity.setCustomName(upsideDown ? Component.literal(UPSIDE_DOWN_NAME) : null);
            entity.setCustomNameVisible(false);
        }
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = player.getItemBySlot(slot);
            if (!ItemStack.matches(entity.getItemBySlot(slot), worn)) {
                entity.setItemSlot(slot, worn.copy());
            }
        }
        followAnimation(entity, player, ((LivingEntityUseState) entity).wormholesLivingFlags());
        copyWalk((WalkAnimationView) player.walkAnimation, (WalkAnimationView) entity.walkAnimation);
        LivingEntity.SwingDescription swing = player.getCurrentSwing();
        if (swing != null && swing != reflection.swing) {
            entity.swing(swing.hand(), swing.animation(), false);
        }
        reflection.swing = swing;
    }

    static void followAnimation(Mannequin entity, LocalPlayer player, EntityDataAccessor<Byte> flags) {
        entity.getEntityData().set(flags, player.getEntityData().get(flags));
        LivingEntityUseState use = (LivingEntityUseState) entity;
        use.wormholesUseItem(player.isUsingItem() ? entity.getItemInHand(player.getUsedItemHand()) : ItemStack.EMPTY);
        use.wormholesUseItemRemaining(player.getUseItemRemainingTicks());
        entity.hurtTime = player.hurtTime;
        entity.hurtDuration = player.hurtDuration;
        entity.deathTime = player.deathTime;
    }

    static void followNativePose(Mannequin entity, LocalPlayer player) {
        entity.setOldPosAndRot(new Vec3(player.xOld, player.yOld, player.zOld), player.yRotO, player.xRotO);
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
        target.wormholesPositionScale(source.wormholesPositionScale());
    }

    private boolean taken(int id) {
        for (Reflection reflection : reflections.values()) {
            if (reflection.entity.getId() == id) {
                return true;
            }
        }
        return false;
    }

    private float mirroredYaw(ClientSpace space, float yaw, float pitch) {
        EntityVisualProjection.lookDirectionInto(yaw, pitch, direction);
        space.vectorToDisplay(direction[0], direction[1], direction[2], direction);
        return EntityVisualProjection.yaw(direction[0], direction[2]);
    }

    private float mirroredPitch(ClientSpace space, float yaw, float pitch) {
        EntityVisualProjection.lookDirectionInto(yaw, pitch, direction);
        space.vectorToDisplay(direction[0], direction[1], direction[2], direction);
        return EntityVisualProjection.pitch(direction[0], direction[1], direction[2]);
    }

    private static boolean upsideDown(ClientPortalGeometry mirror) {
        return PortalCoordMap.mirrorTransformFlipsWorldUp(mirror.frame(), mirror.mirrorQuarterTurns());
    }

    private static void remove(ClientLevel level, ClientPacketListener connection, Reflection reflection) {
        if (connection != null && level != null && reflection.entity.level() == level && !reflection.entity.isRemoved()) {
            new ClientboundRemoveEntitiesPacket(reflection.entity.getId()).handle(connection);
        }
    }

    private static final class Reflection {
        private final Mannequin entity;
        private LivingEntity.SwingDescription swing;
        private boolean upsideDown;

        private Reflection(Mannequin entity) {
            this.entity = entity;
        }
    }
}
