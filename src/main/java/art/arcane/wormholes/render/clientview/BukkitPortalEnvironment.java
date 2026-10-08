package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.frame.OpticTransform;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class BukkitPortalEnvironment {
    private static volatile WorldEnvironmentAccess access;

    private BukkitPortalEnvironment() {
    }

    public static EnvironmentState capture(World world, Vec3d eye, OpticTransform transform) {
        WorldEnvironmentAccess current = access;
        if (current == null) {
            current = NativeAdapters.require(WorldEnvironmentAccess.class);
            access = current;
        }
        Block block = world.getBlockAt((int) Math.floor(eye.x()), (int) Math.floor(eye.y()), (int) Math.floor(eye.z()));
        WorldEnvironment environment = current.sample(world, new WorldEnvironmentAccess.Position(eye.x(), eye.y(), eye.z()));
        return convert(environment, transform,
            new EnvironmentState.World(world.getKey().toString(), world.getFullTime(), block.getBiome().getKey().toString(),
                world.getSeaLevel(), block.getLightFromBlocks(), block.getLightFromSky(), environment.dimension().logicalHeight(),
                environment.dimension().hasCeiling(), environment.dimension().ambientLight(), EnvironmentState.EyeMedium.valueOf(environment.eyeMedium().name()), environment.dimension().hasFixedTime()));
    }

    static EnvironmentState convert(WorldEnvironment environment, OpticTransform transform,
                                         EnvironmentState.World world) {
        WorldEnvironment.Sky sourceSky = environment.sky();
        EnvironmentState.Sky sky = new EnvironmentState.Sky(EnvironmentState.Skybox.valueOf(sourceSky.skybox().name()),
            (float) Math.toRadians(sourceSky.sunAngleDegrees()), (float) Math.toRadians(sourceSky.moonAngleDegrees()),
            (float) Math.toRadians(sourceSky.starAngleDegrees()), sourceSky.starBrightness(), rgba(sourceSky.sunrise()),
            rgb(sourceSky.color()), sourceSky.moonPhase(), sourceSky.rain(), sourceSky.thunder());
        WorldEnvironment.Fog sourceFog = environment.fog();
        EnvironmentState.Fog fog = new EnvironmentState.Fog(rgb(sourceFog.color()), sourceFog.start(), sourceFog.end(),
            sourceFog.skyEnd(), sourceFog.cloudEnd(), rgb(sourceFog.waterColor()), sourceFog.waterStart(), sourceFog.waterEnd());
        WorldEnvironment.Lighting sourceLight = environment.lighting();
        EnvironmentState.Lighting lighting = new EnvironmentState.Lighting(rgb(sourceLight.blockTint()), sourceLight.skyFactor(),
            rgb(sourceLight.skyColor()), rgb(sourceLight.ambient()));
        EnvironmentState.Clouds clouds = new EnvironmentState.Clouds(rgba(environment.clouds().color()), environment.clouds().height());
        WorldEnvironment.Dimension sourceDimension = environment.dimension();
        EnvironmentState.Dimension dimension = new EnvironmentState.Dimension(sourceDimension.minY(), sourceDimension.height(),
            sourceDimension.hasSkyLight(), EnvironmentState.CardinalLighting.valueOf(sourceDimension.cardinalLighting().name()),
            sourceDimension.horizonHeight(), sourceDimension.hasEndFlashes());
        return new EnvironmentState(environment.gameTime(), sky, fog, lighting, clouds, transform, dimension, world, 1.0F);
    }

    private static EnvironmentState.Color rgb(WorldEnvironment.Color color) {
        return new EnvironmentState.Color(color.red(), color.green(), color.blue());
    }

    private static EnvironmentState.ColorAlpha rgba(WorldEnvironment.ColorAlpha color) {
        return new EnvironmentState.ColorAlpha(color.red(), color.green(), color.blue(), color.alpha());
    }
}
