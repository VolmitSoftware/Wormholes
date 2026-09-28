package art.arcane.wormholes.modded;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftAtlasServiceTest {
    private static final int FIRST_LOADS = 500;

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
    private Thread serverThread;
    private MinecraftAtlasService service;

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Before
    public void setUp() {
        serverThread = Thread.currentThread();
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(runtime.server()).thenReturn(server);
        when(server.getServerDirectory()).thenReturn(temporary.getRoot().toPath());
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            if (Thread.currentThread() == serverThread) {
                task.run();
            } else {
                serverTasks.add(task);
            }
            return null;
        }).when(server).execute(any(Runnable.class));
        service = new MinecraftAtlasService(runtime);
        service.start();
    }

    @After
    public void tearDown() {
        drain();
        service.close();
    }

    @Test
    public void everyRequestRunsOnceAFirstTimeAtlasLoadFinishesEvenWhenTheLoadWinsTheRace() {
        ServerPlayer player = mock(ServerPlayer.class);
        for (int attempt = 0; attempt < FIRST_LOADS; attempt++) {
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            AtomicInteger runs = new AtomicInteger();
            service.withState(player, state -> runs.incrementAndGet());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runs.get() == 0 && System.nanoTime() < deadline) {
                Runnable task = serverTasks.poll();
                if (task == null) {
                    Thread.onSpinWait();
                } else {
                    task.run();
                }
            }
            drain();
            assertEquals("first-time atlas load " + attempt, 1, runs.get());
        }
    }

    private void drain() {
        Runnable task = serverTasks.poll();
        while (task != null) {
            task.run();
            task = serverTasks.poll();
        }
    }
}
