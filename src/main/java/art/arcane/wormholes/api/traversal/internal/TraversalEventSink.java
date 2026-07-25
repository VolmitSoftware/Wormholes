package art.arcane.wormholes.api.traversal.internal;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

public interface TraversalEventSink {
    TraversalEventSink NONE = new TraversalEventSink() {
        @Override
        public void fireImmediate(Event event) {
        }

        @Override
        public void fireOnEntity(Player traveler, Event event) {
        }
    };

    void fireImmediate(Event event);

    void fireOnEntity(Player traveler, Event event);
}
