package qa;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class TransferFixture extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) {
            return true;
        }
        switch (args[0]) {
            case "setup" -> {
                SetupRequest request = new SetupRequest(player.getWorld(), player.getUniqueId(), player);
                getServer().getRegionScheduler().execute(this, request.world(), 0, 0, () -> setup(request));
            }
            case "stage" -> player.teleportAsync(new Location(player.getWorld(), 8.5, 101, 12.5, 180, 0)).thenAccept(done -> player.sendMessage("FIXTURE staged " + done));
            case "where" -> player.sendMessage("FIXTURE server=" + Wormholes.networkManager.getLocalName() + " uuid=" + player.getUniqueId() + " transferred=" + player.isTransferred() + " position=" + player.getLocation().toVector());
            case "stats" -> {
                TraversalService.Stats stats = Wormholes.traversalService.statsSnapshot();
                player.sendMessage("FIXTURE completed=" + stats.completed() + " failed=" + stats.failed() + " inFlight=" + stats.inFlight());
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
        ILocalPortal existing = Wormholes.portalManager.getLocalPortal(portalId());
        if (existing == null) {
            Set<Block> aperture = new HashSet<>();
            for (int x = 7; x <= 9; x++) {
                for (int y = 101; y <= 104; y++) {
                    aperture.add(world.getBlockAt(x, y, 8));
                }
            }
            PortalStructure structure = new PortalStructure();
            structure.setBlocks(aperture);
            LocalPortal portal = new LocalPortal(portalId(), PortalType.GATEWAY, structure);
            portal.setName("Transfer fixture " + Wormholes.networkManager.getLocalName());
            portal.setDirection(Direction.S);
            portal.setOwner(request.owner());
            portal.setProjectionMode(ProjectionMode.OFF);
            Wormholes.portalManager.addLocalPortal(portal);
            portal.setOpen(true);
            portal.save();
        }
        request.recipient().sendMessage("FIXTURE ready " + portalId());
    }

    private UUID portalId() {
        return UUID.nameUUIDFromBytes(("transfer-fixture:" + Wormholes.networkManager.getLocalName()).getBytes(StandardCharsets.UTF_8));
    }

    private record SetupRequest(World world, UUID owner, Player recipient) {
    }
}
