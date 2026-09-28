package art.arcane.wormholes.rules;

import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * One evaluation of a portal's compiled rules. {@code player} is null for non-player travelers, so any
 * player-only condition fails closed. {@code dryRun} marks the route card's evaluation, which reads the same
 * chain but never reserves anything.
 */
public record RuleContext(LocalPortal portal, Entity traveler, Player player, long nowMillis, boolean dryRun,
                          RulesEnvironment environment) implements RuleEvaluation {
    public RuleContext {
        portal = Objects.requireNonNull(portal, "portal");
        traveler = Objects.requireNonNull(traveler, "traveler");
        environment = Objects.requireNonNull(environment, "environment");
    }

    static WarmupTracker.Anchor anchor(Location location) {
        return new WarmupTracker.Anchor(location.getWorld() == null ? null : location.getWorld().getUID().toString(),
            location.getX(), location.getZ());
    }

    @Override
    public UUID travelerId() { return traveler.getUniqueId(); }

    @Override
    public boolean isPlayer() { return player != null; }

    @Override
    public boolean isClass(TravelerClass kind) {
        return switch (kind) {
            case PLAYER -> traveler instanceof Player;
            case MOB -> traveler instanceof Mob;
            case VEHICLE -> traveler instanceof Vehicle;
            case PROJECTILE -> traveler instanceof Projectile;
            case ITEM -> traveler instanceof Item;
            case XP_ORB -> traveler instanceof ExperienceOrb;
        };
    }

    @Override
    public String entityTypeKey() { return environment.entityTypeKey(traveler); }

    @Override
    public boolean portalOpen() { return portal.isOpen(); }

    @Override
    public String dialedAddress() { return environment.dialedAddress(portal); }

    @Override
    public long worldTimeTicks() { return environment.worldTimeTicks(portal); }

    @Override
    public Condition.WeatherKind weather() { return environment.weather(portal); }

    @Override
    public int moonPhase() { return environment.moonPhase(portal); }

    @Override
    public boolean redstonePowered(int dx, int dy, int dz) { return environment.redstonePowered(portal, dx, dy, dz); }

    @Override
    public boolean hasPermission(String node) { return environment.hasPermission(traveler, node); }

    @Override
    public boolean holdsKey(UUID keyId) { return environment.holdsKey(player, keyId); }

    @Override
    public boolean holdsItem(ItemMatcher matcher, Condition.Hand hand) { return environment.holdsItem(player, matcher, hand); }

    @Override
    public int countItems(ItemMatcher matcher) { return environment.countItems(player, matcher); }

    @Override
    public boolean hasAdvancement(String key) { return environment.hasAdvancement(player, key); }

    @Override
    public String placeholder(String expression) { return environment.placeholder(player, expression); }

    @Override
    public long expensiveConditionCacheMillis() { return RulesLimits.config().expensiveConditionCacheMillis; }

    @Override
    public double playerState(Condition.PlayerField field) {
        return switch (field) {
            case HEALTH -> player.getHealth();
            case HUNGER -> player.getFoodLevel();
            case GAMEMODE -> {
                GameMode mode = player.getGameMode();
                yield mode == null ? -1.0D : mode.ordinal();
            }
            case ON_FIRE -> player.getFireTicks() > 0 ? 1.0D : 0.0D;
            case SNEAKING -> player.isSneaking() ? 1.0D : 0.0D;
        };
    }
}
