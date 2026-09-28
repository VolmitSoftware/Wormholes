package art.arcane.wormholes.modded;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

final class MinecraftShortTitles {
    static final int FADE_IN_TICKS = 5;
    static final int STAY_TICKS = 7;
    static final int FADE_OUT_TICKS = 5;
    private static final long FRESH_LOOK_TICKS = 8L;
    private static final long EXPIRY_TICKS = 200L;

    private final LongSupplier ticks;
    private final Map<UUID, Map<UUID, Hold>> holds = new HashMap<>();

    MinecraftShortTitles(LongSupplier ticks) {
        this.ticks = ticks;
    }

    void send(ServerPlayer player, UUID portalId, String legacy) {
        long now = ticks.getAsLong();
        Map<UUID, Hold> portalHolds = holds.computeIfAbsent(portalId, ignored -> new HashMap<>());
        Hold hold = portalHolds.get(player.getUUID());
        boolean fresh = hold == null || now - hold.lastSentTick > FRESH_LOOK_TICKS;
        if (hold == null) {
            hold = new Hold();
            portalHolds.put(player.getUUID(), hold);
        }
        if (fresh) {
            hold.lookStartTick = now;
        }
        hold.lastSentTick = now;
        if (!fresh && now - hold.lookStartTick < FADE_IN_TICKS) {
            return;
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fresh ? FADE_IN_TICKS : 0, STAY_TICKS, FADE_OUT_TICKS));
        player.connection.send(new ClientboundSetSubtitleTextPacket(MinecraftLegacyText.component(legacy)));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
    }

    void expire() {
        long now = ticks.getAsLong();
        Iterator<Map<UUID, Hold>> portals = holds.values().iterator();
        while (portals.hasNext()) {
            Map<UUID, Hold> portalHolds = portals.next();
            portalHolds.values().removeIf(hold -> now - hold.lastSentTick > EXPIRY_TICKS);
            if (portalHolds.isEmpty()) {
                portals.remove();
            }
        }
    }

    void clear() {
        holds.clear();
    }

    private static final class Hold {
        private long lookStartTick;
        private long lastSentTick;
    }
}
