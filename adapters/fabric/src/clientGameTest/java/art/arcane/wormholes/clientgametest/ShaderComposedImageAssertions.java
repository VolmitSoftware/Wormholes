package art.arcane.wormholes.clientgametest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

final class ShaderComposedImageAssertions {
    private ShaderComposedImageAssertions() {
    }

    static void assertMirrors(Path screenshot) {
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException exception) {
            throw new AssertionError("Unable to read composed shader mirror image: " + screenshot, exception);
        }
        if (image == null || image.getWidth() != 1920 || image.getHeight() != 1080) {
            throw new AssertionError("Shader mirror image acceptance requires native 1920x1080 screenshots");
        }
        assertMirror(image, new Region(640, 450, 810, 530), new Region(640, 610, 810, 640), "first");
        assertMirror(image, new Region(1160, 420, 1420, 550), new Region(1160, 650, 1420, 680), "second");
        System.out.println("WORMHOLES_SHADER_COMPOSED_MIRRORS_PASS clouds=true floor=true");
    }

    private static void assertMirror(BufferedImage image, Region sky, Region floor, String label) {
        int minimumRed = 255;
        int maximumRed = 0;
        for (int y = sky.minimumY(); y < sky.maximumY(); y += 2) {
            for (int x = sky.minimumX(); x < sky.maximumX(); x += 2) {
                int red = image.getRGB(x, y) >> 16 & 255;
                minimumRed = Math.min(minimumRed, red);
                maximumRed = Math.max(maximumRed, red);
            }
        }
        int samples = 0;
        int whitePixels = 0;
        for (int y = floor.minimumY(); y < floor.maximumY(); y += 2) {
            for (int x = floor.minimumX(); x < floor.maximumX(); x += 2) {
                int color = image.getRGB(x, y);
                int red = color >> 16 & 255;
                int green = color >> 8 & 255;
                int blue = color & 255;
                if (red >= 180 && green >= 180 && blue >= 180) {
                    whitePixels++;
                }
                samples++;
            }
        }
        if (maximumRed - minimumRed < 15 || whitePixels * 4 < samples * 3) {
            throw new AssertionError(label + " shader mirror lacks reflected clouds or the white platform: skyRedRange="
                + (maximumRed - minimumRed) + ", whiteFloorPixels=" + whitePixels + "/" + samples);
        }
    }

    private record Region(int minimumX, int minimumY, int maximumX, int maximumY) {
    }
}
