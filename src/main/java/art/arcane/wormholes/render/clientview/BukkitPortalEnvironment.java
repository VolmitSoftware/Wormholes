package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.environment.WorldEnvironment;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.OpticTransform;
import org.bukkit.World;
import org.bukkit.block.Block;

public final class BukkitPortalEnvironment {
    private static volatile WorldEnvironmentAccess access;

    private BukkitPortalEnvironment() {
    }

    public static ProjectionEnvironment capture(World world, Vec3d eye, OpticTransform transform) {
        WorldEnvironmentAccess current = access;
        if (current == null) {
            current = NativeAdapters.require(WorldEnvironmentAccess.class);
            access = current;
        }
        Block block = world.getBlockAt((int) Math.floor(eye.x()), (int) Math.floor(eye.y()), (int) Math.floor(eye.z()));
        WorldEnvironment environment = current.sample(world, new WorldEnvironmentAccess.Position(eye.x(), eye.y(), eye.z()));
        return convert(environment, transform,
            new ProjectionEnvironment.World(world.getKey().toString(), world.getFullTime(), block.getBiome().getKey().toString(),
                world.getSeaLevel(), block.getLightFromBlocks(), block.getLightFromSky(), environment.dimension().logicalHeight(),
                environment.dimension().hasCeiling(), environment.dimension().ambientLight(), ProjectionEnvironment.EyeMedium.valueOf(environment.eyeMedium().name()), environment.dimension().hasFixedTime()));
    }

    static ProjectionEnvironment convert(WorldEnvironment environment, OpticTransform transform,
                                         ProjectionEnvironment.World world) {
        WorldEnvironment.Sky sourceSky = environment.sky();
        ProjectionEnvironment.Sky sky = new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.valueOf(sourceSky.skybox().name()),
            (float) Math.toRadians(sourceSky.sunAngleDegrees()), (float) Math.toRadians(sourceSky.moonAngleDegrees()),
            (float) Math.toRadians(sourceSky.starAngleDegrees()), sourceSky.starBrightness(), rgba(sourceSky.sunrise()),
            rgb(sourceSky.color()), sourceSky.moonPhase(), sourceSky.rain(), sourceSky.thunder());
        WorldEnvironment.Fog sourceFog = environment.fog();
        ProjectionEnvironment.Fog fog = new ProjectionEnvironment.Fog(rgb(sourceFog.color()), sourceFog.start(), sourceFog.end(),
            sourceFog.skyEnd(), sourceFog.cloudEnd(), rgb(sourceFog.waterColor()), sourceFog.waterStart(), sourceFog.waterEnd());
        WorldEnvironment.Lighting sourceLight = environment.lighting();
        ProjectionEnvironment.Lighting lighting = new ProjectionEnvironment.Lighting(rgb(sourceLight.blockTint()), sourceLight.skyFactor(),
            rgb(sourceLight.skyColor()), rgb(sourceLight.ambient()));
        ProjectionEnvironment.Clouds clouds = new ProjectionEnvironment.Clouds(rgba(environment.clouds().color()), environment.clouds().height());
        WorldEnvironment.Dimension sourceDimension = environment.dimension();
        ProjectionEnvironment.Dimension dimension = new ProjectionEnvironment.Dimension(sourceDimension.minY(), sourceDimension.height(),
            sourceDimension.hasSkyLight(), ProjectionEnvironment.CardinalLighting.valueOf(sourceDimension.cardinalLighting().name()),
            sourceDimension.horizonHeight(), sourceDimension.hasEndFlashes());
        return new ProjectionEnvironment(environment.gameTime(), sky, fog, lighting, clouds, transform, dimension, world);
    }

    private static ProjectionEnvironment.Color rgb(WorldEnvironment.Color color) {
        return new ProjectionEnvironment.Color(color.red(), color.green(), color.blue());
    }

    private static ProjectionEnvironment.ColorAlpha rgba(WorldEnvironment.ColorAlpha color) {
        return new ProjectionEnvironment.ColorAlpha(color.red(), color.green(), color.blue(), color.alpha());
    }
}
