package art.arcane.wormholes.render.view;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.RemoteViewCache;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;

import java.util.List;
import java.util.UUID;

public interface ProjectionEntityView extends ProjectionEntityData<EntityData<?>, Equipment> {
    boolean isVisibleTo(Player observer, UUID entityId);

    default MapView getMapView(UUID entityId) {
        return null;
    }
}
