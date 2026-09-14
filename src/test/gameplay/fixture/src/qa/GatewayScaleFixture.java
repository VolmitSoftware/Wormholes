package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.access.PortalAdmission;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalPermissionMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class GatewayScaleFixture implements Listener {
    private final TransferFixture plugin;
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    GatewayScaleFixture(TransferFixture plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Identity identity = identities.get(event.getPlayer().getName());
        if (identity != null) {
            applyIdentity(event.getPlayer(), identity);
        }
    }

    void execute(Player sender, String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException("Scale fixture requires an action and player name");
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        switch (args[1]) {
            case "capacity" -> {
                int capacity = Integer.parseInt(args[2]);
                if (capacity < 1 || capacity > 128) {
                    throw new IllegalArgumentException("Scale capacity must be between 1 and 128");
                }
                plugin.getServer().setMaxPlayers(capacity);
                sender.sendMessage("SCALE capacity=" + capacity);
            }
            case "incoming" -> {
                boolean enabled = Boolean.parseBoolean(args[2]);
                portal().setIncomingTraversalsEnabled(enabled);
                portal().save();
                sender.sendMessage("SCALE incoming=" + enabled);
            }
            case "mode" -> {
                PortalPermissionMode mode = PortalPermissionMode.valueOf(args[2].toUpperCase(Locale.ROOT));
                portal().setPermissionMode(mode);
                portal().save();
                sender.sendMessage("SCALE mode=" + mode);
            }
            case "identity" -> {
                if (args.length != 4) {
                    throw new IllegalArgumentException("Scale identity requires a mode");
                }
                Identity identity = Identity.valueOf(args[3].toUpperCase(Locale.ROOT));
                identities.put(args[2], identity);
                UUID playerId = target == null ? offlinePlayerId(args[2]) : target.getUniqueId();
                applyRole(playerId, identity);
                if (target == null) {
                    OfflinePlayer offline = plugin.getServer().getOfflinePlayer(playerId);
                    offline.setOp(identity == Identity.OP);
                    sender.sendMessage("SCALE identity username=" + args[2] + " mode=" + identity
                        + " online=false op=" + offline.isOp());
                } else {
                    target.getScheduler().run(plugin, task -> {
                        applyIdentity(target, identity);
                        sender.sendMessage("SCALE identity username=" + args[2] + " mode=" + identity + " online=true");
                    }, null);
                }
            }
            case "stage" -> {
                if (target == null || args.length != 4) {
                    throw new IllegalArgumentException("Scale stage requires an online player and lane");
                }
                int lane = Integer.parseInt(args[3]);
                if (lane < -1 || lane > 63) {
                    throw new IllegalArgumentException("Scale lane must be between -1 and 63");
                }
                target.getScheduler().run(plugin, task -> stage(sender, target, lane), null);
            }
            case "probe" -> {
                if (target == null) {
                    UUID playerId = offlinePlayerId(args[2]);
                    sender.sendMessage("SCALE probe username=" + args[2] + " online=false op="
                        + plugin.getServer().getOfflinePlayer(playerId).isOp()
                        + " role=" + portal().extension(AccessPortalExtension.class).role(playerId)
                        + " configuredMode=" + identities.get(args[2]));
                } else {
                    target.getScheduler().run(plugin, task -> probe(sender, target), null);
                }
            }
            default -> throw new IllegalArgumentException("Unknown scale fixture action");
        }
    }

    private static UUID offlinePlayerId(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void applyIdentity(Player player, Identity identity) {
        player.setOp(identity == Identity.OP);
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null && previous.getPermissible() == player) {
            player.removeAttachment(previous);
        }
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("*", identity == Identity.WILDCARD);
        attachments.put(player.getUniqueId(), attachment);
        applyRole(player.getUniqueId(), identity);
        attachment.setPermission(portal().extension(AccessPortalExtension.class).permissionNode(),
            identity != Identity.ORDINARY && identity != Identity.TRUSTED);
    }

    private void applyRole(UUID playerId, Identity identity) {
        AccessPortalExtension access = portal().extension(AccessPortalExtension.class);
        if (identity == Identity.ORDINARY) {
            access.removeRole(playerId);
        } else if (identity == Identity.TRUSTED) {
            access.setRole(playerId, PortalRole.USER);
        } else {
            access.setRole(playerId, PortalRole.DENIED);
        }
    }

    private void stage(Player sender, Player target, int lane) {
        double x = lane == -1 ? 4.5D : 7.45D + lane % 3 * 1.05D;
        double z = lane == -1 ? 12.5D : 12.25D + lane / 3 * 0.12D;
        target.teleportAsync(new Location(target.getWorld(), x, 101, z, 180, 0))
            .thenAccept(done -> target.getScheduler().run(plugin, task -> {
                target.setVelocity(new Vector());
                target.setFallDistance(0);
                sender.sendMessage("SCALE staged username=" + target.getName() + " success=" + done);
            }, null));
    }

    private void probe(Player sender, Player target) {
        LocalPortal portal = portal();
        Location location = target.getLocation();
        sender.sendMessage("SCALE probe username=" + target.getName() + " online=true server="
            + Wormholes.networkManager.getLocalName() + " uuid=" + target.getUniqueId()
            + " op=" + target.isOp() + " wildcard=" + target.hasPermission("*")
            + " role=" + portal.extension(AccessPortalExtension.class).role(target.getUniqueId())
            + " allowed=" + PortalAdmission.allows(portal, target)
            + " transferred=" + target.isTransferred()
            + " nodeGranted=" + target.hasPermission(portal.extension(AccessPortalExtension.class).permissionNode())
            + " roleWhitelist=" + portal.extension(AccessPortalExtension.class).whitelistOnly()
            + " permissionMode=" + portal.getPermissionMode()
            + " incoming=" + portal.isIncomingTraversalsEnabled()
            + " x=" + location.getX() + " y=" + location.getY() + " z=" + location.getZ());
    }

    private LocalPortal portal() {
        LocalPortal portal = (LocalPortal) Wormholes.portalManager.getLocalPortal(plugin.portalId());
        if (portal == null) {
            throw new IllegalStateException("Create the transfer fixture before configuring scale players");
        }
        return portal;
    }

    private enum Identity {
        ORDINARY,
        OP,
        WILDCARD,
        TRUSTED,
        DENIED
    }
}
