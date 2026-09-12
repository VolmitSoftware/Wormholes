package art.arcane.wormholes.rules;

import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Everything a compiled condition needs from the world, the traveler's inventory, the permission stack and
 * PlaceholderAPI. Keeping it behind one interface is what lets the rule tests assert decisions with no server.
 */
public interface RulesEnvironment {
    long worldTimeTicks(LocalPortal portal);

    Condition.WeatherKind weather(LocalPortal portal);

    /** Moon phase 0-7, where 0 is a full moon. */
    int moonPhase(LocalPortal portal);

    boolean redstonePowered(LocalPortal portal, int dx, int dy, int dz);

    boolean hasPermission(Entity traveler, String node);

    boolean hasAdvancement(Player player, String key);

    /** The expanded PlaceholderAPI expression, or an empty string when PlaceholderAPI is absent. */
    String placeholder(Player player, String expression);

    boolean holdsItem(Player player, ItemMatcher matcher, Condition.Hand hand);

    int countItems(Player player, ItemMatcher matcher);

    boolean holdsKey(Player player, UUID keyId);

    /** The traveler's namespaced entity type key, lowercase. */
    String entityTypeKey(Entity traveler);

    /** The address this portal is dialed to, empty when the portal is not part of a dialed network. */
    String dialedAddress(LocalPortal portal);
}
