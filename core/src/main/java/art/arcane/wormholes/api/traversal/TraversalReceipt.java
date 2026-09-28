package art.arcane.wormholes.api.traversal;

public interface TraversalReceipt {
    static TraversalReceipt of(String label) {
        return new SimpleReceipt(label);
    }

    record SimpleReceipt(String label) implements TraversalReceipt {
        public SimpleReceipt {
            label = TraversalText.sanitize(label);
        }
    }
}
