package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.rules.Condition;
import art.arcane.wormholes.rules.ItemMatcher;
import art.arcane.wormholes.rules.RuleEvaluation;
import art.arcane.wormholes.rules.TravelerClass;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.Objects;
import java.util.UUID;

public final class MinecraftRuleEvaluation implements RuleEvaluation {
    private final Options options;
    private final ServerPlayer player;

    public MinecraftRuleEvaluation(Options options) {
        this.options = Objects.requireNonNull(options);
        player = options.traveler() instanceof ServerPlayer found ? found : null;
        if (player != null) {
            Objects.requireNonNull(options.subject());
        }
    }

    @Override
    public UUID travelerId() {
        return options.traveler().getUUID();
    }

    @Override
    public boolean isPlayer() {
        return player != null;
    }

    @Override
    public boolean isClass(TravelerClass kind) {
        Entity entity = options.traveler();
        return switch (kind) {
            case PLAYER -> entity instanceof ServerPlayer;
            case MOB -> entity instanceof Mob;
            case VEHICLE -> entity instanceof VehicleEntity;
            case PROJECTILE -> entity instanceof Projectile;
            case ITEM -> entity instanceof ItemEntity;
            case XP_ORB -> entity instanceof ExperienceOrb;
        };
    }

    @Override
    public String entityTypeKey() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(options.traveler().getType()).toString();
    }

    @Override
    public boolean portalOpen() {
        return options.portal().isOpen();
    }

    @Override
    public String dialedAddress() {
        return options.dialedAddress();
    }

    @Override
    public long worldTimeTicks() {
        return options.level().getDefaultClockTime();
    }

    @Override
    public Condition.WeatherKind weather() {
        ServerLevel level = options.level();
        return level.isThundering() ? Condition.WeatherKind.THUNDER
            : level.isRaining() ? Condition.WeatherKind.RAIN : Condition.WeatherKind.CLEAR;
    }

    @Override
    public int moonPhase() {
        return (int) Math.floorMod(worldTimeTicks() / 24000L, 8L);
    }

    @Override
    public boolean redstonePowered(int dx, int dy, int dz) {
        Vec3d center = options.portal().getGeometry().getArea().center();
        BlockPos position = BlockPos.containing(center.x() + dx, center.y() + dy, center.z() + dz);
        return options.level().hasNeighborSignal(position);
    }

    @Override
    public boolean hasPermission(String node) {
        return player != null && !node.isEmpty() && options.runtime().access().permission(player, node);
    }

    @Override
    public boolean holdsKey(UUID keyId) {
        return options.subject().find(keyId) != null;
    }

    @Override
    public boolean holdsItem(ItemMatcher matcher, Condition.Hand hand) {
        return switch (hand) {
            case MAIN -> options.subject().matches(player.getMainHandItem(), matcher);
            case OFF -> options.subject().matches(player.getOffhandItem(), matcher);
            case EITHER -> options.subject().matches(player.getMainHandItem(), matcher)
                || options.subject().matches(player.getOffhandItem(), matcher);
        };
    }

    @Override
    public int countItems(ItemMatcher matcher) {
        int count = 0;
        for (ItemStack item : options.subject().inventory()) {
            if (options.subject().matches(item, matcher)) {
                count += item.getCount();
            }
        }
        return count;
    }

    @Override
    public boolean hasAdvancement(String key) {
        Identifier id = Identifier.tryParse(key);
        if (id == null) {
            return false;
        }
        AdvancementHolder advancement = options.runtime().server().getAdvancements().get(id);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    @Override
    public String placeholder(String expression) {
        return options.placeholders().expand(player, expression);
    }

    @Override
    public long expensiveConditionCacheMillis() {
        return options.runtime().configuration().settings().getRules().expensiveConditionCacheMillis;
    }

    @Override
    public long nowMillis() {
        return options.nowMillis();
    }

    @Override
    public double playerState(Condition.PlayerField field) {
        return switch (field) {
            case HEALTH -> player.getHealth();
            case HUNGER -> player.getFoodData().getFoodLevel();
            case GAMEMODE -> {
                GameType mode = player.gameMode();
                yield mode == null ? -1.0D : mode.getId();
            }
            case ON_FIRE -> player.getRemainingFireTicks() > 0 ? 1.0D : 0.0D;
            case SNEAKING -> player.isShiftKeyDown() ? 1.0D : 0.0D;
        };
    }

    public record Options(WormholesModRuntime runtime, MinecraftPortal portal, ServerLevel level, Entity traveler,
                          long nowMillis, String dialedAddress, MinecraftRuleCostSubject subject,
                          Placeholders placeholders) {
        public Options {
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(portal);
            Objects.requireNonNull(level);
            Objects.requireNonNull(traveler);
            Objects.requireNonNull(dialedAddress);
            Objects.requireNonNull(placeholders);
        }
    }

    @FunctionalInterface
    public interface Placeholders {
        String expand(ServerPlayer player, String expression);
    }
}
