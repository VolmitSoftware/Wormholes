package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.entity.EntityAnimation;
import art.arcane.optics.occlusion.EntityOcclusion;
import art.arcane.optics.occlusion.ViewOcclusion;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.wormholes.render.ProjectedEntityIdentity;
import io.netty.channel.embedded.EmbeddedChannel;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MinecraftEntityProjectionGameTest {
    private final Options options;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final Vec3 previousPosition;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private MinecraftProjectedEntities renderer;
    private MinecraftProjectedEntities.View view;
    private ArmorStand remote;
    private ArmorStand local;
    private int fakeId;

    private MinecraftEntityProjectionGameTest(Options options) {
        this.options = options;
        this.previousPosition = options.player().position();
    }

    public static CompletableFuture<Boolean> standalone(GameTestHelper helper, WormholesModRuntime runtime) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "projection-test"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
        player.initInventoryMenu();
        level.addNewPlayer(player);
        return run(new Options(helper, runtime, player, channel)).whenComplete((success, failure) -> {
            player.discard();
            channel.finishAndReleaseAll();
        });
    }

    public static CompletableFuture<Boolean> run(Options options) {
        MinecraftEntityProjectionGameTest test = new MinecraftEntityProjectionGameTest(options);
        try {
            test.begin();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result.thenCompose(success -> MinecraftFidelityGameTest.run(options.helper(), options.runtime()));
    }

    private void begin() {
        GameTestHelper helper = options.helper();
        WormholesModRuntime runtime = options.runtime();
        ServerPlayer player = options.player();
        ServerLevel level = player.level();
        source = runtime.portals().create(player.getUUID(), level, cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), level, cells(18), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Entity projection portal link failed");
        Vec3d origin = source.getOrigin();
        Vec3d target = destination.getOrigin();
        Vec3d normal = source.getFrame().getNormal().toVector();
        Vec3d eye = origin.add(normal.multiply(3.0D));
        player.setPos(eye.x(), eye.y() - player.getEyeHeight(), eye.z());
        Vec3 display = new Vec3(origin.x() - normal.x() * 3.0D, origin.y() - 0.5D, origin.z() - normal.z() * 3.0D);
        double[] transformed = new double[3];
        OpticTransform.between(source.getFrame(), origin, destination.getFrame(), target).pointInto(display.x, display.y, display.z, transformed);
        remote = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        local = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(remote != null && local != null, "Entity projection fixtures were not created");
        remote.setPos(transformed[0], transformed[1], transformed[2]);
        remote.setNoGravity(true);
        local.setPos(display);
        local.setNoGravity(true);
        level.addFreshEntity(remote);
        level.addFreshEntity(local);
        MinecraftProjectorPortalAccess portals = new MinecraftProjectorPortalAccess(runtime);
        renderer = new MinecraftProjectedEntities(runtime, new MinecraftProjectedEntities.Context(player, source, portals.createRecursiveIndex()));
        ViewVolume frustum = new ViewVolume(eye, source.getGeometry(), new ViewVolume.Options(16, 16, 0.1D, 1.0D, 0.0D));
        EntityOcclusion<BlockState, ContentView<BlockState, BlockState>> occlusion = new EntityOcclusion<>(
            new ViewOcclusion<>(MinecraftProjectorBlocks.INSTANCE, EntityOcclusion.MAX_VOXEL_STEPS_PER_BATCH));
        view = new MinecraftProjectedEntities.View(destination, destination, level, runtime.projections().scene(level, destination, 16),
            OpticTransform.between(destination.getFrame(), target, source.getFrame(), origin), frustum, eye, occlusion, 16);
        clearPackets();
        renderer.apply(view);
        options.channel().runPendingTasks();
        for (Object packet : options.channel().outboundMessages()) {
            if (packet instanceof ClientboundAddEntityPacket spawn && spawn.getType() == EntityTypes.ARMOR_STAND && ProjectedEntityIdentity.isEntityId(spawn.getId())) {
                fakeId = spawn.getId();
                helper.assertTrue(Math.abs(spawn.getX() - display.x) < 0.01D && Math.abs(spawn.getZ() - display.z) < 0.01D,
                    "Projected entity spawn did not use portal coordinates");
            }
        }
        helper.assertTrue(ProjectedEntityIdentity.isEntityId(fakeId), "Native renderer sent no projected entity spawn");
        helper.assertTrue(options.channel().outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundSetEntityDataPacket),
            "Native renderer sent no captured entity metadata");
        helper.assertTrue(runtime.projections().isEntityHidden(player.getUUID(), local.getUUID()), "Local entity behind aperture was not hidden");
        helper.runAfterDelay(2, this::move);
    }

    private void move() {
        try {
            remote.setPos(remote.position().add(0.25D, 0, 0));
            options.runtime().projections().scene(options.player().level(), destination, 16);
            clearPackets();
            renderer.apply(view);
            options.channel().runPendingTasks();
            options.helper().assertTrue(options.channel().outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundMoveEntityPacket),
                "Projected entity movement did not reach the observer");
            clearPackets();
            renderer.event(EntityAnimation.animation(remote.getUUID(), MinecraftEntityPackets.ANIMATION_SWING_MAIN_HAND));
            renderer.event(EntityAnimation.hurt(remote.getUUID(), 27.0F));
            options.channel().runPendingTasks();
            options.helper().assertTrue(options.channel().outboundMessages().stream().anyMatch(packet ->
                packet instanceof ClientboundSwingAnimationPacket swing && swing.entityId() == fakeId && swing.hand() == InteractionHand.MAIN_HAND),
                "Projected entity swing did not reach the fake id");
            options.helper().assertTrue(options.channel().outboundMessages().stream().anyMatch(packet ->
                packet instanceof ClientboundHurtAnimationPacket hurt && hurt.id() == fakeId && hurt.yaw() == 27.0F),
                "Projected entity hurt did not reach the fake id");
            clearPackets();
            renderer.close();
            renderer = null;
            options.channel().runPendingTasks();
            boolean removed = false;
            for (Object packet : options.channel().outboundMessages()) {
                if (packet instanceof ClientboundRemoveEntitiesPacket destroy && destroy.entityIds().contains(fakeId)) {
                    removed = true;
                }
            }
            options.helper().assertTrue(removed, "Projected entity teardown did not remove the fake id");
            options.helper().assertTrue(!options.runtime().projections().isEntityHidden(options.player().getUUID(), local.getUUID()),
                "Local entity remained hidden after projection teardown");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS entity_projection spawn metadata motion animation hurt local_occlusion teardown");
            finish(null);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private List<BlockPos> cells(int x) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = 0; dx < 4; dx++) {
            for (int y = 2; y < 6; y++) {
                cells.add(options.helper().absolutePos(new BlockPos(x + dx, y, 22)));
            }
        }
        return cells;
    }

    private void clearPackets() {
        options.channel().runPendingTasks();
        options.channel().outboundMessages().clear();
    }

    private void finish(Throwable failure) {
        try {
            if (renderer != null) {
                renderer.close();
            }
            if (remote != null) {
                remote.discard();
            }
            if (local != null) {
                local.discard();
            }
            if (source != null) {
                options.runtime().portals().remove(options.player(), source.getId());
            }
            if (destination != null) {
                options.runtime().portals().remove(options.player(), destination.getId());
            }
            options.player().setPos(previousPosition);
        } catch (Throwable cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure == null) {
            result.complete(true);
        } else {
            result.completeExceptionally(failure);
        }
    }

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel) {
    }
}
