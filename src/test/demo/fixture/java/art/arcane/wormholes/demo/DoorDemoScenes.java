package art.arcane.wormholes.demo;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemService;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorItemPdcCodec;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PocketBlockPosition;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketSpace;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DoorDemoScenes {
    private static final List<String> SCENES = List.of("door-crafting", "pair-doors", "personal-pockets", "public-pockets", "door-open-state", "trapdoor-travel");
    private final World world;

    public DoorDemoScenes(World world) {
        this.world = Objects.requireNonNull(world);
    }

    public void execute(CommandSender sender, String[] fullArgs) {
        if (fullArgs.length < 2) {
            throw new IllegalArgumentException("doors requires prepare, report, player, inventory, or endpoint");
        }
        switch (fullArgs[1]) {
            case "prepare" -> prepare(sender, fullArgs);
            case "report" -> report(sender);
            case "player" -> sender.sendMessage(playerReport(player(fullArgs[2])).toString());
            case "inventory" -> sender.sendMessage(inventoryReport(player(fullArgs[2])).toString());
            case "endpoint" -> sender.sendMessage(endpointReport(Integer.parseInt(fullArgs[2]), Integer.parseInt(fullArgs[3]), Integer.parseInt(fullArgs[4])).toString());
            default -> throw new IllegalArgumentException("Unknown door fixture operation: " + fullArgs[1]);
        }
    }

    private void prepare(CommandSender sender, String[] arguments) {
        if (arguments.length != 5 || !SCENES.contains(arguments[2])) {
            throw new IllegalArgumentException("doors prepare <scene> <actor> <observer>");
        }
        String scene = arguments[2];
        Player actor = player(arguments[3]);
        Player observer = player(arguments[4]);
        boolean pockets = scene.endsWith("pockets");
        boolean trapdoors = scene.equals("trapdoor-travel");
        if (pockets && Bukkit.getWorlds().stream().noneMatch(candidate -> candidate.getKey().toString().equals("wormholes:pockets"))) {
            throw new IllegalStateException("The pocket dimension is unavailable; restart the disposable server after its datapack is installed");
        }
        resetPlayer(actor, GameMode.SURVIVAL);
        resetPlayer(observer, pockets ? GameMode.SURVIVAL : GameMode.SPECTATOR);
        actor.teleport(new Location(world, 22.5, 70, 3.5, 180, 0));
        observer.teleport(new Location(world, pockets ? 25.5 : 27.5, pockets ? 70 : 74, 8.5, 160, 12));
        if (scene.equals("personal-pockets")) {
            clearPersonalMarker(actor);
            clearPersonalMarker(observer);
        }
        for (PlacedDoorEndpoint endpoint : manager().state().endpoints()) {
            DoorPosition position = endpoint.position();
            if (position.worldId().equals(world.getUID()) && inAnnex(position.x(), position.z())) {
                clearEndpoint(actor, endpoint);
            }
        }
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item && inAnnex(entity.getLocation().getBlockX(), entity.getLocation().getBlockZ())) {
                entity.remove();
            }
        }
        gardenAnnex();
        DoorItemService items = manager().items();
        switch (scene) {
            case "door-crafting" -> craftingMaterials(actor);
            case "personal-pockets" -> {
                actor.getInventory().setItem(0, items.createPersonalDoor());
                actor.getInventory().setItem(1, items.createPersonalDoor());
            }
            case "public-pockets" -> actor.getInventory().setItem(0, items.createPublicDoor());
            default -> actor.getInventory().setItem(0, items.createPairKit(trapdoors ? DoorForm.TRAPDOOR : DoorForm.DOOR));
        }
        if (!scene.equals("door-crafting")) {
            actor.getInventory().setItem(6, new ItemStack(Material.DIAMOND_AXE));
            actor.getInventory().setItem(7, new ItemStack(Material.GOLD_BLOCK, 8));
            observer.getInventory().setItem(7, new ItemStack(Material.LAPIS_BLOCK, 8));
        }
        if (pockets) {
            actor.getInventory().setItem(5, new ItemStack(Material.LANTERN, 4));
            observer.getInventory().setItem(5, new ItemStack(Material.LANTERN, 4));
        }
        actor.getInventory().setHeldItemSlot(0);
        actor.updateInventory();
        observer.updateInventory();
        if (trapdoors) {
            trapdoorDeck(22);
            trapdoorDeck(30);
            actor.teleport(new Location(world, 22.5, 74, 3.5, 180, 0));
            observer.teleport(new Location(world, 27.5, 77, 8.5, 160, 20));
        }
        sender.sendMessage("Door scene ready: " + scene);
    }

    private void clearEndpoint(Player actor, PlacedDoorEndpoint endpoint) {
        DoorPosition position = endpoint.position();
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        if (block.isEmpty()) {
            manager().onChunkLoad(new ChunkLoadEvent(block.getChunk(), false));
        } else if (!actor.breakBlock(block)) {
            throw new IllegalStateException("Could not clear the previous door scene at " + position);
        }
        if (manager().state().findEndpointByItem(endpoint.identity().itemId()).isPresent()) {
            throw new IllegalStateException("Previous door endpoint remains registered at " + position);
        }
    }

    private void resetPlayer(Player player, GameMode mode) {
        if (player.isDead()) {
            player.spigot().respawn();
        }
        player.closeInventory();
        player.setGameMode(mode);
        player.getInventory().clear();
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setFallDistance(0);
    }

    private void craftingMaterials(Player actor) {
        world.getBlockAt(22, 70, 0).setType(Material.CRAFTING_TABLE, false);
        actor.getInventory().setItem(9, Wormholes.blockManager.getWormholeRune(4));
        actor.getInventory().setItem(10, new ItemStack(Material.OAK_DOOR, 4));
        actor.getInventory().setItem(11, new ItemStack(Material.ENDER_EYE, 2));
        actor.getInventory().setItem(12, new ItemStack(Material.OBSIDIAN, 2));
        actor.getInventory().setItem(13, new ItemStack(Material.RECOVERY_COMPASS));
        actor.getInventory().setItem(14, new ItemStack(Material.ENDER_CHEST, 2));
        actor.getInventory().setItem(15, new ItemStack(Material.LODESTONE));
    }

    private JsonObject inventoryReport(Player player) {
        JsonArray contents = new JsonArray();
        DoorItemService items = manager().items();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("material", stack.getType().name());
            item.addProperty("count", stack.getAmount());
            UUID kitId = items.pairKit(stack).map(DoorItemPdcCodec.PairKit::kitId).orElse(null);
            if (kitId != null) {
                item.addProperty("kitId", kitId.toString());
            }
            DoorItemIdentity identity = items.decodeDoor(stack).orElse(null);
            if (identity != null) {
                item.addProperty("itemId", identity.itemId().toString());
                item.addProperty("kind", identity.kind().name());
            }
            contents.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("player", player.getName());
        result.add("items", contents);
        return result;
    }

    private void clearPersonalMarker(Player player) {
        PocketSpace pocket = manager().state().findPocket(PocketBinding.personal(player.getUniqueId())).orElse(null);
        if (pocket == null) {
            return;
        }
        World pocketWorld = Bukkit.getWorlds().stream()
                .filter(candidate -> candidate.getKey().toString().equals("wormholes:pockets"))
                .findFirst().orElseThrow();
        PocketBlockPosition exit = manager().layoutOf(pocket).returnDoorLower();
        Block marker = pocketWorld.getBlockAt(exit.x() + 2, exit.y(), exit.z() - 2);
        if (marker.getType() == Material.GOLD_BLOCK || marker.getType() == Material.LAPIS_BLOCK) {
            Block lamp = pocketWorld.getBlockAt(exit.x() + 2, exit.y() + 1, exit.z() - 2);
            if (lamp.getType() == Material.LANTERN) {
                lamp.setType(Material.AIR, false);
            }
            marker.setType(Material.AIR, false);
        }
    }

    private void gardenAnnex() {
        for (int x = 18; x <= 34; x++) {
            for (int z = -5; z <= 13; z++) {
                for (int y = 70; y <= 79; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
                boolean edge = x == 18 || x == 34 || z == -5 || z == 13;
                world.getBlockAt(x, 69, z).setType(edge ? Material.ANDESITE : Material.STONE_BRICKS, false);
            }
        }
        for (int x : new int[]{19, 33}) {
            for (int z : new int[]{-4, 12}) {
                world.getBlockAt(x, 70, z).setType(Material.AZALEA, false);
                world.getBlockAt(x, 70, z + (z < 0 ? 1 : -1)).setType(Material.PINK_TULIP, false);
            }
        }
        for (int x : new int[]{22, 30}) {
            world.getBlockAt(x, 69, 0).setType(Material.CHISELED_STONE_BRICKS, false);
            world.getBlockAt(x - 2, 70, -2).setType(Material.STONE_BRICKS, false);
            world.getBlockAt(x - 2, 71, -2).setType(Material.LANTERN, false);
        }
        world.getBlockAt(30, 69, 6).setType(Material.CHISELED_STONE_BRICKS, false);
    }

    private void trapdoorDeck(int centerX) {
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = -1; z <= 4; z++) {
                if (x != centerX || z != 0) {
                    world.getBlockAt(x, 73, z).setType(Material.STONE_BRICKS, false);
                }
            }
        }
    }

    private void report(CommandSender sender) {
        JsonArray endpoints = new JsonArray();
        for (PlacedDoorEndpoint endpoint : manager().state().endpoints()) {
            DoorPosition position = endpoint.position();
            if (position.worldId().equals(world.getUID()) && inAnnex(position.x(), position.z())) {
                endpoints.add(endpointReport(position.x(), position.y(), position.z()));
            }
        }
        JsonObject result = new JsonObject();
        result.add("endpoints", endpoints);
        sender.sendMessage(result.toString());
    }

    private JsonObject endpointReport(int x, int y, int z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        Block block = world.getBlockAt(x, y, z);
        result.addProperty("material", block.getType().name());
        result.addProperty("open", block.getBlockData() instanceof Openable openable && openable.isOpen());
        PlacedDoorEndpoint endpoint = manager().state().findEndpoint(world.getUID(), x, y, z).orElse(null);
        result.addProperty("exists", endpoint != null);
        if (endpoint != null) {
            result.addProperty("itemId", endpoint.identity().itemId().toString());
            result.addProperty("kind", endpoint.identity().kind().name());
            result.addProperty("form", endpoint.identity().form().name());
            result.addProperty("pairId", endpoint.identity().pairId() == null ? null : endpoint.identity().pairId().toString());
            result.addProperty("openState", endpoint.openState().name());
        }
        return result;
    }

    private JsonObject playerReport(Player player) {
        JsonObject result = new JsonObject();
        Location location = player.getLocation();
        result.addProperty("player", player.getName());
        result.addProperty("world", player.getWorld().getKey().toString());
        result.addProperty("x", location.getX());
        result.addProperty("y", location.getY());
        result.addProperty("z", location.getZ());
        PocketSpace pocket = manager().pocketAt(location).orElse(null);
        result.addProperty("pocket", pocket == null ? null : pocket.spaceId().toString());
        if (pocket != null) {
            PocketLayout layout = manager().layoutOf(pocket);
            PocketBlockPosition exit = layout.returnDoorLower();
            result.addProperty("returnX", exit.x());
            result.addProperty("returnY", exit.y());
            result.addProperty("returnZ", exit.z());
            result.addProperty("returnOpen", player.getWorld().getBlockAt(exit.x(), exit.y(), exit.z()).getBlockData() instanceof Openable openable && openable.isOpen());
            result.addProperty("markerX", exit.x() + 2);
            result.addProperty("markerY", exit.y());
            result.addProperty("markerZ", exit.z() - 2);
            result.addProperty("marker", player.getWorld().getBlockAt(exit.x() + 2, exit.y(), exit.z() - 2).getType().name());
            result.addProperty("markerLight", player.getWorld().getBlockAt(exit.x() + 2, exit.y() + 1, exit.z() - 2).getType().name());
        }
        return result;
    }

    private boolean inAnnex(int x, int z) {
        return x >= 18 && x <= 34 && z >= -5 && z <= 13;
    }

    private Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalStateException("Player is not online: " + name);
        }
        return player;
    }

    private DimensionalDoorManager manager() {
        DimensionalDoorManager manager = Wormholes.dimensionalDoorManager;
        if (manager == null) {
            throw new IllegalStateException("Dimensional doors are not enabled");
        }
        return manager;
    }
}
