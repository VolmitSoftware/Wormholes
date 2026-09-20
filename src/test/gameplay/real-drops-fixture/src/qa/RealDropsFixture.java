package qa;

import art.arcane.wormholes.platform.WormholesPlatform;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.access.PortalAdmission;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class RealDropsFixture extends JavaPlugin {
    private Item droppedItem;
    private Item localItem;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) {
            return true;
        }
        switch (args[0]) {
            case "setup" -> setup(player);
            case "stage" -> player.teleportAsync(new Location(player.getWorld(), 8.5, 191, 13.5, 180, 0))
                .thenAccept(done -> reply(player, "DROPS staged=" + done));
            case "material" -> {
                if (args.length == 2) {
                    Material material = Material.valueOf(args[1]);
                    droppedItem.getScheduler().run(this, task -> {
                        droppedItem.setItemStack(new ItemStack(material));
                        reply(player, "DROPS material=" + material);
                    }, null);
                }
            }
            case "sample" -> sample(player, args.length > 1 ? getServer().getPlayerExact(args[1]) : player);
            case "visibility" -> visibility(player, droppedItem, args);
            case "default" -> droppedItem.getScheduler().run(this, task -> {
                droppedItem.setVisibleByDefault(Boolean.parseBoolean(args[1]));
                reply(player, "DROPS default=" + droppedItem.isVisibleByDefault());
            }, null);
            case "projection" -> {
                sourcePortal().setProjectionMode(Boolean.parseBoolean(args[1]) ? ProjectionMode.ON : ProjectionMode.OFF);
                sourcePortal().save();
                reply(player, "DROPS projection=" + sourcePortal().getProjectionMode());
            }
            case "local" -> local(player, args);
            default -> reply(player, "DROPS unknown command");
        }
        return true;
    }

    private void setup(Player player) {
        World world = player.getWorld();
        UUID owner = player.getUniqueId();
        CompletableFuture<ILocalPortal> source = createPortal(world, owner, 0, "source");
        CompletableFuture<ILocalPortal> target = createPortal(world, owner, 96, "target");
        source.thenCombine(target, (sourcePortal, targetPortal) -> {
            getServer().getRegionScheduler().execute(this, world, 0, 0, () -> link(player, sourcePortal, targetPortal));
            return true;
        }).exceptionally(error -> {
            getLogger().log(Level.SEVERE, "Drop projection fixture failed to create portals", error);
            reply(player, "DROPS failure=" + error);
            return false;
        });
    }

    private ILocalPortal sourcePortal() {
        return Wormholes.portalManager.getLocalPortal(UUID.nameUUIDFromBytes("drop-projection-fixture:source".getBytes(StandardCharsets.UTF_8)));
    }

    private void sample(Player recipient, Player viewer) {
        droppedItem.getScheduler().run(this, task -> captureSample(recipient, viewer), null);
    }

    private void captureSample(Player recipient, Player viewer) {
        ILocalPortal source = sourcePortal();
        List<EntityVisibility> displays = new ArrayList<>();
        for (Display display : droppedItem.getWorld().getNearbyEntitiesByType(Display.class, droppedItem.getLocation(), 5)) {
            displays.add(new EntityVisibility(display.getUniqueId(), display.isVisibleByDefault()));
        }
        String itemState = "DROPS item=" + droppedItem.getUniqueId() + " valid=" + droppedItem.isValid()
            + " stack=" + droppedItem.getItemStack().getType() + " entityId=" + droppedItem.getEntityId()
            + " carrierDefault=" + droppedItem.isVisibleByDefault() + " displays=" + displays.size()
            + " sourceOpen=" + source.isOpen() + " sourceMode=" + source.getProjectionMode()
            + " active=" + WormholesTelemetry.activeProjections() + " failures=" + WormholesTelemetry.failures();
        VisibilitySample sample = new VisibilitySample(itemState,
            new EntityVisibility(droppedItem.getUniqueId(), droppedItem.isVisibleByDefault()), displays);
        viewer.getScheduler().run(this, task -> reportSample(recipient, viewer, sample), null);
    }

    private void reportSample(Player recipient, Player viewer, VisibilitySample sample) {
        int visibleDisplays = 0;
        for (EntityVisibility display : sample.displays()) {
            if (WormholesPlatform.isEntityVisible(viewer, display.id(), display.visibleByDefault(), null)) {
                visibleDisplays++;
            }
        }
        reply(recipient, sample.itemState() + " viewer=" + viewer.getName() + " canSee="
            + WormholesPlatform.isEntityVisible(viewer, sample.carrier().id(), sample.carrier().visibleByDefault(), null)
            + " visibleDisplays=" + visibleDisplays + " observer=" + Wormholes.projectionManager.observersOf(sourcePortal().getId()).contains(viewer)
            + " allowed=" + PortalAdmission.allows((LocalPortal) sourcePortal(), viewer)
            + " position=" + viewer.getLocation().toVector() + " yaw=" + viewer.getLocation().getYaw());
    }

    private void visibility(Player recipient, Item item, String[] args) {
        Player viewer = getServer().getPlayerExact(args[1]);
        boolean visible = args[2].equals("show");
        item.getScheduler().run(this, task -> applyVisibility(recipient, viewer,
            new VisibilityChange(item, item.getUniqueId(), item.isVisibleByDefault(), visible)), null);
    }

    private void applyVisibility(Player recipient, Player viewer, VisibilityChange change) {
        viewer.getScheduler().run(this, task -> {
            if (change.visible()) {
                viewer.showEntity(this, change.item());
            } else {
                viewer.hideEntity(this, change.item());
            }
            reply(recipient, "DROPS visibility viewer=" + viewer.getName() + " canSee="
                + WormholesPlatform.isEntityVisible(viewer, change.id(), change.visibleByDefault(), null));
        }, null);
    }

    private void local(Player player, String[] args) {
        switch (args[1]) {
            case "create" -> {
                localItem = player.getWorld().dropItem(new Location(player.getWorld(), 8.5, 191.25, 4.5), new ItemStack(Material.STICK));
                preserve(localItem);
                reply(player, "DROPS local=" + localItem.getUniqueId() + " entityId=" + localItem.getEntityId());
            }
            case "hide", "show" -> visibility(player, localItem, new String[] { "visibility", player.getName(), args[1] });
            case "default" -> localItem.getScheduler().run(this, task -> {
                localItem.setVisibleByDefault(Boolean.parseBoolean(args[2]));
                reply(player, "DROPS localDefault=" + localItem.isVisibleByDefault());
            }, null);
            case "sample" -> reply(player, "DROPS local=" + localItem.getUniqueId() + " entityId=" + localItem.getEntityId()
                + " canSee=" + player.canSee(localItem)
                + " occluded=" + Wormholes.projectionManager.isLocalEntityOccluded(player.getUniqueId(), localItem.getUniqueId()));
            default -> reply(player, "DROPS unknown local command");
        }
    }

    private void link(Player player, ILocalPortal source, ILocalPortal target) {
        try {
            if (!source.setDestination(target)) {
                throw new IllegalStateException("Fixture source could not link to target");
            }
            source.setProjectionMode(ProjectionMode.ON);
            ((LocalPortal) source).open();
            source.save();
            reply(player, "DROPS ready open=" + source.isOpen() + " linked=" + source.hasTunnel());
        } catch (RuntimeException error) {
            getLogger().log(Level.SEVERE, "Drop projection fixture failed to link portals", error);
            reply(player, "DROPS failure=" + error);
        }
    }

    private CompletableFuture<ILocalPortal> createPortal(World world, UUID owner, int offset, String name) {
        CompletableFuture<ILocalPortal> ready = new CompletableFuture<>();
        world.getChunkAtAsync(offset >> 4, 0, true).thenAccept(chunk ->
            getServer().getRegionScheduler().execute(this, world, offset >> 4, 0,
                () -> preparePortal(new PortalRequest(chunk, owner, offset, name, ready)))).exceptionally(error -> {
                ready.completeExceptionally(error);
                return null;
            });
        return ready;
    }

    private void preparePortal(PortalRequest request) {
        try {
            World world = request.chunk().getWorld();
            request.chunk().addPluginChunkTicket(this);
            clearDrops(world, request.offset());
            preparePlatform(world, request.offset());
            PortalStructure structure = new PortalStructure();
            structure.setBlocks(aperture(world, request.offset()));
            UUID id = UUID.nameUUIDFromBytes(("drop-projection-fixture:" + request.name()).getBytes(StandardCharsets.UTF_8));
            ILocalPortal existing = Wormholes.portalManager.getLocalPortal(id);
            LocalPortal portal = existing == null ? new LocalPortal(id, PortalType.PORTAL, structure) : (LocalPortal) existing;
            portal.setName("Drop projection " + request.name());
            portal.setFrame(PortalFrame.canonical(Direction.S));
            portal.setOwner(request.owner());
            portal.setNetworkViewDepth(16);
            portal.setNetworkViewLateralPad(8);
            portal.setRenderMode(ProjectionRenderMode.VENTICULAR);
            portal.setProjectionMode(ProjectionMode.OFF);
            if (existing == null) {
                Wormholes.portalManager.addLocalPortal(portal);
            }
            portal.open();
            portal.save();
            if (request.offset() != 0) {
                prepareDrop(world, request.offset());
            }
            Wormholes.projectionChangeTracker.markChanged(world.getUID(), request.offset(), 0);
            request.ready().complete(portal);
        } catch (RuntimeException error) {
            request.ready().completeExceptionally(error);
        }
    }

    private void preparePlatform(World world, int offset) {
        for (int x = 1; x <= 14; x++) {
            for (int z = 1; z <= 14; z++) {
                for (int y = 190; y <= 198; y++) {
                    world.getBlockAt(offset + x, y, z).setType(y == 190 ? Material.STONE : Material.AIR, false);
                }
            }
        }
    }

    private Set<Block> aperture(World world, int offset) {
        Set<Block> blocks = new HashSet<>();
        for (int x = 5; x <= 11; x++) {
            for (int y = 190; y <= 197; y++) {
                Block block = world.getBlockAt(offset + x, y, 8);
                if (x == 5 || x == 11 || y == 190 || y == 197) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private void prepareDrop(World world, int offset) {
        Location location = new Location(world, offset + 8.5, 191.25, 4.5);
        droppedItem = world.dropItem(location, new ItemStack(Material.DIORITE));
        preserve(droppedItem);
    }

    private void clearDrops(World world, int offset) {
        Location location = new Location(world, offset + 8.5, 191.25, 4.5);
        for (Item item : world.getNearbyEntitiesByType(Item.class, location, 5)) {
            item.remove();
        }
        for (Display display : world.getNearbyEntitiesByType(Display.class, location, 5)) {
            display.remove();
        }
    }

    private void preserve(Item item) {
        item.setVelocity(new Vector());
        item.setPickupDelay(Integer.MAX_VALUE);
        item.setUnlimitedLifetime(true);
    }

    private void reply(Player player, String message) {
        player.getScheduler().run(this, task -> player.sendMessage(message), null);
    }

    private record PortalRequest(Chunk chunk, UUID owner, int offset, String name, CompletableFuture<ILocalPortal> ready) {
    }

    private record VisibilitySample(String itemState, EntityVisibility carrier, List<EntityVisibility> displays) {
    }

    private record EntityVisibility(UUID id, boolean visibleByDefault) {
    }

    private record VisibilityChange(Item item, UUID id, boolean visibleByDefault, boolean visible) {
    }
}
