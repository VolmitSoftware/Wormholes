package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.phys.Vec3;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;

import java.nio.file.Path;

public final class PaperArrivalFrames {
    private static String label;
    private static String dimension;
    private static double targetX;
    private static int issued;
    private static int completed;
    private static AssertionError failure;

    private PaperArrivalFrames() {
    }

    static synchronized void arm(String name, String world, double x) {
        label = name;
        dimension = world;
        targetX = x;
        issued = 0;
        completed = 0;
        failure = null;
        if (floor(0, 0, 100) || floor(100, 0, 100) || floor(0, 100, 100)) {
            throw new AssertionError("Arrival pixel oracle accepted an absent checker floor");
        }
    }

    public static synchronized void endFrame() {
        if (label == null || issued >= 8 || failure != null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
            || !dimension.equals(client.level.dimension().identifier().toString())
            || Math.abs(client.player.getX() - targetX) > 0.1D || Math.abs(client.player.getZ() - 8.5D) > 0.1D) {
            return;
        }
        if (!NativeTestWindow.hiddenAndUnfocused(client) || !NativeTestWindow.mouseReleased(client)) {
            failure = new AssertionError("Arrival client took window or mouse focus");
            return;
        }
        if (client.player.isDeadOrDying() || ClientGui.screen(client) != null) {
            failure = new AssertionError("Arrival destination frame is obstructed or the player is dead: screen="
                + ClientGui.screen(client) + " camera=" + ClientRenderAccess.gameState(client).levelRenderState.cameraRenderState.pos
                + " targetX=" + targetX + " diagnostics=" + WormholesClient.instance().arrivals().diagnostics());
            return;
        }
        if (Boolean.getBoolean("wormholes.test.shaderPackRequested") && (!Iris.isPackInUseQuick()
            || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline))) {
            failure = new AssertionError("Arrival destination frame lost the requested shader pipeline");
            return;
        }
        Vec3 camera = ClientRenderAccess.gameState(client).levelRenderState.cameraRenderState.pos;
        if (Math.abs(camera.x - targetX) > 0.1D || Math.abs(camera.z - 8.5D) > 0.1D) {
            return;
        }
        if (!client.level.hasChunk((int) Math.floor(targetX) >> 4, 0)) {
            failure = new AssertionError("First destination frame has no landing chunk");
            return;
        }
        int frame = ++issued;
        String sample = label;
        if (frame == 1) {
            System.out.println("WORMHOLES_PAPER_ARRIVAL_POSE sample=" + sample + " player=" + client.player.position()
                + " yaw=" + client.player.getYRot() + " pitch=" + client.player.getXRot()
                + " camera=" + camera + " cameraPitch=" + ClientRenderAccess.mainCamera(client).xRot()
                + " diagnostics=" + WormholesClient.instance().arrivals().diagnostics());
        }
        Path output = client.gameDirectory.toPath().resolve("screenshots/paper-arrival/" + sample + "-" + frame + ".png");
        FramebufferProbe.capture(client, output, pixels -> inspect(sample, frame, pixels));
    }

    static synchronized boolean complete() {
        if (failure != null) {
            throw failure;
        }
        return completed == 8;
    }

    static synchronized void clear() {
        label = null;
    }

    public static synchronized void respawn(ClientboundRespawnPacket packet) {
        if (label != null) {
            System.out.println("WORMHOLES_PAPER_ARRIVAL_RESPAWN sample=" + label + " keepAll="
                + packet.shouldKeep(ClientboundRespawnPacket.KEEP_ALL_DATA) + " world="
                + packet.commonPlayerSpawnInfo().dimension().identifier() + " seaLevel=" + packet.commonPlayerSpawnInfo().seaLevel()
                + " diagnostics=" + WormholesClient.instance().arrivals().diagnostics());
        }
    }

    private static synchronized void inspect(String sample, int frame, FramebufferProbe pixels) {
        int red = 0;
        int white = 0;
        int total = 0;
        for (int y = pixels.height() / 5; y < pixels.height() * 4 / 5; y += 3) {
            for (int x = pixels.width() / 5; x < pixels.width() * 4 / 5; x += 3) {
                int color = pixels.read(x, y);
                int r = color >> 16 & 255;
                int g = color >> 8 & 255;
                int b = color & 255;
                if (r > 60 && r > g * 1.6D && r > b * 1.6D) {
                    red++;
                }
                if (r > 100 && g > 100 && b > 100 && Math.max(r, Math.max(g, b)) < Math.min(r, Math.min(g, b)) * 1.6D) {
                    white++;
                }
                total++;
            }
        }
        if (!floor(red, white, total)) {
            failure = new AssertionError("Arrival floor missing in " + sample + " frame=" + frame
                + " red=" + red + " white=" + white + " total=" + total);
        }
        completed++;
        System.out.println("WORMHOLES_PAPER_ARRIVAL_FRAME sample=" + sample + " frame=" + frame
            + " red=" + red + " white=" + white + " total=" + total);
    }

    private static boolean floor(int red, int white, int total) {
        return total > 0 && red > total / 12 && white > total / 12;
    }
}
