package art.arcane.wormholes.network;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;

import org.bukkit.entity.Entity;

@FunctionalInterface
interface TraversalEntityScheduler {

    TraversalEntityScheduler BUKKIT = (Entity entity, Runnable task, Runnable retired, long delayTicks) ->
        FoliaScheduler.runEntity(Wormholes.instance, entity, task, delayTicks, retired);

    boolean schedule(Entity entity, Runnable task, Runnable retired, long delayTicks);
}
