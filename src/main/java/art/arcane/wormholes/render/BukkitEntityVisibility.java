package art.arcane.wormholes.render;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BukkitEntityVisibility implements EntityRenderLocalOcclusionArbiter.Host<Player, Entity> {
    private final Controller controller;

    private BukkitEntityVisibility(Controller controller) {
        this.controller = controller;
    }

    public static BukkitEntityVisibility create() {
        return create(new Controller() {
            public void hide(Player observer, Entity entity) { observer.hideEntity(Wormholes.instance, entity); }
            public void show(Player observer, Entity entity) {
                if (entity instanceof Item && !entity.isVisibleByDefault()) {
                    observer.hideEntity(Wormholes.instance, entity);
                } else {
                    observer.showEntity(Wormholes.instance, entity);
                }
            }
        });
    }

    public static BukkitEntityVisibility create(Controller controller) {
        return new BukkitEntityVisibility(controller);
    }

    public UUID id(Player observer) { return observer.getUniqueId(); }
    public boolean online(Player observer) { return observer.isOnline(); }
    public boolean valid(Entity entity) { return entity.isValid() && !entity.isDead(); }
    public void hide(Player observer, Entity entity) { controller.hide(observer, entity); }
    public void show(Player observer, Entity entity) { controller.show(observer, entity); }
    public boolean schedule(Player observer, Runnable retry) {
        return Wormholes.instance != null && FoliaScheduler.runEntity(Wormholes.instance, observer, retry, 1L);
    }
    public void failure(IllegalStateException error) {
        Wormholes plugin = Wormholes.instance;
        Logger logger = plugin == null ? Logger.getLogger("Wormholes") : plugin.getLogger();
        logger.log(Level.WARNING,
            "[spoof] Local entity occlusion crossed an unowned Folia region; this projection will retain its prior visibility state.", error);
    }

    public interface Controller {
        void hide(Player observer, Entity entity);
        void show(Player observer, Entity entity);
    }
}
