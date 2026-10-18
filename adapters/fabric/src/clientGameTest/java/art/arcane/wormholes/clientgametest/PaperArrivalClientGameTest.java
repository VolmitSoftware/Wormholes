package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.ArrivalMessage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

public final class PaperArrivalClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (ExternalServerSession server = ExternalServerSession.connect(new FabricTestClient(context),
            ExternalServerSession.configuredAddress())) {
            context.waitFor(client -> WormholesClient.instance() != null && WormholesClient.instance().session().active(), 600);
            context.runOnClient(client -> {
                if (!ClientGui.hudHidden(client)) {
                    ClientGui.toggleHud(client);
                }
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.options.bobView().set(false);
                client.options.ambientOcclusion().set(false);
                client.options.fov().set(70);
                client.options.fovEffectScale().set(0D);
                client.options.framerateLimit().set(20);
            });
            server.command("whnested arrival setup", "ARRIVAL setup=true");
            server.command("whnested arrival stage", "ARRIVAL crossed=true");
            context.waitTicks(40);
            context.runOnClient(client -> PaperArrivalFrames.arm("baseline", "minecraft:overworld", 8.5D));
            context.waitFor(client -> PaperArrivalFrames.complete(), 200);
            context.runOnClient(client -> PaperArrivalFrames.clear());
            for (int repetition = 0; repetition < 2; repetition++) {
                cross(context, server, "same", "minecraft:overworld", 520.5D, true, repetition);
                cross(context, server, "return", "minecraft:overworld", 8.5D, true, repetition);
                cross(context, server, "end", "minecraft:the_end", 520.5D, false, repetition);
                cross(context, server, "return", "minecraft:overworld", 8.5D, false, repetition);
            }
            dirty(context, server);
            server.command("whnested arrival prepare return", "ARRIVAL prepared=return");
            ready(context);
            server.command("whnested arrival cancel", "ARRIVAL cancelled=true");
            context.waitFor(client -> diagnostics().token() == null && diagnostics().bytes() == 0, 100);
            System.out.println("WORMHOLES_PAPER_ARRIVAL_PASS " + context.computeOnClient(client -> diagnostics()));
        } finally {
            context.runOnClient(client -> PaperArrivalFrames.clear());
        }
    }

    private void cross(ClientGameTestContext context, ExternalServerSession server, String mode, String dimension,
                       double x, boolean sameWorld, int repetition) {
        if (!sameWorld && Boolean.getBoolean("wormholes.test.shaderPackRequested")) {
            shaderDimensionFallback(context, server, mode, dimension, x, repetition);
            return;
        }
        long adopted = context.computeOnClient(client -> diagnostics().adopted());
        LocalPlayer player = context.computeOnClient(client -> client.player);
        ClientLevel level = context.computeOnClient(client -> client.level);
        server.command("whnested arrival prepare " + mode, "ARRIVAL prepared=" + mode);
        ready(context);
        server.command("whnested arrival cross", "ARRIVAL crossed=true",
            client -> PaperArrivalFrames.arm(mode + "-" + repetition, dimension, x));
        context.waitFor(client -> {
            PaperArrivalFrames.complete();
            return diagnostics().adopted() == adopted + 1;
        }, 100);
        context.waitFor(client -> PaperArrivalFrames.complete(), 200);
        context.runOnClient(client -> {
            if (!dimension.equals(client.level.dimension().identifier().toString()) || client.player.level() != client.level
                || Math.abs(client.player.getX() - x) > 0.1D || Math.abs(client.player.getY() - 200D) > 0.1D
                || sameWorld && (client.player != player || client.level != level)) {
                throw new AssertionError("Arrival changed player ownership or destination: " + diagnostics());
            }
            PaperArrivalFrames.clear();
        });
        context.waitFor(client -> client.level.hasChunk(((int) Math.floor(x) >> 4) + 4, 0), 400);
        System.out.println("WORMHOLES_PAPER_ARRIVAL_CROSS mode=" + mode + " repetition=" + repetition
            + " diagnostics=" + context.computeOnClient(client -> diagnostics()));
    }

    private void shaderDimensionFallback(ClientGameTestContext context, ExternalServerSession server, String mode,
                                         String dimension, double x, int repetition) {
        long adopted = context.computeOnClient(client -> diagnostics().adopted());
        long rejected = context.computeOnClient(client -> diagnostics().rejected());
        server.command("whnested arrival prepare " + mode, "ARRIVAL prepared=" + mode);
        context.waitFor(client -> diagnostics().rejected() > rejected && diagnostics().token() == null, 100);
        server.command("whnested arrival cross", "ARRIVAL crossed=true");
        context.waitFor(client -> dimension.equals(client.level.dimension().identifier().toString())
            && Math.abs(client.player.getX() - x) < 0.1D, 200);
        context.waitTicks(40);
        context.waitFor(client -> client.levelRenderer.hasRenderedAllSections(), 400);
        context.runOnClient(client -> {
            if (diagnostics().adopted() != adopted || diagnostics().token() != null) {
                throw new AssertionError("Shader dimension switch adopted an invalid prepared pipeline: " + diagnostics());
            }
            PaperArrivalFrames.arm("recovered-" + mode + "-" + repetition, dimension, x);
        });
        context.waitFor(client -> PaperArrivalFrames.complete(), 200);
        context.runOnClient(client -> PaperArrivalFrames.clear());
        System.out.println("WORMHOLES_PAPER_ARRIVAL_SHADER_FALLBACK mode=" + mode + " repetition=" + repetition
            + " diagnostics=" + context.computeOnClient(client -> diagnostics()));
        long retryAfter = System.nanoTime() + 6_000_000_000L;
        context.waitFor(client -> System.nanoTime() >= retryAfter, 400);
    }

    private void dirty(ClientGameTestContext context, ExternalServerSession server) {
        long adopted = context.computeOnClient(client -> diagnostics().adopted());
        server.command("whnested arrival prepare same", "ARRIVAL prepared=same");
        ready(context);
        server.command("whnested arrival dirty", "ARRIVAL dirty=true");
        server.command("whnested arrival cross", "ARRIVAL crossed=true");
        context.waitFor(client -> Math.abs(client.player.getX() - 520.5D) < 0.1D, 200);
        context.runOnClient(client -> {
            if (diagnostics().adopted() != adopted) {
                throw new AssertionError("Dirty arrival terrain was adopted");
            }
        });
    }

    private static void ready(ClientGameTestContext context) {
        for (int tick = 0; tick < 100; tick++) {
            context.runOnClient(client -> {
                if (client.player == null || !client.player.isAlive() || ClientGui.screen(client) != null) {
                    throw new AssertionError("Arrival preparation interrupted by player death or GUI");
                }
            });
            if (context.computeOnClient(client -> diagnostics().readiness() == ArrivalMessage.Readiness.RENDER)) {
                return;
            }
            if (tick % 20 == 0) {
                System.out.println("WORMHOLES_PAPER_ARRIVAL_WAIT " + context.computeOnClient(client -> PaperArrivalDiagnostics.current()));
            }
            context.waitTicks(1);
        }
        throw new AssertionError("Arrival preparation timed out: " + context.computeOnClient(client -> PaperArrivalDiagnostics.current()));
    }

    private static ClientArrivalPreparation.Diagnostics diagnostics() {
        return WormholesClient.instance().arrivals().diagnostics();
    }
}
