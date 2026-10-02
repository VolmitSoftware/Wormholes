package art.arcane.wormholes.network.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;

final class ClientViewEnvironmentCodec {
    private ClientViewEnvironmentCodec() {
    }

    static void write(ClientViewWriter out, ClientViewEnvironment value) {
        out.i64(value.gameTime());
        ClientViewEnvironment.Sky sky = value.sky();
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
        ClientViewEnvironment.Fog fog = value.fog();
        rgb(out, fog.color());
        out.f32(fog.start());
        out.f32(fog.end());
        out.f32(fog.skyEnd());
        out.f32(fog.cloudEnd());
        rgb(out, fog.waterColor());
        out.f32(fog.waterStart());
        out.f32(fog.waterEnd());
        ClientViewEnvironment.Lighting light = value.lighting();
        rgb(out, light.blockTint());
        out.f32(light.skyFactor());
        rgb(out, light.skyColor());
        rgb(out, light.ambient());
        rgba(out, value.clouds().color());
        out.f32(value.clouds().height());
        ClientViewEnvironment.Transform transform = value.transform();
        out.u8(transform.xAxis().ordinal());
        out.u8(transform.yAxis().ordinal());
        out.u8(transform.zAxis().ordinal());
        out.f64(transform.translation().x());
        out.f64(transform.translation().y());
        out.f64(transform.translation().z());
        ClientViewEnvironment.Dimension dimension = value.dimension();
        out.i32(dimension.minY());
        out.i32(dimension.height());
        out.u8(dimension.hasSkyLight() ? 1 : 0);
        out.u8(dimension.cardinalLighting().ordinal());
        out.f64(dimension.horizonHeight());
        out.u8(dimension.endFlashes() ? 1 : 0);
    }

    static ClientViewEnvironment read(ClientViewReader in) throws ClientViewProtocolException {
        try {
            long gameTime = in.i64();
            ClientViewEnvironment.Sky sky = new ClientViewEnvironment.Sky(enumValue(ClientViewEnvironment.Skybox.values(), in.u8()),
                in.f32(), in.f32(), in.f32(), in.f32(), rgba(in), rgb(in), in.u8(), in.f32(), in.f32());
            ClientViewEnvironment.Fog fog = new ClientViewEnvironment.Fog(rgb(in), in.f32(), in.f32(), in.f32(), in.f32(), rgb(in),
                in.f32(), in.f32());
            ClientViewEnvironment.Lighting lighting = new ClientViewEnvironment.Lighting(rgb(in), in.f32(), rgb(in), rgb(in));
            ClientViewEnvironment.Clouds clouds = new ClientViewEnvironment.Clouds(rgba(in), in.f32());
            ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(enumValue(Direction.values(), in.u8()),
                enumValue(Direction.values(), in.u8()), enumValue(Direction.values(), in.u8()),
                new GeometryVector(in.f64(), in.f64(), in.f64()));
            ClientViewEnvironment.Dimension dimension = new ClientViewEnvironment.Dimension(in.i32(), in.i32(), bool(in),
                enumValue(ClientViewEnvironment.CardinalLighting.values(), in.u8()), in.f64(), bool(in));
            return new ClientViewEnvironment(gameTime, sky, fog, lighting, clouds, transform, dimension);
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

    private static void rgb(ClientViewWriter out, ClientViewEnvironment.Color value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
    }

    private static void rgba(ClientViewWriter out, ClientViewEnvironment.ColorAlpha value) {
        out.f32(value.red());
        out.f32(value.green());
        out.f32(value.blue());
        out.f32(value.alpha());
    }

    private static ClientViewEnvironment.Color rgb(ClientViewReader in) throws ClientViewProtocolException {
        return new ClientViewEnvironment.Color(in.f32(), in.f32(), in.f32());
    }

    private static ClientViewEnvironment.ColorAlpha rgba(ClientViewReader in) throws ClientViewProtocolException {
        return new ClientViewEnvironment.ColorAlpha(in.f32(), in.f32(), in.f32(), in.f32());
    }
}
