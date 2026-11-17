package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the Wormholes recipes in the server recipe manager. On 1.20.1 the manager accepts a full replacement set, so
 * the recipes are merged into it directly and installed again whenever a reload swaps the manager or drops them.
 */
public final class MinecraftRecipeBook implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftRecipeBook> ACTIVE = new ConcurrentHashMap<>();

    private final WormholesModRuntime runtime;
    private MinecraftServer server;
    private boolean open;
    private List<Recipe<?>> shared = List.of();
    private List<Recipe<?>> doors = List.of();
    private LocalizationSnapshot language;
    private RecipeManager installed;

    public MinecraftRecipeBook(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static MinecraftRecipeBook forServer(MinecraftServer server) {
        return ACTIVE.get(server);
    }

    public static List<ResourceLocation> keys() {
        List<ResourceLocation> keys = new ArrayList<>(MinecraftDoorRecipes.keys().size() + 1);
        keys.add(MinecraftPortalTools.WAND_RECIPE);
        keys.addAll(MinecraftDoorRecipes.keys());
        return List.copyOf(keys);
    }

    public void open(MinecraftServer server) {
        this.server = server;
        open = true;
        ACTIVE.put(server, this);
        refresh();
    }

    @Override
    public void close() {
        if (server == null) {
            return;
        }
        open = false;
        install();
        ACTIVE.remove(server, this);
        server = null;
        installed = null;
    }

    public void tick() {
        if (!open) {
            return;
        }
        RecipeManager manager = server.getRecipeManager();
        if (runtime.localization().snapshot(null) != language || manager != installed
            || manager.byKey(MinecraftPortalTools.WAND_RECIPE).isEmpty()) {
            refresh();
        }
    }

    public List<Recipe<?>> sharedRecipes() {
        return shared;
    }

    public List<Recipe<?>> doorRecipes() {
        return doors;
    }

    public void refresh() {
        runtime.requireServerThread();
        language = runtime.localization().snapshot(null);
        List<Recipe<?>> previous = new ArrayList<>(shared.size() + doors.size());
        previous.addAll(shared);
        previous.addAll(doors);
        install();
        Set<ResourceLocation> current = new HashSet<>();
        for (Recipe<?> recipe : shared) {
            current.add(recipe.getId());
        }
        for (Recipe<?> recipe : doors) {
            current.add(recipe.getId());
        }
        ClientboundUpdateRecipesPacket recipes = new ClientboundUpdateRecipesPacket(server.getRecipeManager().getRecipes());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (Recipe<?> recipe : previous) {
                if (!current.contains(recipe.getId())) {
                    player.getRecipeBook().remove(recipe);
                }
            }
            player.connection.send(recipes);
            player.getRecipeBook().sendInitialRecipeBook(player);
            synchronize(player);
        }
    }

    public void reinstall() {
        if (open) {
            install();
        }
    }

    public void synchronize(ServerPlayer player) {
        if (!shared.isEmpty()) {
            player.awardRecipes(shared);
        }
        if (doors.isEmpty()) {
            return;
        }
        if (runtime.doors().canCraft(player)) {
            player.awardRecipes(doors);
        } else {
            player.resetRecipes(doors);
        }
    }

    private void install() {
        rebuild();
        RecipeManager manager = server.getRecipeManager();
        Set<ResourceLocation> owned = new HashSet<>(keys());
        List<Recipe<?>> merged = new ArrayList<>(manager.getRecipes().size() + shared.size() + doors.size());
        for (Recipe<?> recipe : manager.getRecipes()) {
            if (!owned.contains(recipe.getId())) {
                merged.add(recipe);
            }
        }
        merged.addAll(shared);
        merged.addAll(doors);
        manager.replaceRecipes(merged);
        installed = manager;
    }

    private void rebuild() {
        if (!open) {
            shared = List.of();
            doors = List.of();
            return;
        }
        MinecraftPortalItems items = new MinecraftPortalItems(server.registryAccess(), language);
        shared = List.of(MinecraftPortalTools.wandRecipe(items));
        try {
            doors = runtime.doors().enabled() ? MinecraftDoorRecipes.build(runtime.configuration().settings().getRecipes(), items) : List.of();
        } catch (RuntimeException failure) {
            LOGGER.error("Could not build the dimensional-door recipes; they are unavailable until the next reload", failure);
            doors = List.of();
        }
    }
}
