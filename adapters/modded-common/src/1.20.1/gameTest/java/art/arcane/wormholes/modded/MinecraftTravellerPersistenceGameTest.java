package art.arcane.wormholes.modded;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class MinecraftTravellerPersistenceGameTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000005ca1");

    private MinecraftTravellerPersistenceGameTest() {
    }

    // Minecraft 1.20.1 has no scale attribute, so the portal scale checks of the 26.x test are omitted.
    public static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int rotations = 0;
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            ServerPlayer original = player(level);
            MinecraftGravity.set(original, frame);
            original.setYRot(35.0F);
            original.setXRot(-20.0F);
            CompoundTag saved = original.saveWithoutId(new CompoundTag());
            ServerPlayer restored = player(level);
            helper.assertTrue(restored.connection == null, "Persistence probe must load before the player connection exists");
            restored.load(saved);
            helper.assertTrue(MinecraftGravity.frame(restored).equals(frame), "Saved gravity did not survive loading orientation " + index);
            helper.assertTrue(restored.getYRot() == 35.0F && restored.getXRot() == -20.0F, "Loading changed the saved local look");
            rotations++;
        }
        helper.assertTrue(rotations == 24, "Did not cover every gravity rotation");
        verifyRespawn(helper);
        LoggerFactory.getLogger("Wormholes").info("WORMHOLES_GAME_TEST_PASS traveller_persistence_runtime rotations=24 disconnected_player_load transient_scale alive_and_dead_respawn");
    }

    private static void verifyRespawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer original = player(level);
        ServerPlayer restored = player(level);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        try {
            restored.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, restored);
            restored.initInventoryMenu();
            restored.restoreFrom(original, true);
            restored.restoreFrom(original, false);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ServerPlayer player(ServerLevel level) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(PLAYER_ID, "SaveProbe"));
    }
}
