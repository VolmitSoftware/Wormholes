package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.wormholes.modded.mixin.RecipeMapAccess;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftRecipeBook implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftRecipeBook> ACTIVE = new ConcurrentHashMap<>();

    private final WormholesModRuntime runtime;
    private MinecraftServer server;
    private boolean open;
    private List<RecipeHolder<?>> shared = List.of();
    private List<RecipeHolder<?>> doors = List.of();
    private LocalizationSnapshot language;

    public MinecraftRecipeBook(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static MinecraftRecipeBook forServer(MinecraftServer server) {
        return ACTIVE.get(server);
    }

    public static List<ResourceKey<Recipe<?>>> keys() {
        List<ResourceKey<Recipe<?>>> keys = new ArrayList<>(MinecraftDoorRecipes.keys().size() + 1);
        keys.add(MinecraftPortalTools.WAND_RECIPE);
        keys.addAll(MinecraftDoorRecipes.keys());
        return List.copyOf(keys);
    }

    public static RecipeMap inject(RecipeManager manager, RecipeMap loaded) {
        for (MinecraftRecipeBook book : ACTIVE.values()) {
            if (book.server.getRecipeManager() == manager) {
                return book.merge(loaded);
            }
        }
        return loaded;
    }

    public static Collection<RecipeHolder<?>> learnable(RecipeMap recipes) {
        List<RecipeHolder<?>> learnable = new ArrayList<>(recipes.values().size());
        for (RecipeHolder<?> holder : recipes.values()) {
            if (!holder.value().isSpecial()) {
                learnable.add(holder);
            }
        }
        return Collections.unmodifiableList(learnable);
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
        server.getRecipeManager().finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        ACTIVE.remove(server, this);
        server = null;
    }

    public void tick() {
        if (open && runtime.localization().snapshot(null) != language) {
            refresh();
        }
    }

    public List<RecipeHolder<?>> sharedRecipes() {
        return shared;
    }

    public List<RecipeHolder<?>> doorRecipes() {
        return doors;
    }

    public void refresh() {
        runtime.requireServerThread();
        language = runtime.localization().snapshot(null);
        server.getRecipeManager().finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        List<ResourceKey<Recipe<?>>> retired = new ArrayList<>(keys());
        for (RecipeHolder<?> holder : shared) {
            retired.remove(holder.id());
        }
        for (RecipeHolder<?> holder : doors) {
            retired.remove(holder.id());
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ResourceKey<Recipe<?>> key : retired) {
                player.getRecipeBook().remove(key);
            }
            player.getRecipeBook().sendInitialRecipeBook(player);
            synchronize(player);
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

    private RecipeMap merge(RecipeMap loaded) {
        Set<RecipeHolder<?>> previous = Collections.newSetFromMap(new IdentityHashMap<>());
        previous.addAll(shared);
        previous.addAll(doors);
        rebuild();
        List<RecipeHolder<?>> injected = new ArrayList<>(shared.size() + doors.size());
        injected.addAll(shared);
        injected.addAll(doors);
        Set<ResourceKey<Recipe<?>>> replaced = new HashSet<>();
        for (RecipeHolder<?> holder : injected) {
            replaced.add(holder.id());
        }
        List<RecipeHolder<?>> merged = new ArrayList<>(loaded.values().size() + injected.size());
        boolean changed = !injected.isEmpty();
        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            for (RecipeHolder<?> holder : holders(loaded, type)) {
                if (previous.contains(holder) || replaced.contains(holder.id())) {
                    changed = true;
                } else {
                    merged.add(holder);
                }
            }
        }
        if (!changed) {
            return loaded;
        }
        merged.addAll(injected);
        return recipeMap(merged);
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

    private static RecipeMap recipeMap(List<RecipeHolder<?>> holders) {
        ImmutableMultimap.Builder<RecipeType<?>, RecipeHolder<?>> byType = ImmutableMultimap.builder();
        ImmutableMap.Builder<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey = ImmutableMap.builderWithExpectedSize(holders.size());
        for (RecipeHolder<?> holder : holders) {
            byType.put(holder.value().getType(), holder);
            byKey.put(holder.id(), holder);
        }
        return RecipeMapAccess.wormholesCreate(byType.build(), byKey.build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection<RecipeHolder<?>> holders(RecipeMap map, RecipeType<?> type) {
        return (Collection) map.byType((RecipeType) type);
    }
}
