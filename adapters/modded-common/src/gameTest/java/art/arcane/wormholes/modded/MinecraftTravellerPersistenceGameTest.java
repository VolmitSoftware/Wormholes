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
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class MinecraftTravellerPersistenceGameTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000005ca1");

    private MinecraftTravellerPersistenceGameTest() {
    }

    public static void verify(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftScaleAccess scale = MinecraftScaleAccess.scaleAttribute();
        int rotations = 0;
        for (int index = 0; index < 48; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (orientation.reflects()) {
                continue;
            }
            GravityFrame frame = MinecraftGravity.fromIndex(index);
            ServerPlayer original = player(level);
            MinecraftGravity.set(original, frame);
            scale.scale(original, 2.0D);
            original.setYRot(35.0F);
            original.setXRot(-20.0F);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            original.saveWithoutId(output);
            CompoundTag saved = output.buildResult();
            ServerPlayer restored = player(level);
            helper.assertTrue(restored.connection == null, "Persistence probe must load before the player connection exists");
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
            helper.assertTrue(MinecraftGravity.frame(restored).equals(frame), "Saved gravity did not survive loading orientation " + index);
            helper.assertTrue(scale.scale(restored) == 2.0D, "Saved portal scale did not survive player loading");
            helper.assertTrue(restored.getYRot() == 35.0F && restored.getXRot() == -20.0F, "Loading changed the saved local look");
            helper.assertTrue(restored.getAttribute(Attributes.SCALE).getPermanentModifiers().isEmpty(), "Portal scale leaked into permanent attributes");
            saved.remove("wormholesScale");
            ServerPlayer unscaled = player(level);
            unscaled.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
            helper.assertTrue(unscaled.getAttribute(Attributes.SCALE).getValue() == 1.0D, "Vanilla attributes retain portal size without the Wormholes scale record");
            rotations++;
        }
        helper.assertTrue(rotations == 24, "Did not cover every gravity rotation");
        verifyRespawn(helper, scale);
        LoggerFactory.getLogger("Wormholes").info("WORMHOLES_GAME_TEST_PASS traveller_persistence_runtime rotations=24 disconnected_player_load transient_scale alive_and_dead_respawn");
    }

    private static void verifyRespawn(GameTestHelper helper, MinecraftScaleAccess scale) {
        ServerLevel level = helper.getLevel();
        ServerPlayer original = player(level);
        scale.scale(original, 2.0D);
        ServerPlayer restored = player(level);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(restored.getGameProfile(), false);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        try {
            restored.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, restored, cookie);
            restored.initInventoryMenu();
            restored.restoreFrom(original, true);
            helper.assertTrue(scale.scale(restored) == 2.0D, "Alive player recreation lost the portal scale");
            helper.assertTrue(restored.getAttribute(Attributes.SCALE).getPermanentModifiers().isEmpty(), "Alive player recreation made the scale permanent");
            restored.restoreFrom(original, false);
            helper.assertTrue(scale.scale(restored) == 1.0D, "Death respawn retained the portal scale");
            helper.assertTrue(restored.getAttribute(Attributes.SCALE).getPermanentModifiers().isEmpty(), "Death respawn retained permanent portal attributes");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ServerPlayer player(ServerLevel level) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(PLAYER_ID, "SaveProbe"), false);
        return new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
    }
}
