package art.arcane.wormholes.render.view;

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public interface ProjectionWorldViewProvider {
    ProjectionWorldView view(World world);

    default ProjectionWorldView authoritativeView(World world) {
        return view(world);
    }

    default boolean usesRegionSnapshots() {
        return false;
    }

    default void tick() {
    }

    default void reconfigure() {
    }

    default void close() {
    }

    static ProjectionWorldViewProvider sectionCached(Plugin plugin, ProjectionWorldChangeTracker tracker) {
        return new SectionCachedWorldViewProvider(plugin, tracker);
    }
}
