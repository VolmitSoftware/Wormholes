package art.arcane.wormholes.rules;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A price a matched rule charges on departure. Costs are reserved before the built-in portal travel cost
 * runs, committed once the traveler departs, and refunded when the traversal is rejected.
 */
public sealed interface Cost {
    /** Consumes {@code quantity} matching items from the traveler's inventory. */
    record Item(ItemMatcher matcher, int quantity) implements Cost {
    }

    /** Withdraws Vault currency. */
    record Vault(BigDecimal amount) implements Cost {
        public Vault {
            amount = amount == null ? BigDecimal.ZERO : amount;
        }
    }

    /** Takes experience, either whole levels or raw points. */
    record Xp(int amount, boolean levels) implements Cost {
    }

    /** Takes hunger points. */
    record Hunger(int points) implements Cost {
    }

    /** Takes health, never below half a heart. */
    record Health(double points) implements Cost {
    }

    /** Damages a matching item in the traveler's inventory. */
    record Durability(ItemMatcher matcher, int points) implements Cost {
    }

    /** Consumes charges from the portal's pool. */
    record Charge(int count) implements Cost {
    }

    /** Consumes uses from a ticket item the traveler carries. */
    record Ticket(UUID ticketId, int uses) implements Cost {
    }
}
