package art.arcane.wormholes.rules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import art.arcane.wormholes.portal.LocalPortal;

/** A rules environment with no Bukkit behind it, so condition tests assert decisions rather than mocks. */
final class FakeRulesEnvironment implements RulesEnvironment {
    private final Set<String> permissions = new HashSet<>();
    private final Set<String> advancements = new HashSet<>();
    private final Map<String, String> placeholders = new HashMap<>();
    private final Set<UUID> heldKeys = new HashSet<>();
    private final Map<String, Integer> ownedItems = new HashMap<>();
    private long worldTimeTicks;
    private Condition.WeatherKind weather = Condition.WeatherKind.CLEAR;
    private int moonPhase;
    private boolean redstonePowered;
    private String entityTypeKey = "minecraft:player";
    private String dialedAddress = "";
    private int placeholderCalls;
    private int advancementCalls;

    FakeRulesEnvironment permission(String node) {
        permissions.add(node);
        return this;
    }

    FakeRulesEnvironment advancement(String key) {
        advancements.add(key);
        return this;
    }

    FakeRulesEnvironment placeholder(String expression, String value) {
        placeholders.put(expression, value);
        return this;
    }

    FakeRulesEnvironment key(UUID keyId) {
        heldKeys.add(keyId);
        return this;
    }

    FakeRulesEnvironment owns(String material, int count) {
        ownedItems.put(material, Integer.valueOf(count));
        return this;
    }

    FakeRulesEnvironment worldTime(long ticks) {
        worldTimeTicks = ticks;
        return this;
    }

    FakeRulesEnvironment weather(Condition.WeatherKind kind) {
        weather = kind;
        return this;
    }

    FakeRulesEnvironment moonPhase(int phase) {
        moonPhase = phase;
        return this;
    }

    FakeRulesEnvironment redstone(boolean powered) {
        redstonePowered = powered;
        return this;
    }

    FakeRulesEnvironment entityType(String key) {
        entityTypeKey = key;
        return this;
    }

    FakeRulesEnvironment dialed(String address) {
        dialedAddress = address;
        return this;
    }

    int placeholderCalls() {
        return placeholderCalls;
    }

    int advancementCalls() {
        return advancementCalls;
    }

    @Override
    public long worldTimeTicks(LocalPortal portal) {
        return worldTimeTicks;
    }

    @Override
    public Condition.WeatherKind weather(LocalPortal portal) {
        return weather;
    }

    @Override
    public int moonPhase(LocalPortal portal) {
        return moonPhase;
    }

    @Override
    public boolean redstonePowered(LocalPortal portal, int dx, int dy, int dz) {
        return redstonePowered;
    }

    @Override
    public boolean hasPermission(Entity traveler, String node) {
        return permissions.contains(node);
    }

    @Override
    public boolean hasAdvancement(Player player, String key) {
        advancementCalls++;
        return advancements.contains(key);
    }

    @Override
    public String placeholder(Player player, String expression) {
        placeholderCalls++;
        return placeholders.getOrDefault(expression, "");
    }

    @Override
    public boolean holdsItem(Player player, ItemMatcher matcher, Condition.Hand hand) {
        return countItems(player, matcher) > 0;
    }

    @Override
    public int countItems(Player player, ItemMatcher matcher) {
        return ownedItems.getOrDefault(matcher.material(), Integer.valueOf(0)).intValue();
    }

    @Override
    public boolean holdsKey(Player player, UUID keyId) {
        return heldKeys.contains(keyId);
    }

    @Override
    public String entityTypeKey(Entity traveler) {
        return entityTypeKey;
    }

    @Override
    public String dialedAddress(LocalPortal portal) {
        return dialedAddress;
    }
}
