package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.storage.WorldData;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WormholesModRuntimeTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void executesInDueOrderOnServerTicks() {
        MinecraftServer server = server();
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server);
        List<Integer> executed = new ArrayList<>();
        runtime.schedule(() -> executed.add(3), 2L);
        runtime.schedule(() -> executed.add(1), 1L);
        runtime.schedule(() -> executed.add(2), 1L);

        runtime.tick();
        assertEquals(List.of(1, 2), executed);
        runtime.tick();
        assertEquals(List.of(1, 2, 3), executed);
        runtime.stop();
    }

    @Test
    public void defersTasksScheduledFromWithinCurrentTick() {
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server());
        AtomicInteger calls = new AtomicInteger();
        runtime.schedule(() -> runtime.schedule(calls::incrementAndGet, 0L), 0L);
        runtime.tick();
        assertEquals(0, calls.get());
        runtime.tick();
        assertEquals(1, calls.get());
        runtime.stop();
    }

    @Test
    public void stoppingCancelsPendingTasksAcrossRestart() {
        MinecraftServer server = server();
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server);
        AtomicInteger calls = new AtomicInteger();
        assertTrue(runtime.schedule(calls::incrementAndGet, 1L));
        runtime.stop();
        assertFalse(runtime.running());
        runtime.tick();
        assertFalse(runtime.schedule(calls::incrementAndGet, 1L));
        runtime.start(server);
        runtime.tick();
        assertEquals(0, calls.get());
        runtime.stop();
    }

    @Test
    public void refusesWorldOperationsOffServerThread() {
        MinecraftServer server = server();
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server);
        when(server.isSameThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, runtime::tick);
        assertThrows(IllegalStateException.class, runtime::leases);
        assertThrows(IllegalStateException.class, runtime::preSend);
        when(server.isSameThread()).thenReturn(true);
        runtime.stop();
    }

    @Test
    public void acceptsBackgroundSchedulingWithoutRunningThere() throws InterruptedException {
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server());
        List<Thread> executingThreads = new ArrayList<>();
        Thread worker = new Thread(() -> runtime.schedule(() -> executingThreads.add(Thread.currentThread()), 1L));
        worker.start();
        worker.join();
        assertTrue(executingThreads.isEmpty());
        runtime.tick();
        assertEquals(List.of(Thread.currentThread()), executingThreads);
        runtime.stop();
    }

    @Test
    public void reloadsWithoutWaitingForGameplayTicks() throws Exception {
        MinecraftServer server = server();
        BlockingQueue<Runnable> serverTasks = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            serverTasks.add(invocation.getArgument(0, Runnable.class));
            return null;
        }).when(server).execute(any(Runnable.class));
        WormholesModRuntime runtime = new WormholesModRuntime();
        runtime.start(server);
        try {
            Files.writeString(directory.getRoot().toPath().resolve("config/wormholes/wormholes.toml"),
                "schema = 3\nmetrics = false\n");
            CompletableFuture<WormholesSettings> reload = runtime.configuration().reload();
            Runnable apply = serverTasks.poll(5L, TimeUnit.SECONDS);
            assertNotNull(apply);
            apply.run();
            assertFalse(reload.get(5L, TimeUnit.SECONDS).isMetrics());
        } finally {
            runtime.stop();
        }
    }

    @Test
    public void failedStartupReleasesResourcesAndAllowsCorrectedRestart() throws Exception {
        MinecraftServer server = server();
        WormholesModRuntime runtime = new WormholesModRuntime();
        Files.createDirectories(directory.getRoot().toPath().resolve("config/wormholes"));
        Files.writeString(directory.getRoot().toPath().resolve("config/wormholes/wormholes.toml"), "not = [ valid");
        assertThrows(RuntimeException.class, () -> runtime.start(server));
        assertFalse(runtime.running());
        assertFalse(runtime.schedule(() -> { }, 1L));
        Files.writeString(directory.getRoot().toPath().resolve("config/wormholes/wormholes.toml"), "schema = 3\n");
        runtime.start(server);
        assertTrue(runtime.running());
        runtime.stop();
    }

    private MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(server.getRecipeManager()).thenReturn(mock(RecipeManager.class));
        when(server.getWorldData()).thenReturn(mock(WorldData.class));
        when(server.getPort()).thenReturn(25565);
        when(server.getAllLevels()).thenReturn(List.of());
        when(server.isSameThread()).thenReturn(true);
        when(server.getServerDirectory()).thenReturn(directory.getRoot().toPath());
        return server;
    }
}
