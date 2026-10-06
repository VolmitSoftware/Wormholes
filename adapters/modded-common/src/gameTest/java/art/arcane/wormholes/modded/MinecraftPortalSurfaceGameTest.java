package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.AmbientSparkCadence;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.optics.claim.ProjectedBlockClaim;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.claim.ProjectionClaimSet;
import art.arcane.optics.view.ContentView;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MinecraftPortalSurfaceGameTest {
    private MinecraftPortalSurfaceGameTest() { }

    static void run(Options options) {
        GameTestHelper helper = options.helper();
        WormholesModRuntime runtime = options.runtime();
        ServerPlayer player = options.player();
        EmbeddedChannel channel = options.channel();
        BlockPos cell = helper.absolutePos(new BlockPos(24, 3, 4));
        MinecraftPortal portal = runtime.portals().create(player.getUUID(), helper.getLevel(), List.of(cell, cell.above()),
            PortalType.PORTAL, new Vec3(0, 0, -1));
        ProjectionClaimSet<ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>>> claims = new ProjectionClaimSet<>();
        LongOpenHashSet staged = new LongOpenHashSet();
        MinecraftProjectorPortalAccess access = new MinecraftProjectorPortalAccess(runtime);
        Vec3 previous = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        ItemStack held = player.getMainHandItem().copy();
        BlockState physical = helper.getLevel().getBlockState(cell);
        try (MinecraftPortalSurfaces surfaces = new MinecraftPortalSurfaces(runtime, new MinecraftPortalSurfaces.Context(player, claims, staged))) {
            player.setPos(cell.getX() + 0.5, cell.getY() - 0.5, cell.getZ() - 2);
            player.setYRot(0);
            player.setXRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS, 5));
            helper.assertTrue(runtime.useItem(player, InteractionHand.MAIN_HAND) && portal.getSurfaceSkin().equals("minecraft:glass"),
                "Held block gesture did not apply skin");
            helper.assertTrue(player.getMainHandItem().getCount() == 5, "Skin gesture consumed held block");
            portal.setAmbientStyle(AmbientParticleStyle.OUTLINE);
            clear(channel);
            surfaces.update(List.of(portal), access, 5);
            channel.runPendingTasks();
            ClientboundAddEntityPacket spawn = null;
            for (Object packet : channel.outboundMessages()) {
                if (packet instanceof ClientboundAddEntityPacket added) { spawn = added; }
            }
            helper.assertTrue(spawn != null, "Glass skin did not create private display packet");
            int display = spawn.getId();
            helper.assertTrue(helper.getLevel().getEntity(display) == null, "Skin display leaked into server entities");
            helper.assertTrue(channel.outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundSetEntityDataPacket),
                "Skin display metadata missing");
            helper.assertTrue(channel.outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundLevelParticlesPacket),
                "Ambient outline packets missing");
            clear(channel);
            surfaces.update(List.of(portal), access, 6);
            channel.runPendingTasks();
            helper.assertTrue(channel.outboundMessages().stream().noneMatch(packet -> packet instanceof ClientboundAddEntityPacket),
                "Unchanged skin respawned displays");
            portal.setAmbientStyle(AmbientParticleStyle.SPARKS);
            clear(channel);
            surfaces.update(List.of(portal), access, 10);
            List<ClientboundLevelParticlesPacket> sparks = particles(channel);
            helper.assertTrue(sparks.size() == 1 && sparks.get(0).particle() == ParticleTypes.MYCELIUM, "Spark burst was not one particle packet");
            helper.assertTrue(sparks.get(0).count() == (portal.isOpen() ? 4 : 1) && sparks.get(0).xDist() == (float) AmbientSparkCadence.CELL_SPREAD,
                "Spark burst lost its count or cell spread");
            helper.assertTrue(portal.getGeometry().contains(new art.arcane.optics.math.Vec3(sparks.get(0).x(), sparks.get(0).y(), sparks.get(0).z())),
                "Spark burst left the aperture cells");
            surfaces.update(List.of(portal), access, 11);
            helper.assertTrue(particles(channel).isEmpty(), "Sparks were sent between five-tick steps");
            portal.setSurfaceSkin("minecraft:water");
            surfaces.update(List.of(portal), access, 7);
            claims.resolveStaged(staged);
            staged.clear();
            long key = CellKeys.pack(cell.getX(), cell.getY(), cell.getZ());
            helper.assertTrue(claims.getWinningClaim(key) != null && claims.getWinningClaim(key).getData().is(Blocks.WATER),
                "Water skin did not claim aperture cells");
            channel.runPendingTasks();
            helper.assertTrue(channel.outboundMessages().stream().anyMatch(packet -> packet instanceof ClientboundRemoveEntitiesPacket removed
                && removed.entityIds().contains(display)), "Changing to fluid did not remove skin display");
            Long2ObjectOpenHashMap<ProjectedBlockClaim<BlockState, ContentView<BlockState, BlockState>>> behind = new Long2ObjectOpenHashMap<>();
            behind.put(key, new ProjectedBlockClaim<>(Blocks.STONE.defaultBlockState(), null, ProjectedBlockClaim.NO_REMOTE_KEY, false));
            UUID background = UUID.randomUUID();
            claims.replacePortalClaims(background, background.toString(), 100, behind);
            portal.setSurfaceSkin("");
            surfaces.update(List.of(portal), access, 8);
            claims.resolveStaged(staged);
            staged.clear();
            helper.assertTrue(claims.getWinningClaim(key).getData().is(Blocks.STONE), "Clearing fluid did not reveal competing projection claim");
            portal.setSurfaceSkin("minecraft:lava");
            surfaces.update(List.of(portal), access, 9);
            claims.resolveStaged(staged);
            staged.clear();
            surfaces.close();
            claims.resolveStaged(staged);
            helper.assertTrue(claims.getWinningClaim(key).getData().is(Blocks.STONE), "Surface shutdown did not release fluid claim");
            helper.assertTrue(helper.getLevel().getBlockState(cell) == physical, "Surface rendering mutated physical world");
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new BrandPayload("Geyser")));
            helper.assertTrue(MinecraftClientProfiles.profile(player).withholdsDisplays(), "Native brand payload did not apply Bedrock caps");
            portal.setSurfaceSkin("minecraft:glass");
            clear(channel);
            surfaces.update(List.of(portal), access, 11);
            claims.resolveStaged(staged);
            staged.clear();
            channel.runPendingTasks();
            helper.assertTrue(claims.getWinningClaim(key).getData().is(Blocks.GLASS), "Bedrock skin did not use block claims");
            helper.assertTrue(channel.outboundMessages().stream().noneMatch(packet -> packet instanceof ClientboundAddEntityPacket),
                "Bedrock skin emitted disabled display entity");
            Short2ObjectMap<BlockState> changes = new Short2ObjectOpenHashMap<>();
            for (short index = 0; index < 150; index++) { changes.put(index, Blocks.GLASS.defaultBlockState()); }
            new MinecraftProjectionPackets(runtime).sendSection(player, SectionPos.asLong(cell.getX() >> 4, cell.getY() >> 4, cell.getZ() >> 4), changes);
            channel.runPendingTasks();
            int updates = 0;
            int blocks = 0;
            for (Object packet : channel.outboundMessages()) {
                if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
                    int[] count = {0};
                    update.runUpdates((position, state) -> count[0]++);
                    helper.assertTrue(count[0] <= 64, "Bedrock block batch exceeded conservative limit");
                    updates++;
                    blocks += count[0];
                }
            }
            helper.assertTrue(updates == 3 && blocks == 150, "Bedrock section batching dropped or duplicated changes");
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
            helper.assertTrue(!MinecraftClientProfiles.profile(player).bedrock(), "Changed native brand retained stale Bedrock profile");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS bedrock_profile native_brand skin_fallback block_batches refresh");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS portal_surfaces held_skin display metadata ambient spark_batch fluid_claims competing_claim teardown");
        } finally {
            player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")));
            player.setPos(previous);
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            runtime.portals().remove(player, portal.getId());
        }
    }

    private static List<ClientboundLevelParticlesPacket> particles(EmbeddedChannel channel) {
        channel.runPendingTasks();
        List<ClientboundLevelParticlesPacket> particles = new ArrayList<>();
        for (Object packet : channel.outboundMessages()) {
            if (packet instanceof ClientboundLevelParticlesPacket particle) {
                particles.add(particle);
            }
        }
        channel.outboundMessages().clear();
        return particles;
    }

    private static void clear(EmbeddedChannel channel) {
        channel.runPendingTasks();
        channel.outboundMessages().clear();
    }

    record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, EmbeddedChannel channel) { }
}
