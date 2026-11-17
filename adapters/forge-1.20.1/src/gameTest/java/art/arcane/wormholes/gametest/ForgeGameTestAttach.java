package art.arcane.wormholes.gametest;

import art.arcane.wormholes.forge.WormholesForge;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.WormholesGameTests;
import art.arcane.wormholes.modded.WormholesModRuntime;
import io.netty.util.AttributeKey;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.network.NetworkConstants;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;
import javax.xml.parsers.ParserConfigurationException;

/** Wires the GameTest server run to the production Forge runtime. Inert outside {@code runGameTestServer}. */
@Mod.EventBusSubscriber(modid = "wormholes", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeGameTestAttach {
    private static final long TICK_NANOS = 50_000_000L;
    private static final AttributeKey<String> FML_NETVERSION = AttributeKey.valueOf("fml:netversion");
    private static MinecraftServer attachedServer;
    private static long nextTick;

    private ForgeGameTestAttach() {
    }

    @SubscribeEvent
    public static void aboutToStart(ServerAboutToStartEvent event) {
        if (!ForgeGameTestHooks.isGametestServer()) {
            return;
        }
        // Forge reads the handshake's network version from every play connection; mark the test players' embedded
        // connections as Forge clients that finished the handshake.
        MinecraftGameTestPlayer.configureConnections(connection -> connection.channel().attr(FML_NETVERSION).set(NetworkConstants.NETVERSION));
        String report = System.getProperty("wormholes.gametest.report-file");
        if (report == null || report.isBlank()) {
            return;
        }
        try {
            File file = new File(report);
            file.getParentFile().mkdirs();
            GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(file));
        } catch (ParserConfigurationException failure) {
            throw new IllegalStateException("Could not create the GameTest report", failure);
        }
    }

    // Forge creates the world from server.properties instead of the vanilla test presets; match their game rules.
    @SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        if (!ForgeGameTestHooks.isGametestServer()) {
            return;
        }
        MinecraftServer server = event.getServer();
        server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ForgeGameTestHooks.isGametestServer()) {
            return;
        }
        attach(event.getServer());
        pace();
    }

    // The 1.20.1 GameTestServer runs ticks back to back. Hold it to 20 ticks per second so the runtime's
    // wall-clock cooldowns, warmups and timeouts line up with tick counts as they do on a real server.
    private static void pace() {
        long now = System.nanoTime();
        if (nextTick != 0L && now < nextTick) {
            LockSupport.parkNanos(nextTick - now);
            now = nextTick;
        }
        nextTick = now + TICK_NANOS;
    }

    private static void attach(MinecraftServer server) {
        if (attachedServer == server) {
            return;
        }
        Object mod = ModList.get().getModContainerById("wormholes").map(ModContainer::getMod)
            .orElseThrow(() -> new IllegalStateException("The Wormholes production Forge mod is not loaded"));
        try {
            Field field = WormholesForge.class.getDeclaredField("runtime");
            field.setAccessible(true);
            WormholesGameTests.attach(server, (WormholesModRuntime) field.get(mod));
            attachedServer = server;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not attach GameTests to the production Forge runtime", failure);
        }
    }
}
