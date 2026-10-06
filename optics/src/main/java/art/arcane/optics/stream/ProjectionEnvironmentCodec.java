package art.arcane.optics.stream;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.math.Face;

public final class ProjectionEnvironmentCodec {
    private ProjectionEnvironmentCodec() {
    }

    public static void write(ClientViewWriter out, ProjectionEnvironment value) throws ClientViewProtocolException {
        out.i64(value.gameTime());
        ProjectionEnvironment.Sky sky = value.sky();
        out.u8(sky.skybox().ordinal());
        out.f32(sky.sunAngle());
        out.f32(sky.moonAngle());
        out.f32(sky.starAngle());
        out.f32(sky.starBrightness());
        rgba(out, sky.sunrise());
        rgb(out, sky.color());
        out.u8(sky.moonPhase());
        out.f32(sky.rain());
        out.f32(sky.thunder());
        ProjectionEnvironment.Fog fog = value.fog();
        rgb(out, fog.color());
        out.f32(fog.start());
        out.f32(fog.end());
        out.f32(fog.skyEnd());
        out.f32(fog.cloudEnd());
        rgb(out, fog.waterColor());
        out.f32(fog.waterStart());
        out.f32(fog.waterEnd());
        ProjectionEnvironment.Lighting light = value.lighting();
        rgb(out, light.blockTint());
        out.f32(light.skyFactor());
        rgb(out, light.skyColor());
        rgb(out, light.ambient());
        rgba(out, value.clouds().color());
        out.f32(value.clouds().height());
        ProjectionEnvironment.Transform transform = value.transform();
        out.u8(transform.xAxis().ordinal());
        out.u8(transform.yAxis().ordinal());
        out.u8(transform.zAxis().ordinal());
        out.f64(transform.translation().x());
        out.f64(transform.translation().y());
        out.f64(transform.translation().z());
        ProjectionEnvironment.Dimension dimension = value.dimension();
        out.i32(dimension.minY());
        out.i32(dimension.height());
        out.u8(dimension.hasSkyLight() ? 1 : 0);
        out.u8(dimension.cardinalLighting().ordinal());
        out.f64(dimension.horizonHeight());
        out.u8(dimension.endFlashes() ? 1 : 0);
        out.string(value.world().dimensionKey());
        out.i64(value.world().clockTime());
        out.string(value.world().biomeKey());
        out.i32(value.world().seaLevel());
        out.u8(value.world().blockLight());
        out.u8(value.world().skyLight());
        out.i32(value.world().logicalHeight());
        out.u8(value.world().hasCeiling() ? 1 : 0);
        out.f32(value.world().ambientLight());
        out.u8(value.world().eyeMedium().ordinal());
        out.u8(value.world().hasFixedTime() ? 1 : 0);
    }

    public static ProjectionEnvironment read(ClientViewReader in) throws ClientViewProtocolException {
        try {
            long gameTime = in.i64();
            ProjectionEnvironment.Sky sky = new ProjectionEnvironment.Sky(enumValue(ProjectionEnvironment.Skybox.values(), in.u8()),
                in.f32(), in.f32(), in.f32(), in.f32(), rgba(in), rgb(in), in.u8(), in.f32(), in.f32());
            ProjectionEnvironment.Fog fog = new ProjectionEnvironment.Fog(rgb(in), in.f32(), in.f32(), in.f32(), in.f32(), rgb(in),
                in.f32(), in.f32());
            ProjectionEnvironment.Lighting lighting = new ProjectionEnvironment.Lighting(rgb(in), in.f32(), rgb(in), rgb(in));
            ProjectionEnvironment.Clouds clouds = new ProjectionEnvironment.Clouds(rgba(in), in.f32());
            ProjectionEnvironment.Transform transform = new ProjectionEnvironment.Transform(enumValue(Face.values(), in.u8()),
                enumValue(Face.values(), in.u8()), enumValue(Face.values(), in.u8()),
                new Vec3(in.f64(), in.f64(), in.f64()));
            ProjectionEnvironment.Dimension dimension = new ProjectionEnvironment.Dimension(in.i32(), in.i32(), bool(in),
                enumValue(ProjectionEnvironment.CardinalLighting.values(), in.u8()), in.f64(), bool(in));
            ProjectionEnvironment.World world = new ProjectionEnvironment.World(in.string(), in.i64(), in.string(), in.i32(), in.u8(), in.u8(), in.i32(), bool(in), in.f32(),
                enumValue(ProjectionEnvironment.EyeMedium.values(), in.u8()), bool(in));
            return new ProjectionEnvironment(gameTime, sky, fog, lighting, clouds, transform, dimension, world);
        } catch (IllegalArgumentException exception) {
            throw new ClientViewProtocolException("Invalid destination environment", exception);
        }
    }

    private static boolean bool(ClientViewReader in) throws ClientViewProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ClientViewProtocolException("Invalid environment boolean");
        }
        return value == 1;
    }

    private static <T> T enumValue(T[] values, int ordinal) throws ClientViewProtocolException {
        if (ordinal >= values.length) {
            throw new ClientViewProtocolException("Invalid environment enum");
        }
        return values[ordinal];
    }

    private static void rgb(ClientViewWriter out, ProjectionEnvironment.Color value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
    }

    private static void rgba(ClientViewWriter out, ProjectionEnvironment.ColorAlpha value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
        out.f32(value.alpha());
    }

    private static ProjectionEnvironment.Color rgb(ClientViewReader in) throws ClientViewProtocolException {
        return new ProjectionEnvironment.Color(in.f32(), in.f32(), in.f32());
    }

    private static ProjectionEnvironment.ColorAlpha rgba(ClientViewReader in) throws ClientViewProtocolException {
        return new ProjectionEnvironment.ColorAlpha(in.f32(), in.f32(), in.f32(), in.f32());
    }
}
