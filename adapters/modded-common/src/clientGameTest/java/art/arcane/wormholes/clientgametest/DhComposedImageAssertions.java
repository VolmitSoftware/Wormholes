package art.arcane.wormholes.clientgametest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

final class DhComposedImageAssertions {
    private DhComposedImageAssertions() {
    }

    static BufferedImage read(Path image) {
        try {
            BufferedImage result = ImageIO.read(image.toFile());
            if (result == null) {
                throw new AssertionError("Unable to decode DH framebuffer screenshot: " + image);
            }
            return result;
        } catch (IOException exception) {
            throw new AssertionError("Unable to read DH framebuffer screenshot: " + image, exception);
        }
    }

    static void assertDestinationLandmark(BufferedImage image) {
        if (image.getWidth() != 1920 || image.getHeight() != 1080) {
            throw new AssertionError("DH landmark acceptance requires a native 1920x1080 screenshot");
        }
        int cyanPixels = 0;
        int cyanColumns = 0;
        for (int x = 930; x < 1130; x++) {
            int columnPixels = 0;
            for (int y = 438; y < 474; y++) {
                int color = image.getRGB(x, y);
                int red = color >> 16 & 255;
                int green = color >> 8 & 255;
                int blue = color & 255;
                if (green > red + 8 && blue > red + 8) {
                    columnPixels++;
                }
            }
            cyanPixels += columnPixels;
            if (columnPixels >= 32) {
                cyanColumns++;
            }
        }
        if (cyanPixels < 6480 || cyanColumns < 180) {
            throw new AssertionError("DH destination cyan landmark lacks its expected visible interior in the composed portal image: pixels="
                + cyanPixels + "/7200 columns=" + cyanColumns + "/200");
        }
        System.out.println("WORMHOLES_DH_COMPOSED_DESTINATION_PASS cyanPixels=" + cyanPixels + " cyanColumns=" + cyanColumns);
    }

    static LandmarkContrast shaderDestinationControl(BufferedImage image) {
        LandmarkContrast contrast = shaderLandmark(image);
        System.out.println("WORMHOLES_DH_SHADER_LANDMARK_CONTROL_PASS contrast=" + contrast);
        return contrast;
    }

    static void assertShaderDestinationLandmark(LandmarkContrast expected, BufferedImage image) {
        LandmarkContrast actual = shaderLandmark(image);
        if (!matchingContrast(expected.red(), actual.red()) || !matchingContrast(expected.green(), actual.green())
            || !matchingContrast(expected.blue(), actual.blue())) {
            throw new AssertionError("DH shader portal landmark differs from the direct destination control: expected="
                + expected + " actual=" + actual);
        }
        System.out.println("WORMHOLES_DH_COMPOSED_DESTINATION_PASS shaderContrast=" + actual);
    }

    private static LandmarkContrast shaderLandmark(BufferedImage image) {
        if (image.getWidth() != 1920 || image.getHeight() != 1080) {
            throw new AssertionError("DH shader landmark acceptance requires a native 1920x1080 screenshot");
        }
        double red = 0.0D;
        double green = 0.0D;
        double blue = 0.0D;
        int columns = 0;
        for (int x = 940; x < 1010; x++) {
            double r = channel(image, x, 404, 416, 16) - channel(image, x, 438, 450, 16);
            double g = channel(image, x, 404, 416, 8) - channel(image, x, 438, 450, 8);
            double b = channel(image, x, 404, 416, 0) - channel(image, x, 438, 450, 0);
            if (r >= 25.0D && g >= 10.0D && b >= 25.0D) {
                columns++;
            }
            red += r;
            green += g;
            blue += b;
        }
        if (columns < 64) {
            throw new AssertionError("DH shader destination landmark lacks its expected horizontal edge: columns=" + columns);
        }
        return new LandmarkContrast(red / 70.0D, green / 70.0D, blue / 70.0D);
    }

    private static double channel(BufferedImage image, int x, int startY, int endY, int shift) {
        int sum = 0;
        for (int y = startY; y < endY; y++) {
            sum += image.getRGB(x, y) >> shift & 255;
        }
        return (double) sum / (endY - startY);
    }

    private static boolean matchingContrast(double expected, double actual) {
        return actual >= expected * 0.5D && actual <= expected * 2.0D;
    }

    static void assertSourceFloor(BufferedImage expected, BufferedImage actual) {
        if (expected.getWidth() != 1920 || expected.getHeight() != 1080
            || actual.getWidth() != 1920 || actual.getHeight() != 1080) {
            throw new AssertionError("DH composed-image acceptance requires native 1920x1080 screenshots");
        }
        long difference = 0L;
        int samples = 0;
        for (int y = 840; y < 1040; y += 4) {
            for (int x = 640; x < 1280; x += 4) {
                int reference = expected.getRGB(x, y);
                int observed = actual.getRGB(x, y);
                for (int shift = 0; shift < 24; shift += 8) {
                    difference += Math.abs((reference >> shift & 255) - (observed >> shift & 255));
                    samples++;
                }
            }
        }
        double meanDifference = (double) difference / samples;
        if (meanDifference > 20.0D) {
            throw new AssertionError("DH portal changed the source floor outside its aperture: mean RGB difference=" + meanDifference);
        }
        System.out.println("WORMHOLES_DH_COMPOSED_FLOOR_PASS meanRgbDifference=" + meanDifference);
    }

    static void assertSourceBackground(BufferedImage expected, BufferedImage actual) {
        if (expected.getWidth() != 1920 || expected.getHeight() != 1080
            || actual.getWidth() != 1920 || actual.getHeight() != 1080) {
            throw new AssertionError("DH source-background acceptance requires native 1920x1080 screenshots");
        }
        int sourcePixels = 0;
        int matchingPixels = 0;
        long difference = 0L;
        for (int y = 390; y < 580; y++) {
            for (int x = 1400; x < 1750; x++) {
                int reference = expected.getRGB(x, y);
                int red = reference >> 16 & 255;
                int green = reference >> 8 & 255;
                int blue = reference & 255;
                if (red <= green + 8 || blue <= green + 8) {
                    continue;
                }
                sourcePixels++;
                int observed = actual.getRGB(x, y);
                int observedRed = observed >> 16 & 255;
                int observedGreen = observed >> 8 & 255;
                int observedBlue = observed & 255;
                if (observedRed > observedGreen + 8 && observedBlue > observedGreen + 8) {
                    matchingPixels++;
                }
                difference += Math.abs(red - observedRed) + Math.abs(green - observedGreen) + Math.abs(blue - observedBlue);
            }
        }
        if (sourcePixels < 8192) {
            throw new AssertionError("DH source-background control lacks its expected magenta landmark: pixels=" + sourcePixels);
        }
        double meanDifference = (double) difference / (sourcePixels * 3L);
        if (matchingPixels < sourcePixels * 0.9D || meanDifference > 20.0D) {
            throw new AssertionError("DH portal changed the source distant landmark outside its aperture: matchingPixels="
                + matchingPixels + "/" + sourcePixels + " mean RGB difference=" + meanDifference);
        }
        System.out.println("WORMHOLES_DH_COMPOSED_BACKGROUND_PASS matchingPixels=" + matchingPixels + "/" + sourcePixels
            + " meanRgbDifference=" + meanDifference);
    }

    record LandmarkContrast(double red, double green, double blue) {
    }
}
