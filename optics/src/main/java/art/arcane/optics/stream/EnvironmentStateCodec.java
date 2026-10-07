package art.arcane.optics.stream;





import art.arcane.optics.frame.OpticTransform;
public final class EnvironmentStateCodec {
    private EnvironmentStateCodec() {
    }

    public static void write(ViewStreamWriter out, EnvironmentState value) throws ViewStreamProtocolException {
        out.i64(value.gameTime());
        EnvironmentState.Sky sky = value.sky();
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
        EnvironmentState.Fog fog = value.fog();
        rgb(out, fog.color());
        out.f32(fog.start());
        out.f32(fog.end());
        out.f32(fog.skyEnd());
        out.f32(fog.cloudEnd());
        rgb(out, fog.waterColor());
        out.f32(fog.waterStart());
        out.f32(fog.waterEnd());
        EnvironmentState.Lighting light = value.lighting();
        rgb(out, light.blockTint());
        out.f32(light.skyFactor());
        rgb(out, light.skyColor());
        rgb(out, light.ambient());
        rgba(out, value.clouds().color());
        out.f32(value.clouds().height());
        writeTransform(out, value.transform());
        EnvironmentState.Dimension dimension = value.dimension();
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

    public static EnvironmentState read(ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            long gameTime = in.i64();
            EnvironmentState.Sky sky = new EnvironmentState.Sky(enumValue(EnvironmentState.Skybox.values(), in.u8()),
                in.f32(), in.f32(), in.f32(), in.f32(), rgba(in), rgb(in), in.u8(), in.f32(), in.f32());
            EnvironmentState.Fog fog = new EnvironmentState.Fog(rgb(in), in.f32(), in.f32(), in.f32(), in.f32(), rgb(in),
                in.f32(), in.f32());
            EnvironmentState.Lighting lighting = new EnvironmentState.Lighting(rgb(in), in.f32(), rgb(in), rgb(in));
            EnvironmentState.Clouds clouds = new EnvironmentState.Clouds(rgba(in), in.f32());
            OpticTransform transform = readTransform(in);
            EnvironmentState.Dimension dimension = new EnvironmentState.Dimension(in.i32(), in.i32(), bool(in),
                enumValue(EnvironmentState.CardinalLighting.values(), in.u8()), in.f64(), bool(in));
            EnvironmentState.World world = new EnvironmentState.World(in.string(), in.i64(), in.string(), in.i32(), in.u8(), in.u8(), in.i32(), bool(in), in.f32(),
                enumValue(EnvironmentState.EyeMedium.values(), in.u8()), bool(in));
            return new EnvironmentState(gameTime, sky, fog, lighting, clouds, transform, dimension, world);
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

    private static void rgb(ViewStreamWriter out, EnvironmentState.Color value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
    }

    private static void rgba(ViewStreamWriter out, EnvironmentState.ColorAlpha value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
        out.f32(value.alpha());
    }

    private static EnvironmentState.Color rgb(ViewStreamReader in) throws ViewStreamProtocolException {
        return new EnvironmentState.Color(in.f32(), in.f32(), in.f32());
    }

    private static EnvironmentState.ColorAlpha rgba(ViewStreamReader in) throws ViewStreamProtocolException {
        return new EnvironmentState.ColorAlpha(in.f32(), in.f32(), in.f32(), in.f32());
    }
}
