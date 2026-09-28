package art.arcane.wormholes.modded;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class MinecraftFidelityGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final List<Packet<?>> packets = new ArrayList<>();
    private final List<PhysicalBlock> physical = new ArrayList<>();
    private final float rain;
    private final float thunder;
    private final boolean weather;
    private MinecraftGameTestPlayer connection;
    private MinecraftPortal source;
    private MinecraftPortal destination;
    private int attempts;

    private MinecraftFidelityGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
        rain = helper.getLevel().getRainLevel(1);
        thunder = helper.getLevel().getThunderLevel(1);
        weather = FidelitySettings.weather;
    }

    static CompletableFuture<Boolean> run(GameTestHelper helper, WormholesModRuntime runtime) {
        MinecraftFidelityGameTest test = new MinecraftFidelityGameTest(helper, runtime);
        try {
            test.start();
        } catch (Throwable failure) {
            test.finish(failure);
        }
        return test.result;
    }

    private void start() {
        ServerLevel level = helper.getLevel();
        connection = MinecraftGameTestPlayer.connect(runtime, level, "fidelity-probe");
        connection.channel().pipeline().addLast("wormholes-fidelity-packets", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof Packet<?> packet) {
                    packets.add(packet);
                }
                context.write(message, promise);
            }
        });
        ServerPlayer player = connection.player();
        for (int x = 3; x < 6; x++) {
            for (int y = 3; y < 6; y++) {
                BlockPos position = helper.absolutePos(new BlockPos(x, y, 25));
                physical.add(new PhysicalBlock(position, level.getBlockState(position)));
                level.setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            }
        }
        source = runtime.portals().create(player.getUUID(), level, cells(3), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), level, cells(19), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Fidelity fixture did not link portals");
        source.setAtmosphereMode(AtmosphereMode.FULL);
        source.setAcousticsProfile(AcousticsProfile.FULL);
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        destination.setAmbientStyle(AmbientParticleStyle.OFF);
        source.setNetworkViewDepth(8);
        source.setNetworkViewLateralPad(8);
        GeometryVector origin = source.getOrigin();
        player.setPos(origin.x(), origin.y() - player.getEyeHeight(), origin.z() - 3);
        player.setYRot(0);
        player.setXRot(0);
        FidelitySettings.weather = true;
        level.setRainLevel(1);
        level.setThunderLevel(0);
        helper.runAfterDelay(2, this::verifyWeather);
    }

    private void verifyWeather() {
        try {
            connection.channel().runPendingTasks();
            boolean rainPacket = packets.stream().anyMatch(packet -> packet instanceof ClientboundLevelParticlesPacket particles
                && (particles.getParticle().getType() == ParticleTypes.RAIN || particles.getParticle().getType() == ParticleTypes.SNOWFLAKE));
            if (!rainPacket && attempts++ < 80) {
                helper.runAfterDelay(2, this::verifyWeather);
                return;
            }
            helper.assertTrue(rainPacket, "Destination weather did not reach native observer: packets=" + packets.size() + ", projectors=" + runtime.projections().projectorCount() + ", rain=" + helper.getLevel().isRaining());
            packets.clear();
            GeometryVector target = destination.getOrigin();
            helper.getLevel().playSound(null, target.x(), target.y(), target.z(), SoundEvents.NOTE_BLOCK_HARP, SoundSource.BLOCKS, 1.0f, 1.0f);
            helper.runAfterDelay(1, this::verifySound);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private void verifySound() {
        try {
            connection.channel().runPendingTasks();
            GeometryVector aperture = source.getGeometry().getApertureCenter();
            boolean relayed = packets.stream().anyMatch(packet -> packet instanceof ClientboundSoundPacket sound
                && sound.getSound().value().location().toString().equals("minecraft:block.note_block.harp")
                && Math.abs(sound.getX() - aperture.x()) < 0.13 && Math.abs(sound.getZ() - aperture.z()) < 0.13
                && sound.getSource() == SoundSource.BLOCKS && sound.getVolume() > 0);
            helper.assertTrue(relayed, "World sound broadcast did not relay through native portal acoustics");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS fidelity_packets weather sound_broadcast aperture_relay");
            finish(null);
        } catch (Throwable failure) {
            finish(failure);
        }
    }

    private List<BlockPos> cells(int x) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int y = 3; y < 6; y++) {
                cells.add(helper.absolutePos(new BlockPos(x + dx, y, 23)));
            }
        }
        return cells;
    }

    private record PhysicalBlock(BlockPos position, BlockState state) { }

    private void finish(Throwable failure) {
        try {
            helper.getLevel().setRainLevel(rain);
            helper.getLevel().setThunderLevel(thunder);
            FidelitySettings.weather = weather;
            for (PhysicalBlock block : physical) { helper.getLevel().setBlockAndUpdate(block.position(), block.state()); }
            if (connection != null) {
                if (source != null) { runtime.portals().remove(connection.player(), source.getId()); }
                if (destination != null) { runtime.portals().remove(connection.player(), destination.getId()); }
                connection.close();
            }
        } catch (Throwable cleanup) {
            if (failure == null) { failure = cleanup; }
            else { failure.addSuppressed(cleanup); }
        }
        if (failure == null) { result.complete(true); }
        else { result.completeExceptionally(failure); }
    }
}
