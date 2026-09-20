package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.access.AccessPortalExtension;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TransferFixture extends JavaPlugin {
    private final ConcurrentMap<UUID, PermissionAttachment> permissionAttachments = new ConcurrentHashMap<>();
    private final GatewayScaleFixture scaleFixture = new GatewayScaleFixture(this);

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(scaleFixture, this);
        getServer().getPluginManager().registerEvents(new TickTimingFixture(), this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) {
            return true;
        }
        switch (args[0]) {
            case "scale" -> scaleFixture.execute(player, args);
            case "tickstats" -> {
                if (args.length > 1 && args[1].equals("reset")) {
                    TickTimingFixture.reset(player);
                } else {
                    TickTimingFixture.sample(player);
                }
            }
            case "horizontal" -> HorizontalProjectionFixture.execute(this, player, args);
            case "setup", "reentry" -> {
                SetupRequest request = new SetupRequest(player.getWorld(), player.getUniqueId(), player, args[0].equals("reentry"));
                getServer().getRegionScheduler().execute(this, request.world(), 0, 0, () -> setup(request));
            }
            case "stage" -> player.teleportAsync(new Location(player.getWorld(), 8.5, 101, 12.5, 180, 0)).thenAccept(done -> player.sendMessage("FIXTURE staged " + done));
            case "menu" -> ((LocalPortal) Wormholes.portalManager.getLocalPortal(portalId())).uiOpenPortalMenu(player);
            case "where" -> player.sendMessage("FIXTURE server=" + Wormholes.networkManager.getLocalName() + " uuid=" + player.getUniqueId() + " transferred=" + player.isTransferred() + " position=" + player.getLocation().toVector());
            case "stats" -> {
                TraversalService.Stats stats = Wormholes.traversalService.statsSnapshot();
                player.sendMessage("FIXTURE completed=" + stats.completed() + " failed=" + stats.failed() + " inFlight=" + stats.inFlight());
            }
            case "projection" -> {
                ILocalPortal portal = Wormholes.portalManager.getLocalPortal(portalId());
                if (portal != null && args.length == 2) {
                    portal.setProjectionMode(Boolean.parseBoolean(args[1]) ? ProjectionMode.ON : ProjectionMode.OFF);
                    portal.save();
                    player.sendMessage("FIXTURE projection=" + portal.getProjectionMode());
                }
            }
            case "ticks" -> player.sendMessage("FIXTURE tickMillis=" + getServer().getAverageTickTime()
                + " projections=" + WormholesTelemetry.activeProjections()
                + " observers=" + WormholesTelemetry.projectionObservers()
                + " renderMillis=" + WormholesTelemetry.renderMsPerSecond(System.currentTimeMillis())
                + " online=" + getServer().getOnlinePlayers().size()
                + " maxPlayers=" + getServer().getMaxPlayers()
                + " projectorViewers=" + HorizontalProjectionFixture.viewerCount()
                + " aperture=" + Wormholes.portalManager.getLocalPortal(portalId()).getStructure().getBlockPositions().size()
                + " depth=" + Wormholes.portalManager.getLocalPortal(portalId()).getNetworkViewDepth()
                + " frameBudgetMicros=" + Settings.PROJECTION_MAX_FRAME_MICROS
                + " platform=" + getServer().getName());
            case "gate" -> {
                ILocalPortal portal = Wormholes.portalManager.getLocalPortal(portalId());
                player.sendMessage("FIXTURE gate open=" + (portal != null && portal.isOpen())
                    + " valid=" + (portal != null && portal.getTunnel() != null && portal.getTunnel().isValid())
                    + " projection=" + (portal == null ? "missing" : portal.getProjectionMode())
                    + " destination=" + (portal == null || portal.getTunnel() == null ? "missing" : portal.getTunnel().getDestinationId())
                    + " center=" + (portal == null ? "missing" : portal.getStructure().getCenter())
                    + " frame=" + (portal == null || portal.getFrame() == null ? "missing" : portal.getFrame().toJSON())
                    + " capture=" + (portal == null || portal.getStructure().getCaptureZone() == null ? "missing" : portal.getStructure().getCaptureZone().min() + "/" + portal.getStructure().getCaptureZone().max())
                    + " player=" + player.getLocation().toVector());
            }
            case "grant" -> {
                if (args.length == 3) {
                    Player guest = getServer().getPlayerExact(args[1]);
                    ILocalPortal portal = Wormholes.portalManager.getLocalPortal(portalId());
                    if (guest != null && portal != null) {
                        String permission = ((LocalPortal) portal).extension(AccessPortalExtension.class).permissionNode();
                        boolean granted = Boolean.parseBoolean(args[2]);
                        guest.getScheduler().run(this, task -> {
                            PermissionAttachment attachment = permissionAttachments.computeIfAbsent(guest.getUniqueId(), ignored -> guest.addAttachment(this));
                            attachment.setPermission(permission, granted);
                            player.sendMessage("FIXTURE permission=" + permission + " granted=" + granted + " guest=" + guest.getName());
                        }, null);
                    }
                }
            }
            case "export" -> Wormholes.importExportService.exportToChat(player, Wormholes.portalManager.getLocalPortal(portalId()));
            case "import" -> {
                if (args.length == 2) {
                    Wormholes.importExportService.importCode(player, Wormholes.portalManager.getLocalPortal(portalId()), args[1]);
                }
            }
            case "status" -> player.sendMessage("FIXTURE peers=" + Wormholes.networkManager.status() + " portal=" + Wormholes.portalManager.getLocalPortal(portalId()));
            default -> player.sendMessage("FIXTURE unknown command");
        }
        return true;
    }

    private void setup(SetupRequest request) {
        World world = request.world();
        ILocalPortal existing = Wormholes.portalManager.getLocalPortal(portalId());
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                world.getBlockAt(x, 100, z).setType(Material.STONE, false);
                for (int y = 101; y <= 107; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
        for (int x = 6; x <= 10; x++) {
            for (int y = 100; y <= 105; y++) {
                if (x == 6 || x == 10 || y == 100 || y == 105) {
                    world.getBlockAt(x, y, 8).setType(Material.OBSIDIAN, false);
                }
            }
        }
        if (existing == null) {
            Set<Block> aperture = new HashSet<>();
            for (int x = 7; x <= 9; x++) {
                for (int y = 101; y <= 104; y++) {
                    aperture.add(world.getBlockAt(x, y, 8));
                }
            }
            PortalStructure structure = new PortalStructure();
            structure.setBlocks(aperture);
            LocalPortal portal = new LocalPortal(portalId(), request.reentry() ? PortalType.PORTAL : PortalType.GATEWAY, structure);
            portal.setName("Transfer fixture " + Wormholes.networkManager.getLocalName());
            portal.setFrame(PortalFrame.canonical(Direction.S));
            portal.setOwner(request.owner());
            portal.setProjectionMode(ProjectionMode.OFF);
            Wormholes.portalManager.addLocalPortal(portal);
            portal.open();
            portal.save();
        }
        if (request.reentry()) {
            Settings.TELEPORT_COOLDOWN_MILLIS = 0L;
            Set<Block> aperture = new HashSet<>();
            for (int x = 11; x <= 13; x++) {
                for (int y = 101; y <= 104; y++) {
                    aperture.add(world.getBlockAt(x, y, 8));
                }
            }
            PortalStructure structure = new PortalStructure();
            structure.setBlocks(aperture);
            UUID targetId = UUID.nameUUIDFromBytes(("transfer-fixture-reentry:" + Wormholes.networkManager.getLocalName()).getBytes(StandardCharsets.UTF_8));
            LocalPortal target = (LocalPortal) Wormholes.portalManager.getLocalPortal(targetId);
            boolean newTarget = target == null;
            if (newTarget) {
                target = new LocalPortal(targetId, PortalType.PORTAL, structure);
            }
            target.setFrame(PortalFrame.canonical(Direction.S));
            target.setOwner(request.owner());
            target.setProjectionMode(ProjectionMode.OFF);
            if (newTarget) {
                Wormholes.portalManager.addLocalPortal(target);
            }
            target.open();
            ILocalPortal source = Wormholes.portalManager.getLocalPortal(portalId());
            if (!source.setDestination(target) || !target.setDestination(source)) {
                throw new IllegalStateException("Reentry fixture portals could not link");
            }
            target.save();
            source.save();
        }
        request.recipient().sendMessage("FIXTURE ready " + portalId());
    }

    UUID portalId() {
        return UUID.nameUUIDFromBytes(("transfer-fixture:" + Wormholes.networkManager.getLocalName()).getBytes(StandardCharsets.UTF_8));
    }

    private record SetupRequest(World world, UUID owner, Player recipient, boolean reentry) {
    }
}
