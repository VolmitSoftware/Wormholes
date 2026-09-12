package art.arcane.wormholes.rules;

import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.portal.VanillaTravelCost;
import art.arcane.wormholes.portal.VaultTravelCost;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * What a portal tells someone walking up to it: where it goes, what it costs, whether they can pay, and why it
 * would refuse them. Built from a dry run of the same compiled rules the gate uses, so the card and the gate
 * can never disagree.
 */
public final class RouteCardModel {
    private RouteCardModel() {
    }

    /**
     * The pre-entry summary. {@code destination} is empty when the portal is unlinked or unlisted; the price is
     * empty when travel is free; {@code refusalKey} names the message a crossing would be refused with.
     */
    public record RouteCard(String portal, String destination, String price, boolean affordable,
                            long cooldownSeconds, String refusalKey) {
        public enum State {
            READY,
            COOLDOWN,
            REFUSED
        }

        public State state() {
            if (!refusalKey.isEmpty()) {
                return State.REFUSED;
            }
            return cooldownSeconds > 0L ? State.COOLDOWN : State.READY;
        }

        public boolean free() {
            return price.isEmpty();
        }

        public boolean destinationKnown() {
            return !destination.isEmpty();
        }
    }

    public static RouteCard build(LocalPortal portal, Player viewer, CompiledRules.Match dryRun,
                                  long cooldownRemainingMillis, ChargePool charges, boolean listed) {
        return build(portal, viewer, dryRun, cooldownRemainingMillis, charges, listed, System.currentTimeMillis());
    }

    static RouteCard build(LocalPortal portal, Player viewer, CompiledRules.Match dryRun, long cooldownRemainingMillis,
                           ChargePool charges, boolean listed, long nowMillis) {
        List<Cost> costs = dryRun.costs();
        PortalTravelCost builtIn = portal.getTravelCost();
        String price = price(builtIn, costs);
        boolean affordable = affordable(viewer, builtIn, costs, charges);
        String refusalKey = refusalKey(dryRun, costs, charges, nowMillis);
        long cooldownSeconds = cooldownRemainingMillis <= 0L ? 0L : (cooldownRemainingMillis + 999L) / 1000L;
        return new RouteCard(portal.getName(), listed ? destination(portal) : "", price, affordable,
            cooldownSeconds, refusalKey);
    }

    private static String destination(LocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (tunnel == null) {
            return "";
        }
        IPortal destination = tunnel.getDestination();
        if (destination == null || destination.getName() == null) {
            return "";
        }
        return destination.getName();
    }

    private static String price(PortalTravelCost builtIn, List<Cost> costs) {
        StringJoiner joiner = new StringJoiner(" + ");
        String builtInPrice = describe(builtIn);
        if (!builtInPrice.isEmpty()) {
            joiner.add(builtInPrice);
        }
        String rulePrice = RuleCostText.describe(costs);
        if (!rulePrice.isEmpty()) {
            joiner.add(rulePrice);
        }
        return joiner.toString();
    }

    /** The portal's own travel cost as text, empty when travel is free. Reads no player state. */
    public static String builtInPrice(PortalTravelCost builtIn) {
        return describe(builtIn);
    }

    private static String describe(PortalTravelCost builtIn) {
        if (builtIn == null) {
            return "";
        }
        return switch (builtIn.getType()) {
            case VANILLA -> vanilla(builtIn);
            case VAULT -> vault(builtIn);
        };
    }

    private static String vanilla(PortalTravelCost builtIn) {
        VanillaTravelCost cost = (VanillaTravelCost) builtIn;
        return cost.getQuantity() + "x " + cost.getTemplate().getType().name();
    }

    private static String vault(PortalTravelCost builtIn) {
        return ((VaultTravelCost) builtIn).getFormattedAmount();
    }

    private static boolean affordable(Player viewer, PortalTravelCost builtIn, List<Cost> costs, ChargePool charges) {
        if (viewer == null) {
            return false;
        }
        if (builtIn != null && builtIn.status(viewer) != PortalTravelCost.Status.AVAILABLE) {
            return false;
        }
        return RuleCostReservation.canAfford(viewer, withoutCharges(costs), charges);
    }

    /** The charge pool is reported as a refusal, not as something the viewer failed to afford. */
    private static List<Cost> withoutCharges(List<Cost> costs) {
        List<Cost> payable = new ArrayList<>(costs.size());
        for (Cost cost : costs) {
            if (!(cost instanceof Cost.Charge)) {
                payable.add(cost);
            }
        }
        return payable;
    }

    private static String refusalKey(CompiledRules.Match dryRun, List<Cost> costs, ChargePool charges, long nowMillis) {
        if (!dryRun.outcome().allowed()) {
            return dryRun.outcome().reason();
        }
        int chargeCost = 0;
        for (Cost cost : costs) {
            if (cost instanceof Cost.Charge charge) {
                chargeCost += charge.count();
            }
        }
        return charges.canConsume(chargeCost, nowMillis) ? "" : RulesMessages.DENIED_CHARGES.id();
    }
}
