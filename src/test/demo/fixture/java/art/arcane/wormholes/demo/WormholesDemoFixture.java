package art.arcane.wormholes.demo;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;

public final class WormholesDemoFixture extends JavaPlugin implements Listener {
    private volatile ProfileProperty skin;
    private String actorName;
    private String observerName;
    private String worldName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        actorName = Objects.requireNonNull(getConfig().getString("actor-name"));
        observerName = Objects.requireNonNull(getConfig().getString("observer-name"));
        worldName = Objects.requireNonNull(getConfig().getString("world"));
        loadSkin();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("whdemo")).setExecutor(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        ProfileProperty texture = skin;
        if (!event.getName().equalsIgnoreCase(actorName) || texture == null) {
            return;
        }
        PlayerProfile profile = event.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(texture);
        event.setPlayerProfile(profile);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage("whdemo scene rune|wand|link; equip-runes|equip-wand [player]; clear-markers source|destination|all; pose actor|observer [source|destination]; report; skin-reload; skin-status");
            return true;
        }
        try {
            execute(sender, arguments);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage("Demo command failed: " + exception.getMessage());
            getLogger().log(Level.WARNING, "Demo fixture command failed", exception);
        }
        return true;
    }

    private void execute(CommandSender sender, String[] arguments) {
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        switch (operation) {
            case "scene" -> reset(sender, arguments);
            case "equip-runes" -> equip(sender, arguments, true);
            case "equip-wand" -> equip(sender, arguments, false);
            case "clear-markers" -> clearMarkers(sender, arguments);
            case "pose" -> pose(sender, arguments);
            case "report", "status" -> report(sender);
            case "skin-reload" -> {
                loadSkin();
                skinStatus(sender);
            }
            case "skin-status" -> skinStatus(sender);
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    private void reset(CommandSender sender, String[] arguments) {
        if (arguments.length < 2 || !List.of("wand", "rune", "link").contains(arguments[1])) {
            throw new IllegalArgumentException("scene requires rune, wand, or link");
        }
        World world = world();
        int removed = Wormholes.portalManager.deleteAllPortals();
        new DemoScene(world).build(!arguments[1].equals("rune"));
        sender.sendMessage("Scene ready: " + arguments[1] + ", removed=" + removed + ", source=-1..1,70..72,0; destination=-1..1,70..72,200; frames=3x3");
    }

    private void equip(CommandSender sender, String[] arguments, boolean runes) {
        Player player = player(arguments.length > 1 ? arguments[1] : actorName);
        player.closeInventory();
        player.getInventory().clear();
        ItemStack wand = Wormholes.blockManager.getWand();
        player.getInventory().setItem(0, wand);
        if (runes) {
            player.getInventory().setItem(1, Wormholes.blockManager.getWormholeRune(9));
        }
        player.getInventory().setHeldItemSlot(0);
        player.updateInventory();
        sender.sendMessage("Equipped " + player.getName() + ": wand=slot0, runes=" + (runes ? 9 : 0));
    }

    private void clearMarkers(CommandSender sender, String[] arguments) {
        String site = arguments.length > 1 ? arguments[1] : "all";
        if (!List.of("source", "destination", "all").contains(site)) {
            throw new IllegalArgumentException("Expected source, destination, or all");
        }
        for (int offset : new int[]{0, 200}) {
            if (site.equals("source") && offset != 0 || site.equals("destination") && offset != 200) {
                continue;
            }
            clearMarker(-1, 70, offset);
            clearMarker(1, 72, offset);
            if (offset == 0) {
                for (int x = -1; x <= 1; x++) {
                    for (int y = 70; y <= 72; y++) {
                        clearMarker(x, y, -1);
                    }
                }
            }
        }
        sender.sendMessage("Cleared glass corner markers: " + site);
    }

    private void clearMarker(int x, int y, int z) {
        if (world().getBlockAt(x, y, z).getType() == Material.GLASS) {
            world().getBlockAt(x, y, z).setType(Material.AIR, false);
        }
    }

    private void pose(CommandSender sender, String[] arguments) {
        if (arguments.length < 2 || !List.of("actor", "observer").contains(arguments[1])) {
            throw new IllegalArgumentException("pose requires actor or observer");
        }
        boolean observer = arguments[1].equals("observer");
        int offset = arguments.length > 2 && arguments[2].equals("destination") ? 200 : 0;
        Player player = player(observer ? observerName : actorName);
        player.closeInventory();
        player.setGameMode(observer ? GameMode.SPECTATOR : GameMode.CREATIVE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        Location position = observer
                ? new Location(world(), 7.5, 73.0, offset + 10.5, 145.0F, 12.0F)
                : new Location(world(), 0.5, 70.0, offset + 5.5, 180.0F, 0.0F);
        player.teleport(position);
        sender.sendMessage("Posed " + player.getName() + " at " + position.getX() + "," + position.getY() + "," + position.getZ());
    }

    private void report(CommandSender sender) {
        DemoScene scene = new DemoScene(world());
        List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
        sender.sendMessage("portals=" + portals.size() + ", sourceFrame=" + scene.hasFrame(0) + ", destinationFrame=" + scene.hasFrame(200));
        for (ILocalPortal portal : portals) {
            ITunnel tunnel = portal.getTunnel();
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int cells = 0;
            int z = 0;
            for (Vector block : portal.getStructure().getBlockPositions()) {
                minX = Math.min(minX, block.getBlockX());
                maxX = Math.max(maxX, block.getBlockX());
                minY = Math.min(minY, block.getBlockY());
                maxY = Math.max(maxY, block.getBlockY());
                z = block.getBlockZ();
                cells++;
            }
            sender.sendMessage("portal=" + portal.getId() + ", name=" + portal.getName() + ", z=" + z + ", width=" + (maxX - minX + 1) + ", height=" + (maxY - minY + 1) + ", cells=" + cells + ", linked=" + (tunnel != null && tunnel.isValid()) + ", destination=" + (tunnel == null ? "none" : tunnel.getDestinationId()));
        }
    }

    private void skinStatus(CommandSender sender) {
        Player actor = Bukkit.getPlayerExact(actorName);
        boolean applied = actor != null && actor.getPlayerProfile().getProperties().stream().anyMatch(property -> property.getName().equals("textures") && skin != null && property.getValue().equals(skin.getValue()));
        sender.sendMessage("skinLoaded=" + (skin != null) + ", actor=" + actorName + ", actorOnline=" + (actor != null) + ", skinApplied=" + applied);
    }

    private void loadSkin() {
        Path path = getDataFolder().toPath().resolve("skin-profile.json");
        if (!Files.isRegularFile(path)) {
            skin = null;
            getLogger().info("No skin-profile.json supplied; using the player's normal profile");
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray properties = document.getAsJsonArray("properties");
            if (properties == null) {
                throw new IllegalArgumentException("Skin profile requires properties");
            }
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if (property.get("name").getAsString().equals("textures")) {
                    String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                    skin = new ProfileProperty("textures", property.get("value").getAsString(), signature);
                    getLogger().info("Loaded demo actor texture; signed=" + (signature != null));
                    return;
                }
            }
            throw new IllegalArgumentException("Skin profile contains no textures property");
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            skin = null;
            getLogger().log(Level.SEVERE, "Unable to load demo actor skin profile", exception);
        }
    }

    private World world() {
        return Objects.requireNonNull(Bukkit.getWorld(worldName), "Demo world is not loaded: " + worldName);
    }

    private Player player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalStateException("Player is not online: " + name);
        }
        return player;
    }
}
