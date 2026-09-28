package art.arcane.wormholes.modded;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MinecraftAtlasCommandTest {
    @Test
    public void subcommandsParseAndAnythingElseIsAUsageError() {
        assertEquals(MinecraftAtlasCommand.Subcommand.OPEN, MinecraftAtlasCommand.parse(new String[] {}));
        assertEquals(MinecraftAtlasCommand.Subcommand.FAVORITES, MinecraftAtlasCommand.parse(new String[] {"FAVORITES"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.RECENTS, MinecraftAtlasCommand.parse(new String[] {"recents"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.GUIDE, MinecraftAtlasCommand.parse(new String[] {"guide", "Market"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.GUIDE_OFF, MinecraftAtlasCommand.parse(new String[] {"guide", "off"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.USAGE, MinecraftAtlasCommand.parse(new String[] {"guide"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.USAGE, MinecraftAtlasCommand.parse(new String[] {"nonsense"}));
        assertEquals(MinecraftAtlasCommand.Subcommand.USAGE, MinecraftAtlasCommand.parse(new String[] {"favorites", "extra"}));
    }

    @Test
    public void theGuideTargetIsEveryArgumentAfterTheSubcommand() {
        assertEquals("Market Square", MinecraftAtlasCommand.guideTarget(new String[] {"guide", "Market", "Square"}));
        assertEquals("", MinecraftAtlasCommand.guideTarget(new String[] {"guide"}));
    }

    @Test
    public void tabCompletionOffersSubcommandsThenGuideTargets() {
        List<String> names = List.of("Market", "Mines", "Docks");
        assertEquals(List.of("favorites", "recents", "guide"), MinecraftAtlasCommand.completions(new String[] {""}, List.of()));
        assertEquals(List.of("guide"), MinecraftAtlasCommand.completions(new String[] {"GU"}, List.of()));
        assertEquals(List.of("off", "Market", "Mines", "Docks"), MinecraftAtlasCommand.completions(new String[] {"guide", ""}, names));
        assertEquals(List.of("Market", "Mines"), MinecraftAtlasCommand.completions(new String[] {"guide", "m"}, names));
        assertTrue(MinecraftAtlasCommand.completions(new String[] {"guide", "Market", ""}, names).isEmpty());
    }
}
