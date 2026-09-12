package art.arcane.wormholes.rules;

import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * One evaluation of a portal's compiled rules. {@code player} is null for non-player travelers, so any
 * player-only condition fails closed. {@code dryRun} marks the route card's evaluation, which reads the same
 * chain but never reserves anything.
 */
public record RuleContext(LocalPortal portal, Entity traveler, Player player, long nowMillis, boolean dryRun,
                          RulesEnvironment environment) {
    public RuleContext {
        portal = Objects.requireNonNull(portal, "portal");
        traveler = Objects.requireNonNull(traveler, "traveler");
        environment = Objects.requireNonNull(environment, "environment");
    }
}
