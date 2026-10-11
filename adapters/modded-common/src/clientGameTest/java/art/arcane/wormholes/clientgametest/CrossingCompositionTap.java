package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class CrossingCompositionTap {
    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static ClientLevel source;
    private static double planeZ;
    private static String label;
    private static int arrivalFrames;
    private static int captureIndex;
    private static int completed;
    private static boolean arrived;

    private CrossingCompositionTap() {
    }

    static synchronized void arm(Minecraft minecraft, String name, double z) {
        source = minecraft.level;
        planeZ = z;
        label = name;
        arrivalFrames = 0;
        captureIndex = 0;
        completed = 0;
        arrived = false;
        SAMPLES.clear();
    }

    static synchronized void frame(Minecraft minecraft) {
        if (source == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        Vec3 eye = minecraft.gameRenderer.mainCamera().position();
        arrived |= minecraft.level != source || Math.abs(eye.z - planeZ) > 10.0D;
        if (arrived && arrivalFrames++ >= 8 || !arrived && Math.abs(eye.z - planeZ) > 0.35D) {
            return;
        }
        int index = captureIndex++;
        String name = label;
        boolean destination = arrived;
        Screenshot.grab(minecraft.gameDirectory, "composition-" + name + "-" + index + ".png",
            minecraft.gameRenderer.mainRenderTarget(), 1, message -> System.out.println("WORMHOLES_CROSSING_IMAGE " + message.getString()));
        FramebufferProbe.capture(minecraft, image -> sampled(name, index, destination, image));
    }

    static synchronized boolean complete() {
        return arrivalFrames >= 8 && completed == captureIndex;
    }

    static synchronized void verify() {
        int destination = 0;
        int approach = 0;
        for (Sample sample : SAMPLES) {
            if (sample.destination()) {
                destination++;
            } else {
                approach++;
            }
            if (sample.bright() < 23) {
                throw new AssertionError(label + " crossing frame " + sample.index() + " lost composed destination terrain: " + sample);
            }
        }
        if (approach < 2 || destination != 8) {
            throw new AssertionError(label + " crossing image coverage incomplete: approach=" + approach + ", destination=" + destination);
        }
        System.out.println("WORMHOLES_CROSSING_COMPOSITION_PASS " + label + " approach=" + approach + " destination=" + destination);
    }

    static synchronized void close() {
        source = null;
    }

    private static synchronized void sampled(String name, int index, boolean destination, FramebufferProbe image) {
        if (!name.equals(label)) {
            throw new AssertionError("Crossing capture completed after its scene was replaced");
        }
        if (image.width() != 1920 || image.height() != 1080) {
            throw new AssertionError("Crossing composition requires native 1920x1080 frames");
        }
        int bright = 0;
        int minimum = 255;
        for (int y = 350; y <= 550; y += 50) {
            for (int x = 740; x <= 1180; x += 110) {
                int color = image.average(x, y, 20);
                int red = color >> 16 & 255;
                int green = color >> 8 & 255;
                int blue = color & 255;
                int luminance = (red + green + blue) / 3;
                minimum = Math.min(minimum, luminance);
                if (luminance >= 110 && Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) < 65) {
                    bright++;
                }
            }
        }
        SAMPLES.add(new Sample(index, destination, bright, minimum));
        completed++;
    }

    private record Sample(int index, boolean destination, int bright, int minimum) {
    }
}
