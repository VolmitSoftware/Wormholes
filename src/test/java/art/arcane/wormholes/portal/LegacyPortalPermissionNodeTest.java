package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.util.Cuboid;

/**
 * The name-derived node {@code wormholes.portal.<sanitized name>} is a legacy alias for the stable
 * per-portal permission key. {@code [access] legacy-name-node-enabled = false} must stop it being
 * read, not merely warn about it.
 */
public final class LegacyPortalPermissionNodeTest {
    private final WormholesSettings previousSettings = Wormholes.settings;

    @AfterEach
    public void restoreSettings() {
        Wormholes.settings = previousSettings;
    }

    @Test
    public void theNameDerivedNodeIsReadWhileTheLegacyAliasIsEnabled() {
        Wormholes.settings = settings(true);
        LocalPortal portal = whitelistPortal();

        assertFalse(portal.canDepart(traveler()));
        assertFalse(portal.canArrive(traveler()));
    }

    @Test
    public void turningTheLegacyAliasOffStopsTheNameDerivedNodeBeingRead() {
        Wormholes.settings = settings(false);
        LocalPortal portal = whitelistPortal();

        assertTrue(portal.canDepart(traveler()));
        assertTrue(portal.canArrive(traveler()));
    }

    @Test
    public void theNameDerivedNodeIsReadWhenNoSettingsAreLoaded() {
        Wormholes.settings = null;
        LocalPortal portal = whitelistPortal();

        assertFalse(portal.canDepart(traveler()));
        assertFalse(portal.canArrive(traveler()));
    }

    private static WormholesSettings settings(boolean legacyNameNodeEnabled) {
        WormholesSettings settings = new WormholesSettings(new MainConfig(), new ProjectionConfig(),
            new RenderConfig(), new NetworkConfig());
        settings.getAccess().legacyNameNodeEnabled = legacyNameNodeEnabled;
        return settings;
    }

    private static LocalPortal whitelistPortal() {
        PortalStructure structure = new PortalStructure();
        structure.setArea(cuboid());
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setPermissionMode(PortalPermissionMode.WHITELIST);
        return portal;
    }

    private static Cuboid cuboid() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("worldKey", "minecraft:overworld");
        map.put("x1", Integer.valueOf(0));
        map.put("y1", Integer.valueOf(64));
        map.put("z1", Integer.valueOf(0));
        map.put("x2", Integer.valueOf(0));
        map.put("y2", Integer.valueOf(66));
        map.put("z2", Integer.valueOf(2));
        return new Cuboid(map);
    }

    private static Player traveler() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] { Player.class },
            (proxy, method, arguments) -> switch(method.getName()) {
                case "isOp" -> Boolean.FALSE;
                case "hasPermission" -> Boolean.FALSE;
                case "toString" -> "TravelerPlayer";
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == arguments[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
