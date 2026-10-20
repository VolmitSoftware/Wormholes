package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperCrossingLatencyClientGameTest implements FabricClientGameTest {
    private static final Pattern DELAY = Pattern.compile("delayMicros=(-?\\d+)");
    private static final Pattern DEPARTURE = Pattern.compile("departureZ=(-?[\\d.]+)");
    private static final long MAX_DELAY_MICROS = Long.getLong("wormholes.test.crossingMaxMillis", 150L) * 1000L;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (ExternalServerSession server = ExternalServerSession.connect(new FabricTestClient(context),
            ExternalServerSession.configuredAddress())) {
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), 600);
            context.runOnClient(client -> {
                if (!NativeTestWindow.hiddenAndUnfocused(client) || !NativeTestWindow.mouseReleased(client)) {
                    throw new AssertionError("Crossing latency requires a hidden GPU client with released mouse");
                }
                client.options.framerateLimit().set(60);
                client.options.bobView().set(false);
            });
            server.command("whnested latency setup", "LATENCY setup=true");
            for (String phase : new String[] {"cold", "warm", "plain", "simple"}) {
                boolean enabled = !phase.equals("plain");
                boolean prewarm = phase.equals("warm") || phase.equals("simple");
                if (phase.equals("simple")) {
                    server.command("whnested latency simple", "LATENCY simple=true");
                }
                server.command("whnested latency clientview " + enabled, "LATENCY clientview=" + enabled);
                context.waitFor(client -> WormholesClient.instance().session().active() == enabled, 600);
                server.command("whnested latency prewarm false", "LATENCY prewarm=false");
                double startZ = prewarm ? 14.5D : 10D;
                context.runOnClient(client -> client.getConnection().sendCommand("tp @s 8.5 191 " + startZ + " 180 0"));
                context.waitFor(client -> Math.abs(client.player.getX() - 8.5D) < 0.1D
                    && Math.abs(client.player.getY() - 191D) < 0.1D && Math.abs(client.player.getZ() - startZ) < 0.1D, 400);
                if (prewarm) {
                    context.waitTicks(120);
                    server.command("whnested latency prewarm true", "LATENCY prewarm=true");
                }
                for (int crossing = 0; crossing < 6; crossing++) {
                    cross(context, server, phase, crossing);
                }
            }
            server.command("whnested latency cooldown 1000", "LATENCY cooldownMillis=1000");
            for (int crossing = 0; crossing < 2; crossing++) {
                context.waitTicks(25);
                cross(context, server, "default-cooldown", crossing);
            }
            if (context.computeOnClient(client -> WormholesClient.instance().arrivals().diagnostics().adopted()) == 0) {
                throw new AssertionError("No natural approach adopted prepared destination terrain");
            }
            System.out.println("WORMHOLES_CROSSING_LATENCY_PASS");
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
            context.restoreDefaultGameOptions();
        }
    }

    private void cross(ClientGameTestContext context, ExternalServerSession server, String phase, int crossing) {
        double sourceX = context.computeOnClient(client -> client.player.getX());
        double destinationX = sourceX < 50D ? 104.5D : 8.5D;
        long adopted = context.computeOnClient(client -> WormholesClient.instance().arrivals().diagnostics().adopted());
        server.command("whnested latency arm", "LATENCY armed=true");
        context.runOnClient(client -> {
            client.player.setYRot(client.player.getZ() > 8.5D ? 180F : 0F);
            client.player.setXRot(0F);
        });
        context.getInput().holdKey(options -> options.keyUp);
        try {
            context.waitFor(client -> Math.abs(client.player.getX() - destinationX) < 0.25D, 160);
            context.waitFor(client -> Math.abs(client.player.getZ() - 8.5D) >= 1.25D, 80);
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        String sample = server.command("whnested latency sample", "LATENCY");
        Matcher matcher = DELAY.matcher(sample);
        if (!matcher.find()) {
            throw new AssertionError("Missing server crossing timestamp: " + sample);
        }
        long micros = Long.parseLong(matcher.group(1));
        if (micros < 0) {
            Matcher departure = DEPARTURE.matcher(sample);
            if (!sample.contains("earlyCrossing=true") || !departure.find()
                || Math.abs(Double.parseDouble(departure.group(1)) - 8.5D) > 0.3D) {
                throw new AssertionError("Teleport did not coincide with the measured portal plane: " + sample);
            }
        }
        System.out.println("WORMHOLES_CROSSING_LATENCY phase=" + phase + " crossing=" + crossing + " " + sample
            + " adopted=" + (context.computeOnClient(client -> WormholesClient.instance().arrivals().diagnostics().adopted()) - adopted)
            + " diagnostics=" + context.computeOnClient(client -> WormholesClient.instance().arrivals().diagnostics()));
        if (micros > MAX_DELAY_MICROS) {
            throw new AssertionError("Physical portal crossing waited " + micros + " microseconds before teleport; limit=" + MAX_DELAY_MICROS);
        }
        verifyLanding(context, phase + "-" + crossing, destinationX);
    }

    private void verifyLanding(ClientGameTestContext context, String label, double destinationX) {
        try {
            context.waitFor(client -> landingReady(client, destinationX), 10);
        } catch (AssertionError failure) {
            String state = context.computeOnClient(this::landingState);
            try {
                context.takeScreenshot("paper-crossing-" + label + "-terrain-failed");
            } catch (RuntimeException | AssertionError screenshotFailure) {
                failure.addSuppressed(screenshotFailure);
            }
            throw new AssertionError("Physical portal arrival lost terrain during " + label + ": " + state, failure);
        }
        context.takeScreenshot("paper-crossing-" + label + "-terrain");
        System.out.println("WORMHOLES_CROSSING_TERRAIN sample=" + label + " " + context.computeOnClient(this::landingState));
    }

    private boolean landingReady(Minecraft client, double destinationX) {
        if (client.level == null || client.player == null || client.player.level() != client.level
            || Math.abs(client.player.getX() - destinationX) > 0.25D || Math.abs(client.player.getY() - 191D) > 0.25D
            || ClientGui.screen(client) != null) {
            return false;
        }
        BlockPos floor = BlockPos.containing(client.player.getX(), 190D, client.player.getZ());
        return client.level.getChunkSource().getChunk(floor.getX() >> 4, floor.getZ() >> 4, ChunkStatus.FULL, false) != null
            && client.level.getBlockState(floor).is(Blocks.STONE) && MainRendererSections.visible(client) > 0
            && MainRendererSections.missingReadySections(client) == 0;
    }

    private String landingState(Minecraft client) {
        if (client.level == null || client.player == null) {
            return "level=" + client.level + " player=" + client.player + " screen=" + ClientGui.screen(client);
        }
        BlockPos floor = BlockPos.containing(client.player.getX(), 190D, client.player.getZ());
        PreparedChunkColumns cache = (PreparedChunkColumns) client.level.getChunkSource();
        AtomicReferenceArray<LevelChunk> columns = cache.wormholes$columns();
        int loaded = 0;
        for (int index = 0; index < columns.length(); index++) {
            if (columns.get(index) != null) {
                loaded++;
            }
        }
        return "dimension=" + client.level.dimension().identifier() + " position=" + client.player.position()
            + " playerOwnsLevel=" + (client.player.level() == client.level) + " floor=" + floor
            + " landingChunk=" + (client.level.getChunkSource().getChunk(floor.getX() >> 4, floor.getZ() >> 4, ChunkStatus.FULL, false) != null)
            + " floorState=" + client.level.getBlockState(floor) + " cacheRadius=" + cache.wormholes$radius()
            + " loadedColumns=" + loaded + "/" + columns.length() + " visibleSections=" + MainRendererSections.visible(client)
            + " missingReadySections=" + MainRendererSections.missingReadySections(client)
            + " terrainComplete=" + MainRendererSections.terrainComplete(client) + " screen=" + ClientGui.screen(client)
            + " clientview=" + WormholesClient.instance().session().active() + " resets=" + WormholesClient.instance().session().resets()
            + " arrivals=" + WormholesClient.instance().arrivals().diagnostics();
    }
}
