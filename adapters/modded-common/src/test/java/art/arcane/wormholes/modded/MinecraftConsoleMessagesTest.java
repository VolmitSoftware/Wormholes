package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Supplier;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftConsoleMessagesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void consoleRulesAndAccessFailuresUseDefaultSnapshotWithoutExtraArguments() throws Exception {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftLocalization localization = mock(MinecraftLocalization.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(runtime.localization()).thenReturn(localization);
        when(runtime.portals()).thenReturn(portals);
        when(runtime.access()).thenReturn(access);
        when(access.permission(any(CommandSourceStack.class), anyString())).thenReturn(true);
        when(source.permissions()).thenReturn(PermissionSet.ALL_PERMISSIONS);
        when(portals.snapshot()).thenReturn(List.of());
        when(localization.snapshot(null)).thenReturn(LocalizationSnapshot.create(LocalizationCandidate.english(
            WormholesMessages.catalog(), PluralSelector.oneOther())));
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        new MinecraftRulesCommands(runtime).register(dispatcher);
        new MinecraftAccessService(runtime).registerCommands(dispatcher);
        new MinecraftOperations(runtime).registerCommands(dispatcher);
        when(localization.text(any(), any(), any())).thenAnswer(invocation -> MinecraftMenuText.text(localization.snapshot(null),
            invocation.getArgument(1), invocation.getArgument(2)));
        doAnswer(invocation -> {
            source.sendSystemMessage(invocation.<Supplier<Component>>getArgument(0).get());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        assertEquals(0, dispatcher.execute("wormholes rules key missing", source));
        assertEquals(0, dispatcher.execute("wormholes rules import example missing", source));
        assertEquals(0, dispatcher.execute("wormholes access key missing example", source));
        assertEquals(0, dispatcher.execute("wormholes admin portals info missing", source));
        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(source, atLeastOnce()).sendSystemMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(message -> message.getString().contains("missing")));
    }
}
