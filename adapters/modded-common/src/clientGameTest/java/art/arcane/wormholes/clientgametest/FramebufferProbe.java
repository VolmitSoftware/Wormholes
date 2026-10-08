package art.arcane.wormholes.clientgametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.function.Consumer;

final class FramebufferProbe {
    private final int width;
    private final int height;
    private final int[] pixels;

    private FramebufferProbe(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    static void capture(Minecraft client, Consumer<FramebufferProbe> sink) {
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        Screenshot.takeScreenshot(target, image -> sink.accept(copy(image)));
    }

    static int[] screen(Minecraft client, Vec3 world) {
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Vector4f clip = viewProjection.transform(new Vector4f((float) (world.x - eye.x), (float) (world.y - eye.y), (float) (world.z - eye.z), 1.0F));
        if (clip.w <= 0.0F) {
            return null;
        }
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        int x = (int) Math.floor((clip.x / clip.w + 1.0F) * 0.5F * target.width);
        int y = (int) Math.floor((1.0F - clip.y / clip.w) * 0.5F * target.height);
        return x < 0 || y < 0 || x >= target.width || y >= target.height ? null : new int[] {x, y};
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int read(int x, int y) {
        return pixels[y * width + x];
    }

    int average(int x, int y, int radius) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        for (int row = Math.max(0, y - radius); row <= Math.min(height - 1, y + radius); row++) {
            for (int column = Math.max(0, x - radius); column <= Math.min(width - 1, x + radius); column++) {
                int argb = read(column, row);
                red += (argb >> 16) & 0xFF;
                green += (argb >> 8) & 0xFF;
                blue += argb & 0xFF;
                count++;
            }
        }
        return 0xFF000000 | (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
    }

    private static FramebufferProbe copy(NativeImage image) {
        try (image) {
            int[] pixels = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    pixels[y * image.getWidth() + x] = image.getPixel(x, y);
                }
            }
            return new FramebufferProbe(image.getWidth(), image.getHeight(), pixels);
        }
    }
}
