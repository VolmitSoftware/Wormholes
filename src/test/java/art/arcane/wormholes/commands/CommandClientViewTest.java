package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.ClientViewConfig;
import art.arcane.wormholes.render.client.session.ClientViewOptions;
import art.arcane.wormholes.render.client.session.ClientViewSessionRegistry;
import art.arcane.wormholes.render.clientview.BukkitClientView;
import art.arcane.wormholes.render.clientview.ClientViewObserver;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class CommandClientViewTest {
    private ProjectionManager previousManager;
    private BukkitClientView clientView;
    private ClientViewSessionRegistry<ClientViewObserver, BlockData> registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousManager = Wormholes.projectionManager;
        ProjectionManager projections = mock(ProjectionManager.class);
        clientView = mock(BukkitClientView.class);
        registry = mock(ClientViewSessionRegistry.class);
        ClientViewConfig config = new ClientViewConfig();
        config.enabled = true;
        when(projections.clientView()).thenReturn(clientView);
        when(clientView.registry()).thenReturn(registry);
        when(registry.options()).thenReturn(ClientViewOptions.from(config, ClientViewOptions.DEFAULT_INTEREST_GRACE_TICKS));
        when(registry.runtimeEnabled()).thenReturn(true);
        when(registry.stats()).thenReturn(List.of());
        Wormholes.projectionManager = projections;
    }

    @AfterEach
    void tearDown() {
        Wormholes.projectionManager = previousManager;
    }

    @Test
    void theCommandTreeCarriesStatusOnOffAndReset() throws NoSuchFieldException {
        assertEquals("clientview", CommandClientView.class.getAnnotation(Director.class).name());
        assertEquals(List.of("off", "on", "reset", "status"), directorMethodNames());
        assertSame(CommandClientView.class, CommandWormholes.class.getDeclaredField("clientview").getType());
        assertEquals("wormholes.admin", CommandClientView.PERMISSION);
    }

    @Test
    void statusPrintsTheSharedCatalogReplies() {
        List<String> messages = new ArrayList<>();

        new CommandClientView().status(sender(Set.of("wormholes.admin"), messages));

        assertEquals(List.of("ClientView runtime on, configured on, 0 sessions", "No player has a ClientView session."), messages);
    }

    @Test
    void onAndOffFlipTheRuntimeSwitchAndReply() {
        List<String> messages = new ArrayList<>();
        CommandSender admin = sender(Set.of("wormholes.admin"), messages);
        CommandClientView command = new CommandClientView();

        command.off(admin);
        command.on(admin);

        verify(clientView).runtimeEnabled(false);
        verify(clientView).runtimeEnabled(true);
        assertEquals(List.of("ClientView is off. Every session returned to vanilla projection.",
            "ClientView is offered again. Configured: on."), messages);
    }

    @Test
    void projectionAdminsWithoutTheAdminNodeAreRefused() {
        List<String> messages = new ArrayList<>();
        CommandSender projectionAdmin = sender(Set.of("wormholes.admin.projection"), messages);
        CommandClientView command = new CommandClientView();

        command.status(projectionAdmin);
        command.on(projectionAdmin);
        command.off(projectionAdmin);
        command.reset(projectionAdmin, "Alex");

        verify(clientView, never()).runtimeEnabled(anyBoolean());
        verify(registry, never()).stats();
        assertEquals(List.of("[Wormholes] You do not have permission.", "[Wormholes] You do not have permission.",
            "[Wormholes] You do not have permission.", "[Wormholes] You do not have permission."), messages);
    }

    private static List<String> directorMethodNames() {
        List<String> names = new ArrayList<>();
        for (Method method : CommandClientView.class.getDeclaredMethods()) {
            Director director = method.getAnnotation(Director.class);
            if (director != null) {
                names.add(director.name());
            }
        }
        return names.stream().sorted().toList();
    }

    private static CommandSender sender(Set<String> permissions, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(CommandClientViewTest.class.getClassLoader(), new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("hasPermission")) {
                    return permissions.contains(String.valueOf(args[0]));
                }
                if (name.equals("sendRichMessage")) {
                    messages.add(PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(String.valueOf(args[0]))));
                    return null;
                }
                if (name.equals("getName")) {
                    return "Console";
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                return null;
            });
    }
}
