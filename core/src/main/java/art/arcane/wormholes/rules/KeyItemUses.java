package art.arcane.wormholes.rules;

import java.util.UUID;

public final class KeyItemUses {
    private KeyItemUses() {
    }

    public static <I> boolean spend(Host<I> host, I stack, int uses) {
        int remaining = host.remaining(stack);
        if (remaining <= 0) {
            return true;
        }
        if (remaining < uses) {
            return false;
        }
        int left = remaining - uses;
        if (left <= 0) {
            host.remove(stack);
            return true;
        }
        host.setRemaining(stack, left);
        return true;
    }

    public static <I> void refund(Host<I> host, UUID identity, int uses) {
        I existing = host.find(identity);
        if (existing == null) {
            host.give(identity, uses);
            return;
        }
        int remaining = host.remaining(existing);
        if (remaining <= 0) {
            return;
        }
        host.setRemaining(existing, remaining + uses);
    }

    public interface Host<I> {
        I find(UUID identity);
        int remaining(I stack);
        void setRemaining(I stack, int remaining);
        void remove(I stack);
        void give(UUID identity, int uses);
    }
}
