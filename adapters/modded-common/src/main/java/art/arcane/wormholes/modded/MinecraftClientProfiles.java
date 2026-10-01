package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.mixin.ConnectionChannelAccess;
import art.arcane.wormholes.modded.mixin.ServerConnectionAccess;
import art.arcane.wormholes.render.bedrock.BedrockProfile;
import art.arcane.wormholes.render.bedrock.ClientProfiles;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

public final class MinecraftClientProfiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final AttributeKey<String> BRAND = AttributeKey.valueOf("wormholes:client-brand");
    private static final Predicate<ServerPlayer> FLOODGATE = floodgate();
    private static final ClientProfiles<ServerPlayer> PROFILES = new ClientProfiles<>(new ClientProfiles.Options<>(
        FLOODGATE, MinecraftClientProfiles::brand, ServerPlayer::getUUID));

    private MinecraftClientProfiles() { }

    public static BedrockProfile profile(ServerPlayer player) {
        return player.connection == null ? BedrockProfile.JAVA : PROFILES.profile(player);
    }
    public static void forget(ServerPlayer player) { PROFILES.forget(player.getUUID()); }
    public static void clear() { PROFILES.clear(); }
    public static int bedrockViewers() { return PROFILES.bedrockViewers(); }

    public static void brand(Connection connection, String brand) {
        Channel channel = ((ConnectionChannelAccess) connection).wormholesChannel();
        if (channel != null && !Objects.equals(channel.attr(BRAND).getAndSet(brand), brand)) {
            PROFILES.clear();
        }
    }

    public static String brand(Connection connection) {
        Channel channel = connection instanceof ConnectionChannelAccess access ? access.wormholesChannel() : null;
        return channel == null ? null : channel.attr(BRAND).get();
    }

    private static String brand(ServerPlayer player) {
        if (!(player.connection instanceof ServerConnectionAccess listener)) { return null; }
        return brand(listener.wormholesConnection());
    }

    private static Predicate<ServerPlayer> floodgate() {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method instance = api.getMethod("getInstance");
            Method player = api.getMethod("isFloodgatePlayer", UUID.class);
            return new FloodgateDetector(instance, player);
        } catch (ClassNotFoundException absent) {
            return player -> false;
        } catch (ReflectiveOperationException | LinkageError failure) {
            LOGGER.warn("Wormholes could not resolve Floodgate; using client brand and UUID detection", failure);
            return player -> false;
        }
    }

    private static final class FloodgateDetector implements Predicate<ServerPlayer> {
        private final Method instance;
        private final Method player;
        private boolean failed;

        private FloodgateDetector(Method instance, Method player) {
            this.instance = instance;
            this.player = player;
        }

        @Override
        public boolean test(ServerPlayer viewer) {
            if (failed) { return false; }
            try {
                Object api = instance.invoke(null);
                return api != null && Boolean.TRUE.equals(player.invoke(api, viewer.getUUID()));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                failed = true;
                LOGGER.warn("Wormholes Floodgate detection failed; using client brand and UUID detection", failure);
                return false;
            }
        }
    }
}
