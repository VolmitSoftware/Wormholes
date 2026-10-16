package art.arcane.wormholes.menu.door;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorOpenState;
import art.arcane.wormholes.door.DoorProjectionState;

public sealed interface DoorAccessEdit permits DoorAccessEdit.OpenState, DoorAccessEdit.Projection, DoorAccessEdit.AddPlayer,
    DoorAccessEdit.PlayerState, DoorAccessEdit.RemovePlayer {
    record OpenState(DoorOpenState state) implements DoorAccessEdit {
        public OpenState {
            Objects.requireNonNull(state, "state");
        }
    }

    record Projection(DoorProjectionState state) implements DoorAccessEdit {
        public Projection {
            Objects.requireNonNull(state, "state");
        }
    }

    record AddPlayer(UUID playerId) implements DoorAccessEdit {
        public AddPlayer {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    record PlayerState(UUID playerId, DoorAccessState state) implements DoorAccessEdit {
        public PlayerState {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(state, "state");
        }
    }

    record RemovePlayer(UUID playerId) implements DoorAccessEdit {
        public RemovePlayer {
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
