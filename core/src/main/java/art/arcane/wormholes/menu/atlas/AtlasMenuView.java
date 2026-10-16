package art.arcane.wormholes.menu.atlas;

import java.util.List;
import java.util.Objects;

import art.arcane.wormholes.atlas.AtlasModel;
import art.arcane.wormholes.atlas.AtlasPlayerState;

public record AtlasMenuView(AtlasPlayerState state, List<AtlasModel.Row> candidates) {
    public AtlasMenuView {
        Objects.requireNonNull(state, "state");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
}
