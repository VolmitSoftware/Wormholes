package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalType;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class MinecraftLookLabelGameTest {
    private static final DustParticleOptions PREVIEW_OUTLINE = new DustParticleOptions(0xFFBE2D, 1.1F);

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer connected;
    private final MinecraftPortal portal;
    private final MinecraftPortal destination;
    private final Vec3 front;
    private final List<JsonElement> subtitles = new ArrayList<>();
    private final List<Integer> fades = new ArrayList<>();
    private final boolean previousParticles;
    private int outlineParticles;
    private boolean cleaned;

    private MinecraftLookLabelGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connected = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "LookProbe");
        ServerPlayer player = connected.player();
        BlockPos cell = helper.absolutePos(new BlockPos(4, 2, 8));
        portal = runtime.portals().create(player.getUUID(), helper.getLevel(),
            List.of(cell, cell.east(), cell.above(), cell.east().above()), PortalType.PORTAL, new Vec3(0, 0, 1));
        BlockPos remote = helper.absolutePos(new BlockPos(12, 2, 8));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(),
            List.of(remote, remote.above()), PortalType.PORTAL, new Vec3(0, 0, 1));
        helper.assertTrue(runtime.portals().link(player, portal.getId(), destination.getId()), "Look label portal did not link");
        front = new Vec3(cell.getX() + 1.0D, cell.getY(), cell.getZ() - 3.0D);
        previousParticles = runtime.configuration().settings().getMain().enableParticles;
        runtime.configuration().settings().getMain().enableParticles = true;
        place(front, 0.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalTools.wand());
        drain();
        reset();
    }

    public static void run(GameTestHelper helper) {
        new MinecraftLookLabelGameTest(helper).start();
    }

    private void start() {
        runtime.schedule(this::cleanup, 590);
        ServerPlayer player = connected.player();
        helper.startSequence().thenIdle(10).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.contains(json(MinecraftPortalText.router(runtime, portal, false))),
                "Wand holder looking at the portal did not receive the router subtitle");
            helper.assertTrue(!fades.isEmpty() && fades.getFirst() == MinecraftShortTitles.FADE_IN_TICKS,
                "Fresh look label did not fade in");
            helper.assertTrue(outlineParticles > 0, "Wand holder did not receive the portal tool preview outline");
            place(front, 180.0F);
        }).thenIdle(4).thenExecute(this::settle).thenIdle(12).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.isEmpty(), "Wand holder looking away still received a look label");
            place(front.add(0.0D, 0.0D, -6.0D), 0.0F);
        }).thenIdle(4).thenExecute(this::settle).thenIdle(12).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.isEmpty(), "Wand holder beyond eight blocks still received a look label");
            place(front, 0.0F);
            runtime.menus().uiChangeDirection(player, portal);
        }).thenIdle(4).thenExecute(this::settle).thenIdle(12).thenExecute(() -> {
            drain();
            helper.assertTrue(!subtitles.contains(json(MinecraftPortalText.router(runtime, portal, false))),
                "Wand holder choosing the portal direction still received the router subtitle");
            MinecraftPortalMenus.directionInput(player, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }).thenIdle(4).thenExecute(this::settle).thenIdle(50).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.isEmpty(), "Viewer without a tool received a look label while the public label is off");
            portal.setPublicLookLabel(true);
        }).thenIdle(46).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.contains(json("§6§l" + portal.getName())),
                "Viewer without a tool did not receive the gold public label");
            helper.assertTrue(!subtitles.contains(json(MinecraftPortalText.router(runtime, portal, false))),
                "Viewer without a tool received the wand router subtitle");
            helper.assertTrue(outlineParticles == 0, "Viewer without a tool received the portal tool preview");
            place(front, 180.0F);
        }).thenIdle(4).thenExecute(this::settle).thenIdle(12).thenExecute(() -> {
            drain();
            helper.assertTrue(subtitles.isEmpty(), "Viewer looking away still received the public label");
            LoggerFactory.getLogger("WormholesGameTest").info(
                "WORMHOLES_GAME_TEST_PASS look_label_runtime wand_router tool_preview look_away out_of_range direction_prompt public_label_off public_label_on");
            cleanup();
        }).thenSucceed();
    }

    private void place(Vec3 position, float yaw) {
        ServerPlayer player = connected.player();
        player.setPos(position);
        player.setYRot(yaw);
        player.setXRot(0.0F);
    }

    private void settle() {
        drain();
        reset();
    }

    private JsonElement json(String legacy) {
        return json(MinecraftLegacyText.component(legacy));
    }

    private JsonElement json(Component component) {
        return ComponentSerialization.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, runtime.server().registryAccess()), component)
            .getOrThrow();
    }

    private void drain() {
        connected.channel().runPendingTasks();
        Object raw;
        while ((raw = connected.channel().readOutbound()) != null) {
            try {
                Object packet = HiddenByteBuf.unpack(raw);
                if (packet instanceof ByteBuf bytes) {
                    packet = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess()))
                        .codec().decode(bytes.duplicate());
                }
                if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
                    subtitles.add(json(subtitle.text()));
                } else if (packet instanceof ClientboundSetTitlesAnimationPacket animation) {
                    fades.add(animation.getFadeIn());
                } else if (packet instanceof ClientboundLevelParticlesPacket particle && particle.getParticle() instanceof DustParticleOptions dust
                    && dust.getColor().equals(PREVIEW_OUTLINE.getColor()) && dust.getScale() == PREVIEW_OUTLINE.getScale()) {
                    outlineParticles++;
                }
            } finally {
                ReferenceCountUtil.release(raw);
            }
        }
    }

    private void reset() {
        subtitles.clear();
        fades.clear();
        outlineParticles = 0;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        runtime.configuration().settings().getMain().enableParticles = previousParticles;
        runtime.portals().remove(portal.getId());
        runtime.portals().remove(destination.getId());
        connected.close();
    }
}
