package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AccessConfig;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MinecraftAccessServiceTest {
    private MinecraftAccessService access;
    private ServerPlayer player;
    private CommandSourceStack source;

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Before
    public void setUp() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(settings);
        AccessConfig config = new AccessConfig();
        config.portalLimitDefault = 2;
        when(settings.getAccess()).thenReturn(config);
        when(runtime.portals()).thenReturn(portals);
        when(portals.snapshot()).thenReturn(List.of());
        player = mock(ServerPlayer.class);
        source = mock(CommandSourceStack.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.createCommandSourceStack()).thenReturn(source);
        when(source.getEntity()).thenReturn(player);
        when(source.permissions()).thenReturn(PermissionSet.NO_PERMISSIONS);
        access = new MinecraftAccessService(runtime);
    }

    @Test
    public void providerDenialsOverrideDefaultsAndParentGrants() throws Exception {
        AutoCloseable provider = access.register((actor, node) -> switch (node) {
            case "wormholes.admin", "wormholes.portals" -> MinecraftAccessService.Decision.ALLOW;
            case "wormholes.admin.network", "wormholes.atlas" -> MinecraftAccessService.Decision.DENY;
            default -> MinecraftAccessService.Decision.UNSET;
        });
        assertTrue(access.permission(player, "wormholes.admin.access"));
        assertTrue(access.permission(player, "wormholes.portals.portal"));
        assertFalse(access.permission(player, "wormholes.admin.network"));
        assertFalse(access.permission(player, "wormholes.atlas"));
        provider.close();
        assertFalse(access.permission(player, "wormholes.admin.access"));
        assertTrue(access.permission(player, "wormholes.atlas"));
    }

    @Test
    public void nativeAtomsUseTheWormholesNamespace() {
        Permission.Atom allowed = Permission.Atom.create(Identifier.fromNamespaceAndPath("wormholes", "portal.station"));
        when(source.permissions()).thenReturn(allowed::equals);
        assertTrue(access.permission(player, "wormholes.portal.station"));
        assertFalse(access.permission(player, "wormholes.portal.other"));
        assertFalse(access.permission(player, "Invalid node"));
    }

    @Test
    public void onlyEffectiveGrantedNodesContributeToTheLimit() {
        access.register(new MinecraftAccessService.PermissionProvider() {
            @Override
            public MinecraftAccessService.Decision permission(ServerPlayer actor, String node) {
                return switch (node) {
                    case "wormholes.limit.8" -> MinecraftAccessService.Decision.ALLOW;
                    case "wormholes.limit.100" -> MinecraftAccessService.Decision.DENY;
                    default -> MinecraftAccessService.Decision.UNSET;
                };
            }

            @Override
            public Collection<String> grantedNodes(ServerPlayer actor) {
                return List.of("wormholes.limit.8", "wormholes.limit.100", "wormholes.limit.invalid");
            }
        });
        assertEquals(8, access.maximum(player));
        access.close();
        assertEquals(2, access.maximum(player));
    }
}
