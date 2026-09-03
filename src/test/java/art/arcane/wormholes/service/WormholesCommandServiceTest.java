package art.arcane.wormholes.service;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.wormholes.commands.CommandWormholes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesCommandServiceTest {
    @Test
    void normalizeHelpArgsKeepsLegacyHelpSpellings() {
        assertArrayEquals(new String[]{"help=1"}, WormholesCommandService.normalizeHelpArgs(new String[]{"help"}));
        assertArrayEquals(new String[]{"help=2"}, WormholesCommandService.normalizeHelpArgs(new String[]{"?", "2"}));
        assertArrayEquals(new String[]{"wand", "help=3"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "help", "3"}));
        assertArrayEquals(new String[]{"wand", "rune=portal"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "rune=portal"}));
    }

    @Test
    void directorTreeIncludesRootAliasAndFlatCommands() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode root = engine.getRoot();

        assertEquals(List.of("wh", "wormhole"), List.copyOf(root.getDescriptor().getAliases()));
        assertNotNull(findChild(root, "wand"));
        assertNotNull(findChild(root, "door"));
        assertNotNull(findChild(root, "reload"));
        assertNotNull(findChild(root, "info"));
        assertNotNull(findChild(root, "debug"));
        assertNotNull(findChild(root, "debugdump"));
        DirectorRuntimeNode network = findChild(root, "network");
        assertNotNull(network);
        assertNotNull(findChild(network, "status"));
        assertNotNull(findChild(network, "doctor"));
        DirectorRuntimeNode admin = findChild(root, "admin");
        assertNotNull(admin);
        assertNotNull(findChild(admin, "freeze"));
        assertNotNull(findChild(admin, "flush"));
        assertNull(findChild(root, "rune"));
        assertNull(findChild(root, "reset"));
    }

    @Test
    void serverImportIsCanonicalAndNetworkImportRemainsAnAlias() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode root = engine.getRoot();
        DirectorRuntimeNode server = findChild(root, "server");
        DirectorRuntimeNode network = findChild(root, "network");

        assertNotNull(server);
        assertNotNull(network);
        assertNotNull(findChild(server, "connect"));
        assertNotNull(findChild(server, "export"));
        assertNotNull(findChild(server, "import"));
        assertNotNull(findChild(server, "list"));
        assertNotNull(findChild(server, "remove"));
        assertNotNull(findChild(network, "import"));
    }

    @Test
    void dimensionalDoorTypeCompletionOffersCanonicalValues() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorSender sender = directorSender();

        List<String> everyType = List.of(
            "type=pair", "type=pair_trapdoor",
            "type=personal", "type=personal_trapdoor",
            "type=public", "type=public_trapdoor");
        assertEquals(
            everyType,
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=")))
        );
        assertEquals(
            everyType,
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=p")))
        );
        assertEquals(
            List.of("type=public", "type=public_trapdoor"),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=pub")))
        );
        assertEquals(
            List.of("type=unknown"),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=unknown")))
        );
        assertThrows(
            DirectorParsingException.class,
            () -> new CommandWormholes.DoorTypeHandler().parse(" ", false)
        );
    }

	@Test
	void publicTabCompletionOffersHelpInfoAndLanguage()
	{
		CommandSender sender = permissionSender(Player.class, Set.of("volmit.language.self", "wormholes.language.self"));
		assertEquals(List.of("help", "info", "language"), WormholesCommandService.publicTabCompletions(sender, new String[] {""}));
		assertEquals(List.of("info"), WormholesCommandService.publicTabCompletions(sender, new String[] {"i"}));
		assertEquals(List.of("language"), WormholesCommandService.publicTabCompletions(sender, new String[] {"lang"}));
		assertEquals(List.of(), WormholesCommandService.publicTabCompletions(sender, new String[] {"network", ""}));
	}

    @Test
    void publicLanguageCompletionRequiresBothSelfPermissions() {
        CommandSender globalOnly = permissionSender(Player.class, Set.of("volmit.language.self"));
        CommandSender pluginOnly = permissionSender(Player.class, Set.of("wormholes.language.self"));
        CommandSender console = permissionSender(Set.of("volmit.language.self", "wormholes.language.self"));

        assertEquals(List.of("help", "info"), WormholesCommandService.publicTabCompletions(globalOnly, new String[]{""}));
        assertEquals(List.of(), WormholesCommandService.publicTabCompletions(pluginOnly, new String[]{"lang"}));
        assertEquals(List.of(), WormholesCommandService.publicTabCompletions(console, new String[]{"lang"}));
    }

    @Test
    void globalLanguageAdministratorKeepsServerLanguageCompletion() {
        CommandSender sender = permissionSender(Set.of("volmit.language.admin"));

        assertEquals(List.of("language"), WormholesCommandService.publicTabCompletions(sender, new String[]{"lang"}));
    }

    @Test
    void diagnosticCompletionUsesItsDedicatedPermissionWithoutAdminAccess() {
        CommandSender sender = permissionSender(Set.of("wormholes.debugdump"));

        assertEquals(List.of("debugdump"), WormholesCommandService.publicTabCompletions(sender, new String[]{"debug"}));
        assertEquals(List.of("help", "info", "debugdump"), WormholesCommandService.publicTabCompletions(sender, new String[]{""}));
        assertEquals(List.of(), WormholesCommandService.publicTabCompletions(permissionSender(Set.of()), new String[]{"debug"}));
    }

	@Test
	void publicExecutionOnlyAllowsHelpAndInfo()
	{
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[0]));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"help"}));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"?"}));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"info"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"reload"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"network", "status"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"info", "extra"}));
	}

	@Test
	void anyAdministrativeLeafOpensDirectorRouting()
	{
		assertTrue(WormholesCommandService.hasAdminCommandAccess(permissionSender(Set.of("wormholes.admin.reload"))));
		assertTrue(WormholesCommandService.hasAdminCommandAccess(permissionSender(Set.of("wormholes.admin.network"))));
		assertTrue(WormholesCommandService.hasAdminCommandAccess(permissionSender(Set.of("wormholes.admin"))));
		assertEquals(false, WormholesCommandService.hasAdminCommandAccess(permissionSender(Set.of())));
		assertEquals(false, WormholesCommandService.hasAdminCommandAccess(null));
	}

	@Test
	void nonAdministratorExecutionCannotReachMutatingDirectorCommands()
	{
		List<String> messages = new ArrayList<>();
		CommandSender sender = commandSender(messages);
		WormholesCommandService service = new WormholesCommandService(null);
		Command command = new Command("wormholes")
		{
			@Override
			public boolean execute(CommandSender sender, String label, String[] args)
			{
				return false;
			}
		};

		assertTrue(service.onCommand(sender, command, "wormholes", new String[] {"reload"}));
		assertTrue(messages.stream().anyMatch(message -> message.contains("do not have permission")));
	}

	private static CommandSender commandSender(List<String> messages)
	{
		return (CommandSender) Proxy.newProxyInstance(
			WormholesCommandServiceTest.class.getClassLoader(),
			new Class<?>[] {CommandSender.class},
			(proxy, method, args) ->
			{
				if(method.getName().equals("getName"))
				{
					return "guest";
				}
				if(method.getName().equals("hasPermission") || method.getName().equals("isPermissionSet") || method.getName().equals("isOp"))
				{
					return false;
				}
				if(method.getName().equals("sendMessage") && args != null)
				{
					for(Object value : args)
					{
						if(value instanceof String message)
						{
							messages.add(message);
						}
						else if(value instanceof Component component)
						{
							messages.add(PlainTextComponentSerializer.plainText().serialize(component));
						}
					}
					return null;
				}
				if(method.getName().equals("sendRichMessage") && args != null && args.length > 0)
				{
					Component component = MiniMessage.miniMessage().deserialize(String.valueOf(args[0]));
					messages.add(PlainTextComponentSerializer.plainText().serialize(component));
					return null;
				}
				Class<?> returnType = method.getReturnType();
				if(returnType == boolean.class)
				{
					return false;
				}
				if(returnType == int.class)
				{
					return 0;
				}
				return null;
			});
	}

	private static CommandSender permissionSender(Set<String> permissions)
	{
		return permissionSender(CommandSender.class, permissions);
	}

	private static CommandSender permissionSender(Class<? extends CommandSender> senderType, Set<String> permissions)
	{
		return (CommandSender) Proxy.newProxyInstance(
				WormholesCommandServiceTest.class.getClassLoader(),
				new Class<?>[] {senderType},
				(proxy, method, arguments) -> switch(method.getName())
				{
					case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
					case "equals" -> proxy == arguments[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "WormholesCommandServiceTestSender";
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

    private static DirectorSender directorSender() {
        return new DirectorSender() {
            @Override
            public String getName() {
                return "tester";
            }

            @Override
            public boolean isPlayer() {
                return false;
            }

            @Override
            public void sendMessage(String message) {
            }
        };
    }

    private DirectorRuntimeNode findChild(DirectorRuntimeNode root, String name) {
        for (DirectorRuntimeNode child : root.getChildren()) {
            if (child.getDescriptor().getName().equals(name)) {
                return child;
            }
        }

        return null;
    }
}
