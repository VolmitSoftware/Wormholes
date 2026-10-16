package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalTravelCost;
import art.arcane.wormholes.portal.VanillaTravelCost;
import art.arcane.wormholes.portal.VaultTravelCost;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.wormholes.transit.TransitSubsystem;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class PortalQueryFixture {
    private static final String TAG = "[whqa] ";

    private PortalQueryFixture() {
    }

    static void portal(Player player, String[] args) {
        if (args.length == 2 && args[1].equals("list")) {
            list(player);
            return;
        }
        if (args.length == 4 && args[1].equals("get")) {
            get(player, args[2], args[3]);
            return;
        }
        reply(player, "error usage: /whtest portal list | get <id> <field> | create <name> | revoke <id> | restore <id>");
    }

    static void permission(Player player, String[] args) {
        if (args.length != 2) {
            reply(player, "error usage: /whtest perm <node>");
            return;
        }
        reply(player, "perm " + args[1] + " " + player.hasPermission(args[1]));
    }

    private static void list(Player player) {
        List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
        StringBuilder message = new StringBuilder("portal-list ").append(portals.size());
        for (ILocalPortal portal : portals) {
            message.append(' ').append(portal.getId())
                .append(',').append(portal.getType().name())
                .append(',').append(owner(portal))
                .append(',').append(encode(name(portal)));
        }
        reply(player, message.toString());
    }

    private static void get(Player player, String id, String field) {
        UUID portalId;
        try {
            portalId = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            reply(player, "error invalid portal id " + id);
            return;
        }
        ILocalPortal portal = Wormholes.portalManager.getLocalPortal(portalId);
        if (portal == null) {
            reply(player, "error unknown portal " + id);
            return;
        }
        String value = field(portal, field);
        if (value == null) {
            reply(player, "error unknown field " + field);
            return;
        }
        reply(player, "portal-get " + portalId + " " + field + " " + encode(value));
    }

    private static String field(ILocalPortal portal, String field) {
        return switch (field) {
            case "name" -> name(portal);
            case "mode" -> portal.getType().name();
            case "mirror" -> String.valueOf(portal.isMirrorMode());
            case "owner" -> owner(portal);
            case "facing" -> portal.getDirection() == null ? "none" : portal.getDirection().name();
            case "orientation" -> orientation(portal);
            case "projection" -> portal.getProjectionMode().name().toLowerCase(Locale.ROOT);
            case "render" -> portal.getRenderMode().name();
            case "access" -> portal.getPermissionMode().name();
            case "whitelist" -> String.valueOf(access(portal).whitelistOnly());
            case "listed" -> String.valueOf(access(portal).listed());
            case "cost" -> cost(((LocalPortal) portal).getTravelCost());
            case "cost-amount" -> costAmount(((LocalPortal) portal).getTravelCost());
            case "open" -> String.valueOf(portal.isOpen());
            case "destination" -> portal.getTunnel() == null ? "none" : String.valueOf(portal.getTunnel().getDestinationId());
            default -> null;
        };
    }

    private static String orientation(ILocalPortal portal) {
        TransitPortalExtension transit = ((LocalPortal) portal).extension(TransitPortalExtension.class);
        return transit == null ? "none" : transit.effectiveOrientation(TransitSubsystem.config()).name();
    }

    private static AccessPortalExtension access(ILocalPortal portal) {
        return ((LocalPortal) portal).extension(AccessPortalExtension.class);
    }

    private static String cost(PortalTravelCost cost) {
        if (cost == null) {
            return "none";
        }
        return cost instanceof VanillaTravelCost vanilla ? "vanilla:" + vanilla.getMaterial().name() : cost.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String costAmount(PortalTravelCost cost) {
        return switch (cost) {
            case null -> "0";
            case VanillaTravelCost vanilla -> String.valueOf(vanilla.getQuantity());
            case VaultTravelCost vault -> vault.getPlainAmount();
            default -> "0";
        };
    }

    private static String owner(ILocalPortal portal) {
        UUID owner = ((LocalPortal) portal).getOwner();
        return owner == null ? "none" : owner.toString();
    }

    private static String name(ILocalPortal portal) {
        return portal.getName() == null ? "" : portal.getName();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static void reply(Player player, String message) {
        player.sendMessage(TAG + message);
    }
}
