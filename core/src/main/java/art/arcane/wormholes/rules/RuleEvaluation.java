package art.arcane.wormholes.rules;

import java.util.UUID;

public interface RuleEvaluation {
    UUID travelerId();

    boolean isPlayer();

    boolean isClass(TravelerClass kind);

    String entityTypeKey();

    boolean portalOpen();

    String dialedAddress();

    long worldTimeTicks();

    Condition.WeatherKind weather();

    int moonPhase();

    boolean redstonePowered(int dx, int dy, int dz);

    boolean hasPermission(String node);

    boolean holdsKey(UUID keyId);

    boolean holdsItem(ItemMatcher matcher, Condition.Hand hand);

    int countItems(ItemMatcher matcher);

    boolean hasAdvancement(String key);

    String placeholder(String expression);

    long expensiveConditionCacheMillis();

    long nowMillis();

    double playerState(Condition.PlayerField field);
}
