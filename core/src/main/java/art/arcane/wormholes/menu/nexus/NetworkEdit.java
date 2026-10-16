package art.arcane.wormholes.menu.nexus;

import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.nexus.DestinationPolicy;
import art.arcane.wormholes.nexus.FrameIo;

public sealed interface NetworkEdit permits NetworkEdit.Join, NetworkEdit.Leave, NetworkEdit.Address, NetworkEdit.Reciprocal,
    NetworkEdit.Policy, NetworkEdit.Redstone {
    record Join(UUID networkId, String address, String label) implements NetworkEdit {
        public Join {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(label, "label");
        }
    }

    record Leave() implements NetworkEdit {
    }

    record Address(String address) implements NetworkEdit {
        public Address {
            Objects.requireNonNull(address, "address");
        }
    }

    record Reciprocal(boolean enabled) implements NetworkEdit {
    }

    record Policy(DestinationPolicy policy) implements NetworkEdit {
        public Policy {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record Redstone(FrameIo frameIo) implements NetworkEdit {
        public Redstone {
            Objects.requireNonNull(frameIo, "frameIo");
        }
    }
}
