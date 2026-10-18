package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

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
    }
}
