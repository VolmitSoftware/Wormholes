package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.PocketRules;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PocketSpace;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MinecraftPocketRulesGameTest {
    private final Options options;
    private final ServerLevel level;
    private final DoorStateService state;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<Mob> mobs = new ArrayList<>();
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private Boolean serverPvp;

    private MinecraftPocketRulesGameTest(Options options) {
        this.options = options;
        level = options.player().serverLevel();
        state = options.runtime().doors().state();
    }

    public static CompletableFuture<Boolean> verify(Options options) {
        MinecraftPocketRulesGameTest test = new MinecraftPocketRulesGameTest(options);
        // The 1.20.1 GameTestServer leaves server PVP off (26.x defaults the pvp game rule on), so enable it for the pocket PVP rules.
        test.serverPvp = options.runtime().server().isPvpAllowed();
        options.runtime().server().setPvpAllowed(true);
        test.change(options.pocket().rules().withMobs(false).withPvp(false).withKeepInventory(true).withFixedTime(6000), test::restricted);
        return test.result;
    }

    public static CompletableFuture<Boolean> rescue(Options options) {
        MinecraftPocketRulesGameTest test = new MinecraftPocketRulesGameTest(options);
        try {
            ServerPlayer player = options.player();
            player.hasChangedDimension();
            player.getAbilities().invulnerable = false;
            MinecraftGameTestAccess.clearDamageCooldown(player);
            player.setHealth(player.getMaxHealth());
            player.hurt(player.damageSources().genericKill(), 1000);
            options.helper().assertTrue(player.isAlive() && player.getHealth() == 2.0F,
                "Lethal pocket damage did not retain one heart");
            options.helper().assertTrue(options.runtime().doors().rules().rescuing(player.getUUID()),
                "Lethal pocket damage did not schedule rescue");
            test.awaitRescue(0);
        } catch (RuntimeException exception) {
            test.result.completeExceptionally(exception);
        }
        return test.result;
    }

    private void restricted() {
        assertSpawn(false);
        ServerPlayer victim = player("rules-victim");
        ServerPlayer attacker = player("rules-attacker");
        float health = victim.getHealth();
        boolean hurt = victim.hurt(victim.damageSources().playerAttack(attacker), 1);
        options.helper().assertTrue(!hurt && victim.getHealth() == health, "Disabled pocket PVP damaged a player");
        inventory();
        options.channel().runPendingTasks();
        // 1.20.1 has no world clocks; a stopped day cycle is sent as a negated day time.
        boolean fixed = false;
        for (Object packet : options.channel().outboundMessages()) {
            if (packet instanceof ClientboundSetTimePacket time) {
                fixed |= time.getDayTime() == -6000L;
            }
        }
        options.helper().assertTrue(fixed, "Pocket fixed time did not reach the player connection");
        options.channel().outboundMessages().clear();
        change(options.pocket().rules().withMobs(true).withPvp(true).withKeepInventory(false)
            .withFixedTime(PocketRules.FOLLOW_WORLD_TIME), this::permissive);
    }

    private void permissive() {
        assertSpawn(true);
        ServerPlayer victim = player("rules-victim");
        ServerPlayer attacker = player("rules-attacker");
        float health = victim.getHealth();
        boolean hurt = victim.hurt(victim.damageSources().playerAttack(attacker), 1);
        options.helper().assertTrue(hurt && victim.getHealth() < health, "Enabled pocket PVP did not damage a player");
        options.helper().assertTrue(!options.runtime().doors().rules().keepInventory(victim), "Disabled inventory retention remained enabled");
        options.channel().runPendingTasks();
        boolean restored = false;
        for (Object packet : options.channel().outboundMessages()) {
            if (packet instanceof ClientboundSetTimePacket time) {
                restored |= time.getDayTime() >= 0L;
            }
        }
        options.helper().assertTrue(restored, "Following world time did not restore the running clock");
        restore(null);
    }

    private void inventory() {
        ServerPlayer dead = player("rules-death");
        dead.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        dead.experienceLevel = 7;
        dead.totalExperience = 91;
        dead.experienceProgress = 0.25F;
        dead.die(dead.damageSources().genericKill());
        options.helper().assertTrue(dead.getInventory().getItem(0).getCount() == 3,
            "Pocket death dropped retained inventory");
        ServerPlayer respawned = player("rules-respawn");
        respawned.restoreFrom(dead, false);
        options.helper().assertTrue(respawned.getInventory().getItem(0).is(Items.DIAMOND)
            && respawned.getInventory().getItem(0).getCount() == 3 && respawned.experienceLevel == 7
            && respawned.totalExperience == 91 && respawned.experienceProgress == 0.25F,
            "Pocket respawn did not restore inventory and experience");
    }

    private ServerPlayer player(String name) {
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        channels.add(channel);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player);
        player.initInventoryMenu();
        player.setPos(options.player().position());
        player.hasChangedDimension();
        player.getAbilities().invulnerable = false;
        MinecraftGameTestAccess.clearDamageCooldown(player);
        return player;
    }

    private void assertSpawn(boolean allowed) {
        Mob mob = MinecraftEntityTypes.PIG.create(level);
        options.helper().assertTrue(mob != null, "Could not create pocket spawn probe");
        mob.setPos(options.player().position());
        boolean added = level.addFreshEntity(mob);
        mobs.add(mob);
        options.helper().assertTrue(added == allowed, "Pocket mob spawn rule was not enforced");
    }

    private void change(PocketRules rules, Runnable next) {
        persist(rules).whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                restore(failure);
                return;
            }
            options.runtime().schedule(() -> run(next), 3L);
        }, level.getServer());
    }

    private CompletableFuture<Void> persist(PocketRules rules) {
        return CompletableFuture.runAsync(() -> {
            try {
                state.replacePocket(options.pocket().withRules(rules));
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private void run(Runnable next) {
        try {
            next.run();
        } catch (RuntimeException exception) {
            restore(exception);
        }
    }

    private void restore(Throwable failure) {
        persist(options.pocket().rules()).whenCompleteAsync((ignored, restoreFailure) -> {
            if (serverPvp != null) {
                level.getServer().setPvpAllowed(serverPvp);
            }
            for (Mob mob : mobs) {
                mob.discard();
            }
            for (EmbeddedChannel channel : channels) {
                channel.finishAndReleaseAll();
            }
            Throwable problem = failure == null ? restoreFailure : failure;
            if (problem != null) {
                result.completeExceptionally(problem);
            } else {
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS pocket_rules spawn pvp inventory_respawn fixed_time world_time_restore");
                result.complete(true);
            }
        }, level.getServer());
    }

    private void awaitRescue(int ticks) {
        ServerPlayer player = options.player();
        if (!MinecraftDoorService.isPocketLevel(player.serverLevel())
            && options.runtime().doors().state().getReturnTicket(player.getUUID()).isEmpty()) {
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS pocket_rescue lethal_damage retained_health safe_return ticket_removed");
            result.complete(true);
            return;
        }
        if (ticks >= 400) {
            result.completeExceptionally(new IllegalStateException("Pocket lethal rescue did not complete in 400 ticks"));
            return;
        }
        options.runtime().schedule(() -> awaitRescue(ticks + 1), 1L);
    }

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player,
                          PocketSpace pocket, EmbeddedChannel channel) {
    }
}
