package art.arcane.wormholes.render.view;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.atmosphere.BiomeRegistryIds;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class RemoteWorldView extends RemoteProjectionView<BlockData, Material, EntityData<?>, Equipment>
    implements ProjectionWorldView, ProjectionEntityView {
    public RemoteWorldView(RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> view, BlockData fallback) {
        super(view, new Options<>(fallback, BlockData::getMaterial, BiomeRegistryIds::id));
    }

    @Override
    public World getWorld() {
        return null;
    }

    @Override
    public boolean isVisibleTo(Player observer, UUID entityId) {
        return WormholesPlatform.isEntityVisible(observer, entityId, true, Wormholes.instance);
    }
}
