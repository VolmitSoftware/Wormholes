package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.modded.mixin.DoorDisplayDataAccess;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MinecraftPortalEffectsGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connected;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private final Map<Integer, Integer> metadata = new HashMap<>();
    private final Set<Integer> spawned = new HashSet<>();
    private final Set<Integer> removed = new HashSet<>();
    private final Set<Integer> interpolation = new HashSet<>();
    private int sounds;
    private int particles;
    private boolean cleaned;
    private final boolean previousParticles;

    private MinecraftPortalEffectsGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connected = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "EffectsProbe");
        BlockPos cell = helper.absolutePos(new BlockPos(3, 3, 4));
        connected.player().setPos(cell.getX() + .5, cell.getY(), cell.getZ() - 3);
        source = runtime.portals().create(connected.player().getUUID(), helper.getLevel(), List.of(cell, cell.above()), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(connected.player().getUUID(), helper.getLevel(), List.of(cell.east(6), cell.east(6).above()), PortalType.PORTAL, new Vec3(0, 0, -1));
        previousParticles = runtime.configuration().settings().getMain().enableParticles;
        runtime.configuration().settings().getMain().enableParticles = true;
        drain();
        reset();
        runtime.effects().created(source, Map.of(cell, Blocks.GLASS.defaultBlockState(), cell.above(), Blocks.GLASS.defaultBlockState()));
    }

    public static void run(GameTestHelper helper) {
        MinecraftPortalEffectsGameTest test = new MinecraftPortalEffectsGameTest(helper);
        test.start();
    }

    private void start() {
        runtime.schedule(this::cleanup, 590);
        helper.startSequence().thenIdle(6).thenExecute(() -> {
            drain();
            helper.assertTrue(spawned.size() == 2, "Consumed runes did not create two private effect displays");
            helper.assertTrue(spawned.stream().allMatch(id -> metadata.getOrDefault(id, 0) > 2), "Rune displays did not animate their transforms");
            helper.assertTrue(interpolation.containsAll(spawned), "Animated displays omitted their interpolation restart metadata");
            for (int id : spawned) {
                helper.assertTrue(helper.getLevel().getEntity(id) == null, "Formation effect created a persistent server entity");
            }
            helper.assertTrue(sounds > 0 && particles > 0, "Formation omitted sound or particles");
        }).thenIdle(34).thenExecute(() -> {
            drain();
            helper.assertTrue(removed.containsAll(spawned), "Completed formation retained private display entities");
            helper.assertTrue(sounds >= 5, "Formation omitted convergence sounds");
            reset();
            source.link(destination);
        }).thenIdle(35).thenExecute(() -> {
            drain();
            helper.assertTrue(sounds >= 4 && particles > 0, "Link opening omitted prelude, impact, or delayed sounds");
            reset();
            source.unlink();
        }).thenIdle(16).thenExecute(() -> {
            drain();
            helper.assertTrue(spawned.size() == 1 && removed.containsAll(spawned), "Unlink closing did not create and remove its glass pane");
            helper.assertTrue(sounds >= 4 && particles > 0, "Unlink closing omitted cracking sound or particles");
            reset();
            runtime.configuration().settings().getMain().enableParticles = false;
            source.link(destination);
        }).thenIdle(10).thenExecute(() -> {
            drain();
            helper.assertTrue(spawned.isEmpty() && particles == 0 && sounds >= 4, "Disabled particles did not preserve sound-only opening");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS effects_runtime formation_display_transform formation_cleanup link_open unlink_close sound_particles particles_disabled");
            cleanup();
        }).thenSucceed();
    }

    private void drain() {
        connected.channel().runPendingTasks();
        Object raw;
        while ((raw = connected.channel().readOutbound()) != null) {
            try {
                Object packet = HiddenByteBuf.unpack(raw);
                if (packet instanceof ByteBuf bytes) {
                    packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess())).codec().decode(bytes.duplicate());
                }
                if (packet instanceof ClientboundAddEntityPacket added && added.getType() == EntityTypes.BLOCK_DISPLAY) {
                    spawned.add(added.getId());
                } else if (packet instanceof ClientboundSetEntityDataPacket data) {
                    metadata.merge(data.id(), 1, Integer::sum);
                    if (data.packedItems().stream().anyMatch(value -> value.id() == DoorDisplayDataAccess.wormholesInterpolationStart().id())) {
                        interpolation.add(data.id());
                    }
                } else if (packet instanceof ClientboundRemoveEntitiesPacket removal) {
                    for (int id : removal.entityIds()) {
                        removed.add(id);
                    }
                } else if (packet instanceof ClientboundSoundPacket) {
                    sounds++;
                } else if (packet instanceof ClientboundLevelParticlesPacket) {
                    particles++;
                }
            } finally {
                ReferenceCountUtil.release(raw);
            }
        }
    }

    private void reset() {
        metadata.clear();
        interpolation.clear();
        spawned.clear();
        removed.clear();
        sounds = 0;
        particles = 0;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        runtime.configuration().settings().getMain().enableParticles = previousParticles;
        runtime.portals().remove(source.getId());
        runtime.portals().remove(destination.getId());
        connected.close();
    }
}
