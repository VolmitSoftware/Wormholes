package art.arcane.wormholes.atlas;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.atlas.CommandAtlas.Subcommand;
import art.arcane.wormholes.localization.AtlasMessages;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAtlasTest {
    @Test
    void aPlayerWithoutThePermissionIsToldSoAndNothingElseHappens() {
        CommandSender denied = player(Set.of());

        TextKey refusal = CommandAtlas.refusal(denied, true);

        assertEquals(AtlasMessages.NO_PERMISSION, refusal);
    }

    @Test
    void theConsoleIsToldTheAtlasBelongsToPlayers() {
        CommandSender console = console(Set.of(CommandAtlas.PERMISSION));

        assertEquals(AtlasMessages.ONLY_PLAYERS, CommandAtlas.refusal(console, true));
    }

    @Test
    void turningTheAtlasOffRefusesEvenAPermittedPlayer() {
        CommandSender allowed = player(Set.of(CommandAtlas.PERMISSION));

        assertEquals(AtlasMessages.DISABLED, CommandAtlas.refusal(allowed, false));
        assertNull(CommandAtlas.refusal(allowed, true), "a permitted player on an enabled atlas is not refused");
    }

    @Test
    void subcommandsParseAndAnythingElseIsAUsageError() {
        assertEquals(Subcommand.OPEN, CommandAtlas.parse(new String[] {}));
        assertEquals(Subcommand.FAVORITES, CommandAtlas.parse(new String[] {"favorites"}));
        assertEquals(Subcommand.FAVORITES, CommandAtlas.parse(new String[] {"FAVORITES"}));
        assertEquals(Subcommand.RECENTS, CommandAtlas.parse(new String[] {"recents"}));
        assertEquals(Subcommand.GUIDE, CommandAtlas.parse(new String[] {"guide", "Market"}));
        assertEquals(Subcommand.GUIDE_OFF, CommandAtlas.parse(new String[] {"guide", "off"}));
        assertEquals(Subcommand.USAGE, CommandAtlas.parse(new String[] {"guide"}));
        assertEquals(Subcommand.USAGE, CommandAtlas.parse(new String[] {"nonsense"}));
        assertEquals(Subcommand.USAGE, CommandAtlas.parse(new String[] {"favorites", "extra"}));
    }

    @Test
    void theGuideTargetIsEveryArgumentAfterTheSubcommandJoinedBackTogether() {
        assertEquals("Market Square", CommandAtlas.guideTarget(new String[] {"guide", "Market", "Square"}));
        assertEquals("", CommandAtlas.guideTarget(new String[] {"guide"}));
    }

    @Test
    void tabCompletionOffersTheSubcommandsThenTheGuideOffSwitch() {
        assertEquals(List.of("favorites", "recents", "guide"), CommandAtlas.completions(new String[] {""}, List.of()));
        assertEquals(List.of("favorites"), CommandAtlas.completions(new String[] {"fa"}, List.of()));
        assertEquals(List.of("guide"), CommandAtlas.completions(new String[] {"GU"}, List.of()));
        assertTrue(CommandAtlas.completions(new String[] {"zzz"}, List.of()).isEmpty());
    }

    @Test
    void tabCompletingTheGuideTargetOffersOffAndTheNamesInTheAtlas() {
        List<String> names = List.of("Market", "Mines", "Docks");

        assertEquals(List.of("off", "Market", "Mines", "Docks"),
                CommandAtlas.completions(new String[] {"guide", ""}, names));
        assertEquals(List.of("Market", "Mines"), CommandAtlas.completions(new String[] {"guide", "m"}, names));
        assertEquals(List.of("off"), CommandAtlas.completions(new String[] {"guide", "of"}, names));
        assertTrue(CommandAtlas.completions(new String[] {"guide", "Market", ""}, names).isEmpty());
    }

    private static CommandSender player(Set<String> permissions) {
        return sender(permissions, Player.class);
    }

    private static CommandSender console(Set<String> permissions) {
        return sender(permissions, CommandSender.class);
    }

    private static CommandSender sender(Set<String> permissions, Class<?> type) {
        InvocationHandler handler = (Object proxy, Method method, Object[] arguments) -> switch (method.getName()) {
            case "hasPermission" -> Boolean.valueOf(permissions.contains(String.valueOf(arguments[0])));
            case "isOp" -> Boolean.FALSE;
            case "getName" -> "tester";
            case "equals" -> Boolean.valueOf(proxy == arguments[0]);
            case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
            case "toString" -> "AtlasTestSender";
            default -> null;
        };
        return (CommandSender) Proxy.newProxyInstance(CommandAtlasTest.class.getClassLoader(),
                new Class<?>[] {type}, handler);
    }
}
