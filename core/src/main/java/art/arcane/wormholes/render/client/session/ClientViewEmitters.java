package art.arcane.wormholes.render.client.session;

import java.util.List;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.AmbientSparkCadence;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.optics.math.Box;

public final class ClientViewEmitters {
    public static final String SPARK_PARTICLE = "minecraft:mycelium";
    public static final int SURFACE_OPEN_FLAG = 1;
    public static final int SURFACE_INTERVAL_SHIFT = 1;
    public static final int MAX_SURFACE_INTERVAL = 127;
    private static final int OPEN_CORNER_WINDOW = 8;
    private static final int CLOSED_CORNER_WINDOW = 2;
    private static final int OPEN_OUTLINE_WINDOW = 32;
    private static final int CLOSED_OUTLINE_WINDOW = 8;
    public static final int ANIMATION_MODE_MASK = 0x07;
    public static final int ANIMATION_NORMAL_SHIFT = 3;
    public static final int ANIMATION_QUALITY_SHIFT = 5;
    public static final int BURST_SPEED_SCALE = 100;
    public static final int MAX_BURST_COUNT = 0xFFFF;
    public static final int SOUND_CLASS_MASK = 0x03;
    private static final int COLOR_LEVELS_SHIFT = 4;
    private static final int COLOR_LEVEL_SCALE = 17;
    private static final int MAX_FLAGS = 0xFF;

    private ClientViewEmitters() {
    }

