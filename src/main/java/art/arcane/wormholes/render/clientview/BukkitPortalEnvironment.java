package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import org.bukkit.World;

final class BukkitPortalEnvironment {
    private static volatile WorldEnvironmentAccess access;

    private BukkitPortalEnvironment() {
    }

    static ClientViewEnvironment capture(World world, GeometryVector eye, ClientViewEnvironment.Transform transform) {
        WorldEnvironmentAccess current = access;
        if (current == null) {
            current = NativeAdapters.require(WorldEnvironmentAccess.class);
            access = current;
        }
        return convert(current.sample(world, new WorldEnvironmentAccess.Position(eye.x(), eye.y(), eye.z())), transform);
    }

    static ClientViewEnvironment convert(WorldEnvironment environment, ClientViewEnvironment.Transform transform) {
        WorldEnvironment.Sky sourceSky = environment.sky();
        ClientViewEnvironment.Sky sky = new ClientViewEnvironment.Sky(ClientViewEnvironment.Skybox.valueOf(sourceSky.skybox().name()),
            (float) Math.toRadians(sourceSky.sunAngleDegrees()), (float) Math.toRadians(sourceSky.moonAngleDegrees()),
            (float) Math.toRadians(sourceSky.starAngleDegrees()), sourceSky.starBrightness(), rgba(sourceSky.sunrise()),
            rgb(sourceSky.color()), sourceSky.moonPhase(), sourceSky.rain(), sourceSky.thunder());
        WorldEnvironment.Fog sourceFog = environment.fog();
        ClientViewEnvironment.Fog fog = new ClientViewEnvironment.Fog(rgb(sourceFog.color()), sourceFog.start(), sourceFog.end(),
            sourceFog.skyEnd(), sourceFog.cloudEnd(), rgb(sourceFog.waterColor()), sourceFog.waterStart(), sourceFog.waterEnd());
        WorldEnvironment.Lighting sourceLight = environment.lighting();
        ClientViewEnvironment.Lighting lighting = new ClientViewEnvironment.Lighting(rgb(sourceLight.blockTint()), sourceLight.skyFactor(),
            rgb(sourceLight.skyColor()), rgb(sourceLight.ambient()));
        ClientViewEnvironment.Clouds clouds = new ClientViewEnvironment.Clouds(rgba(environment.clouds().color()), environment.clouds().height());
        WorldEnvironment.Dimension sourceDimension = environment.dimension();
        ClientViewEnvironment.Dimension dimension = new ClientViewEnvironment.Dimension(sourceDimension.minY(), sourceDimension.height(),
            sourceDimension.hasSkyLight(), ClientViewEnvironment.CardinalLighting.valueOf(sourceDimension.cardinalLighting().name()),
            sourceDimension.horizonHeight(), sourceDimension.hasEndFlashes());
        return new ClientViewEnvironment(environment.gameTime(), sky, fog, lighting, clouds, transform, dimension);
    }

    private static ClientViewEnvironment.Color rgb(WorldEnvironment.Color color) {
        return new ClientViewEnvironment.Color(color.red(), color.green(), color.blue());
    }

    private static ClientViewEnvironment.ColorAlpha rgba(WorldEnvironment.ColorAlpha color) {
        return new ClientViewEnvironment.ColorAlpha(color.red(), color.green(), color.blue(), color.alpha());
    }
}
