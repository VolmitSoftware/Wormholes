package art.arcane.optics.stream;





import art.arcane.optics.frame.OpticTransform;
public final class ProjectionEnvironmentCodec {
    private ProjectionEnvironmentCodec() {
    }

    public static void write(ViewStreamWriter out, ProjectionEnvironment value) throws ViewStreamProtocolException {
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
        writeTransform(out, value.transform());
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

    public static ProjectionEnvironment read(ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            long gameTime = in.i64();
            ProjectionEnvironment.Sky sky = new ProjectionEnvironment.Sky(enumValue(ProjectionEnvironment.Skybox.values(), in.u8()),
                in.f32(), in.f32(), in.f32(), in.f32(), rgba(in), rgb(in), in.u8(), in.f32(), in.f32());
            ProjectionEnvironment.Fog fog = new ProjectionEnvironment.Fog(rgb(in), in.f32(), in.f32(), in.f32(), in.f32(), rgb(in),
                in.f32(), in.f32());
            ProjectionEnvironment.Lighting lighting = new ProjectionEnvironment.Lighting(rgb(in), in.f32(), rgb(in), rgb(in));
            ProjectionEnvironment.Clouds clouds = new ProjectionEnvironment.Clouds(rgba(in), in.f32());
            OpticTransform transform = readTransform(in);
            ProjectionEnvironment.Dimension dimension = new ProjectionEnvironment.Dimension(in.i32(), in.i32(), bool(in),
                enumValue(ProjectionEnvironment.CardinalLighting.values(), in.u8()), in.f64(), bool(in));
            ProjectionEnvironment.World world = new ProjectionEnvironment.World(in.string(), in.i64(), in.string(), in.i32(), in.u8(), in.u8(), in.i32(), bool(in), in.f32(),
                enumValue(ProjectionEnvironment.EyeMedium.values(), in.u8()), bool(in));
            return new ProjectionEnvironment(gameTime, sky, fog, lighting, clouds, transform, dimension, world);
        } catch (IllegalArgumentException exception) {
            throw new ViewStreamProtocolException("Invalid destination environment", exception);
        }
    }

    public static void writeTransform(ViewStreamWriter out, OpticTransform transform) {
        out.bytes(transform.encode());
    }

    public static OpticTransform readTransform(ViewStreamReader in) throws ViewStreamProtocolException {
        byte[] encoded = in.bytes(OpticTransform.ENCODED_BYTES);
        try {
            return OpticTransform.decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new ViewStreamProtocolException("Invalid environment transform", exception);
        }
    }

    private static boolean bool(ViewStreamReader in) throws ViewStreamProtocolException {
        int value = in.u8();
        if (value > 1) {
            throw new ViewStreamProtocolException("Invalid environment boolean");
        }
        return value == 1;
    }

    private static <T> T enumValue(T[] values, int ordinal) throws ViewStreamProtocolException {
        if (ordinal >= values.length) {
            throw new ViewStreamProtocolException("Invalid environment enum");
        }
        return values[ordinal];
    }

    private static void rgb(ViewStreamWriter out, ProjectionEnvironment.Color value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
    }

    private static void rgba(ViewStreamWriter out, ProjectionEnvironment.ColorAlpha value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
        out.f32(value.alpha());
    }

    private static ProjectionEnvironment.Color rgb(ViewStreamReader in) throws ViewStreamProtocolException {
        return new ProjectionEnvironment.Color(in.f32(), in.f32(), in.f32());
    }

    private static ProjectionEnvironment.ColorAlpha rgba(ViewStreamReader in) throws ViewStreamProtocolException {
        return new ProjectionEnvironment.ColorAlpha(in.f32(), in.f32(), in.f32(), in.f32());
    }
}