    public static void rim(Box area, int red, int green, int blue, int intervalTicks, List<ClientViewMessage.FxEmitter> out) {
        if (area == null) {
            return;
        }
        int rgb = (quantize(red) << 16) | (quantize(green) << 8) | quantize(blue);
        int ticks = Math.max(1, intervalTicks);
        for (int corner = 0; corner < 8; corner++) {
            out.add(new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "", (corner & 1) == 0 ? area.getXa() : area.getXb(),
                (corner & 2) == 0 ? area.getYa() : area.getYb(), (corner & 4) == 0 ? area.getZa() : area.getZb(), rgb, 1.0F, ticks, 1));
        }
    }

    public static void ambient(Ambient ambient, List<ClientViewMessage.FxEmitter> out) {
        AmbientParticleStyle style = ambient.style();
        if (style == null || style == AmbientParticleStyle.OFF || ambient.area() == null) {
            return;
        }
        int cadence = Math.max(1, ambient.cadenceTicks());
        Box area = ambient.area();
        if (style == AmbientParticleStyle.SPARKS) {
            int interval = Math.max(1, Math.min(MAX_SURFACE_INTERVAL, ambient.intervalTicks()));
            int flags = (ambient.open() ? SURFACE_OPEN_FLAG : 0) | (interval << SURFACE_INTERVAL_SHIFT);
            out.add(new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SURFACE, SPARK_PARTICLE, area.getXa(), area.getYa(), area.getZa(),
                (float) AmbientSparkCadence.CELL_SPREAD, 0.0F, cadence, flags));
            return;
        }
        if (style == AmbientParticleStyle.CORNERS) {
            int window = ambient.open() ? OPEN_CORNER_WINDOW : CLOSED_CORNER_WINDOW;
            int ticks = rotation(8, window, cadence);
            for (int corner = 0; corner < 8; corner++) {
                out.add(dust((corner & 1) == 0 ? area.getXa() : area.getXb(), (corner & 2) == 0 ? area.getYa() : area.getYb(),
                    (corner & 4) == 0 ? area.getZa() : area.getZb(), ambient.rgb(), ticks));
            }
            return;
        }
        List<double[]> points = ambient.outline();
        if (points == null || points.isEmpty()) {
            return;
        }
        int limit = Math.min(points.size(), ViewStreamLimits.MAX_FX_EMITTERS - out.size());
        int window = ambient.open() ? OPEN_OUTLINE_WINDOW : CLOSED_OUTLINE_WINDOW;
        int ticks = rotation(points.size(), window, cadence);
        for (int index = 0; index < limit; index++) {
            double[] point = points.get(index);
            out.add(dust(point[0], point[1], point[2], ambient.rgb(), ticks));
        }
    }

    public static boolean oneShot(ClientViewMessage.FxEmitter emitter) {
        ClientViewMessage.FxKind kind = emitter.kind();
        return kind == ClientViewMessage.FxKind.ANIMATION || kind == ClientViewMessage.FxKind.BURST || emitter.ticks() == 0;
    }

    public static ClientViewMessage.FxEmitter animation(PortalAnimation.Mode mode, Vec3d center, Vec3d size,
                                                        VisualQualityProfile quality) {
        int normal = PortalAnimation.normalAxis(size);
        double[] extent = {size.x(), size.y(), size.z()};
        int flags = mode.ordinal() | (normal << ANIMATION_NORMAL_SHIFT) | (quality.ordinal() << ANIMATION_QUALITY_SHIFT);
        return new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.ANIMATION, "", center.x(), center.y(), center.z(),
            (float) extent[PortalAnimation.planeA(normal)], (float) extent[PortalAnimation.planeB(normal)], 0, flags);
    }

    public static Animation animation(ClientViewMessage.FxEmitter emitter) {
        if (emitter.kind() != ClientViewMessage.FxKind.ANIMATION) {
            return null;
        }
        int flags = emitter.flags();
        PortalAnimation.Mode[] modes = PortalAnimation.Mode.values();
        VisualQualityProfile[] qualities = VisualQualityProfile.values();
        int mode = flags & ANIMATION_MODE_MASK;
        int normal = (flags >>> ANIMATION_NORMAL_SHIFT) & 0x03;
        int quality = flags >>> ANIMATION_QUALITY_SHIFT;
        if (mode >= modes.length || normal > 2 || quality >= qualities.length || !Float.isFinite(emitter.paramA()) || !Float.isFinite(emitter.paramB())) {
            return null;
        }
        return new Animation(modes[mode], new Vec3d(emitter.x(), emitter.y(), emitter.z()),
            PortalAnimation.planeSize(normal, emitter.paramA(), emitter.paramB()), qualities[quality]);
    }

    public static ClientViewMessage.FxEmitter burst(String particle, double x, double y, double z, int count, double spreadHorizontal,
                                                    double spreadVertical, double speed) {
        int flags = (int) Math.max(0L, Math.min(MAX_FLAGS, Math.round(speed * BURST_SPEED_SCALE)));
        return new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.BURST, particle, x, y, z, (float) spreadHorizontal, (float) spreadVertical,
            Math.max(1, Math.min(MAX_BURST_COUNT, count)), flags);
    }

    public static double burstSpeed(ClientViewMessage.FxEmitter emitter) {
        return (double) emitter.flags() / BURST_SPEED_SCALE;
    }

    public static ClientViewMessage.FxEmitter sound(AcousticsBridge.Playback sound, int intervalTicks) {
        return new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SOUND, sound.soundKey(), sound.x(), sound.y(), sound.z(), sound.volume(),
            sound.pitch(), Math.max(0, Math.min(0xFFFF, intervalTicks)), sound.soundClass().ordinal());
    }

    public static AcousticsProfile.SoundClass soundClass(ClientViewMessage.FxEmitter emitter) {
        AcousticsProfile.SoundClass[] classes = AcousticsProfile.SoundClass.values();
        int index = emitter.flags() & SOUND_CLASS_MASK;
        return index < classes.length ? classes[index] : AcousticsProfile.SoundClass.AMBIENT;
    }

    public static ClientViewMessage.FxEmitter dust(double x, double y, double z, int red, int green, int blue) {
        return dust(x, y, z, ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF), 0);
    }

    private static ClientViewMessage.FxEmitter dust(double x, double y, double z, int rgb, int ticks) {
        return new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "", x, y, z, rgb & 0xFFFFFF, 1.0F, ticks, 1);
    }

    private static int rotation(int points, int window, int cadence) {
        int rounds = Math.max(1, (points + window - 1) / window);
        return Math.min(0xFFFF, rounds * cadence);
    }

    private static int quantize(int channel) {
        return (Math.max(0, Math.min(255, channel)) >> COLOR_LEVELS_SHIFT) * COLOR_LEVEL_SCALE;
    }

    public record Animation(PortalAnimation.Mode mode, Vec3d center, Vec3d size, VisualQualityProfile quality) {
    }

    public record Ambient(AmbientParticleStyle style, int rgb, boolean open, int intervalTicks, int cadenceTicks, Box area,
                          List<double[]> outline) {
    }
}
