package qa;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalTypeAccess;
import art.arcane.wormholes.portal.ProjectionMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class NativeMenuFixture {
    static final String USE_PERMISSION = "wormholesfixture.use";
    private static final int FRAME_MIN_X = 16;
    private static final int FRAME_MAX_X = 20;
    private static final int FRAME_Z = 8;
    private static final int FLOOR_Y = 100;
    private static final int FRAME_TOP_Y = 105;

    private final Plugin plugin;
    private final ConcurrentMap<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    NativeMenuFixture(Plugin plugin) {
        this.plugin = plugin;
    }

    void capability(Player player) {
        String version = Wormholes.menus.capabilities().capability(player.getUniqueId())
            .map(capability -> String.valueOf(capability.version()))
            .orElse("none");
        plugin.getLogger().info("Native menu capability for " + player.getName() + ": " + version);
        PortalQueryFixture.reply(player, "menu-native " + version);
    }

    void menu(Player player, String id) {
        UUID portalId = portalId(player, id);
        if (portalId == null) {
            return;
        }
        Wormholes.menus.open(player.getUniqueId(), PortalRoutes.home(portalId));
        PortalQueryFixture.reply(player, "menu " + portalId);
    }

    void create(Player player, String[] args) {
        if (args.length != 3) {
            PortalQueryFixture.reply(player, "error usage: /whtest portal create <name>");
            return;
        }
        String name = args[2];
        UUID portalId = UUID.nameUUIDFromBytes(("native-menu-fixture:" + name).getBytes(StandardCharsets.UTF_8));
        World world = player.getWorld();
        UUID owner = player.getUniqueId();
        FixtureScheduler.region(plugin, world, FRAME_MIN_X >> 4, FRAME_Z >> 4, () -> {
            LocalPortal portal = build(world, portalId, name, owner);
            PortalQueryFixture.reply(player, "portal-create " + portal.getId());
        });
    }

    void revoke(Player player, String[] args) {
        LocalPortal portal = portal(player, args, "revoke");
        if (portal == null) {
            return;
        }
        PermissionAttachment attachment = attachments.computeIfAbsent(player.getUniqueId(), ignored -> player.addAttachment(plugin));
        attachment.setPermission(USE_PERMISSION, true);
        attachment.setPermission(PortalTypeAccess.ADMIN, false);
        UUID stranger = UUID.randomUUID();
        portal.setOwner(stranger);
        portal.save();
        player.setOp(false);
        PortalQueryFixture.reply(player, "portal-revoke " + portal.getId() + " owner=" + stranger + " op=" + player.isOp() + " manage=" + manages(player, portal));
    }

    void restore(Player player, String[] args) {
        LocalPortal portal = portal(player, args, "restore");
        if (portal == null) {
            return;
        }
        portal.setOwner(player.getUniqueId());
        portal.save();
        player.setOp(true);
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
        }
        PortalQueryFixture.reply(player, "portal-restore " + portal.getId() + " op=" + player.isOp() + " manage=" + manages(player, portal));
    }

    private LocalPortal build(World world, UUID portalId, String name, UUID owner) {
        for (int x = FRAME_MIN_X - 1; x <= FRAME_MAX_X + 1; x++) {
            for (int z = FRAME_Z - 2; z <= FRAME_Z + 2; z++) {
                world.getBlockAt(x, FLOOR_Y, z).setType(Material.STONE, false);
                for (int y = FLOOR_Y + 1; y <= FRAME_TOP_Y + 1; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        Set<Block> aperture = new HashSet<>();
        for (int x = FRAME_MIN_X; x <= FRAME_MAX_X; x++) {
            for (int y = FLOOR_Y; y <= FRAME_TOP_Y; y++) {
                Block block = world.getBlockAt(x, y, FRAME_Z);
                if (x == FRAME_MIN_X || x == FRAME_MAX_X || y == FLOOR_Y || y == FRAME_TOP_Y) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    aperture.add(block);
                }
            }
        }
        ILocalPortal existing = Wormholes.portalManager.getLocalPortal(portalId);
        if (existing instanceof LocalPortal portal) {
            portal.setOwner(owner);
            portal.setName(name);
            portal.setProjectionMode(ProjectionMode.OFF);
            portal.save();
            return portal;
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(aperture);
        LocalPortal portal = new LocalPortal(portalId, PortalType.PORTAL, structure);
        portal.setName(name);
        portal.setFrame(Frame.canonical(Face.S));
        portal.setOwner(owner);
        portal.setProjectionMode(ProjectionMode.OFF);
        Wormholes.portalManager.addLocalPortal(portal);
        portal.open();
        portal.save();
        return portal;
    }

    private LocalPortal portal(Player player, String[] args, String action) {
        if (args.length != 3) {
            PortalQueryFixture.reply(player, "error usage: /whtest portal " + action + " <id>");
            return null;
        }
        UUID portalId = portalId(player, args[2]);
        if (portalId == null) {
            return null;
        }
        if (!(Wormholes.portalManager.getLocalPortal(portalId) instanceof LocalPortal portal)) {
            PortalQueryFixture.reply(player, "error unknown portal " + portalId);
            return null;
        }
        return portal;
    }

    private static UUID portalId(Player player, String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            PortalQueryFixture.reply(player, "error invalid portal id " + id);
            return null;
        }
    }

    private static boolean manages(Player player, LocalPortal portal) {
        return portal.canManage(player.getUniqueId(), player.isOp() || player.hasPermission(PortalTypeAccess.ADMIN));
    }
}
