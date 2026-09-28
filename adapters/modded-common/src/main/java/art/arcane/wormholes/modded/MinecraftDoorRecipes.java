package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.RecipeConfig;
import art.arcane.wormholes.config.toml.RecipesConfig;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorRecipeSpec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class MinecraftDoorRecipes {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftDoorRecipes> ACTIVE = new ConcurrentHashMap<>();
    private static final Recipe.CommonInfo COMMON = new Recipe.CommonInfo(true);
    private static final CraftingRecipe.CraftingBookInfo BOOK = new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, "");
    private static final SlotDisplay STATION = new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE);

    private final WormholesModRuntime runtime;
    private final MinecraftDoorService doors;
    private MinecraftServer server;
    private List<RecipeHolder<?>> registered = List.of();

    MinecraftDoorRecipes(WormholesModRuntime runtime, MinecraftDoorService doors) {
        this.runtime = runtime;
        this.doors = doors;
    }

    void open(MinecraftServer server) {
        this.server = server;
        ACTIVE.put(server, this);
        refresh();
    }

    void close() {
        if (server == null) {
            return;
        }
        ACTIVE.remove(server, this);
        server.getRecipeManager().finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        registered = List.of();
        server = null;
    }

    public static ResourceKey<Recipe<?>> key(DoorCraftProduct product) {
        return key(product.recipeName());
    }

    public static ResourceKey<Recipe<?>> skinKey(DoorForm form) {
        return key(form == DoorForm.DOOR ? "dimensional_door_skin" : "dimensional_trapdoor_skin");
    }

    public static List<ResourceKey<Recipe<?>>> keys() {
        List<ResourceKey<Recipe<?>>> keys = new ArrayList<>(DoorCraftProduct.values().length + DoorForm.values().length);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            keys.add(key(product));
        }
        for (DoorForm form : DoorForm.values()) {
            keys.add(skinKey(form));
        }
        return List.copyOf(keys);
    }

    public static RecipeMap inject(RecipeManager manager, RecipeMap loaded) {
        List<RecipeHolder<?>> door = List.of();
        for (Map.Entry<MinecraftServer, MinecraftDoorRecipes> active : ACTIVE.entrySet()) {
            if (active.getKey().getRecipeManager() == manager) {
                MinecraftDoorRecipes owner = active.getValue();
                try {
                    owner.registered = owner.build();
                } catch (RuntimeException failure) {
                    LOGGER.error("Could not build the dimensional-door recipes; they are unavailable until the next reload", failure);
                    owner.registered = List.of();
                }
                door = owner.registered;
            }
        }
        Set<ResourceKey<Recipe<?>>> replaced = new HashSet<>();
        for (RecipeHolder<?> holder : door) {
            replaced.add(holder.id());
        }
        List<RecipeHolder<?>> merged = new ArrayList<>(loaded.values().size() + door.size());
        boolean changed = !door.isEmpty();
        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            for (RecipeHolder<?> holder : holders(loaded, type)) {
                if (holder.value() instanceof DoorRecipe || replaced.contains(holder.id())) {
                    changed = true;
                } else {
                    merged.add(holder);
                }
            }
        }
        if (!changed) {
            return loaded;
        }
        merged.addAll(door);
        return RecipeMap.create(merged);
    }

    public List<RecipeHolder<?>> registered() {
        return registered;
    }

    public void refresh() {
        runtime.requireServerThread();
        server.getRecipeManager().finalizeRecipeLoading(server.getWorldData().enabledFeatures());
        List<ResourceKey<Recipe<?>>> retired = new ArrayList<>(keys());
        for (RecipeHolder<?> holder : registered) {
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
        if (registered.isEmpty()) {
            return;
        }
        if (doors.canCraft(player)) {
            player.awardRecipes(registered);
        } else {
            player.resetRecipes(registered);
        }
    }

    List<RecipeHolder<?>> build() {
        if (!doors.enabled()) {
            return List.of();
        }
        RecipesConfig configured = runtime.configuration().settings().getRecipes();
        List<RecipeHolder<?>> holders = new ArrayList<>(DoorCraftProduct.values().length + DoorForm.values().length);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            RecipeConfig recipe = configured.forProduct(product);
            if (recipe == null || recipe.enabled) {
                holders.add(new RecipeHolder<>(key(product), product(product, recipe)));
            }
        }
        if (configured.doorSkin.enabled) {
            holders.add(new RecipeHolder<>(skinKey(DoorForm.DOOR), new SkinRecipe(DoorForm.DOOR)));
        }
        if (configured.trapdoorSkin.enabled) {
            holders.add(new RecipeHolder<>(skinKey(DoorForm.TRAPDOOR), new SkinRecipe(DoorForm.TRAPDOOR)));
        }
        return List.copyOf(holders);
    }

    static ItemStack mint(DoorCraftProduct product) {
        return switch (product.kind()) {
            case PAIR -> MinecraftDoorItems.pairKit(product.form());
            case PERSONAL -> MinecraftDoorItems.door(DoorItemIdentity.newPersonal(product.form()));
            case PUBLIC -> MinecraftDoorItems.door(DoorItemIdentity.newPublic(product.form()));
            case RETURN -> throw new IllegalArgumentException("Return doors cannot be crafted");
        };
    }

    static ItemStack skin(DoorForm form, CraftingInput input) {
        if (input.ingredientCount() != 2) {
            return ItemStack.EMPTY;
        }
        ItemStack source = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;
        DoorItemIdentity identity = null;
        for (ItemStack item : input.items()) {
            if (item.isEmpty()) {
                continue;
            }
            Optional<DoorItemIdentity> decoded = MinecraftDoorItems.identity(item);
            if (decoded.isPresent()) {
                if (identity != null) {
                    return ItemStack.EMPTY;
                }
                identity = decoded.get();
                source = item;
            } else {
                target = item;
            }
        }
        if (identity == null || identity.form() != form || source.is(target.getItem())
            || formOf(source.getItem()) != form || formOf(target.getItem()) != form || form == DoorForm.TRAPDOOR && !target.is(ItemTags.WOODEN_TRAPDOORS)) {
            return ItemStack.EMPTY;
        }
        return MinecraftDoorItems.door(identity, target.getItem());
    }

    static Grid resolve(DoorRecipeSpec spec) {
        Map<Character, Cell> cells = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : spec.ingredients().entrySet()) {
            cells.put(entry.getKey(), cell(entry.getValue()));
        }
        List<String> rows = spec.shape().rows();
        int minX = rows.getFirst().length();
        int maxX = -1;
        int minY = rows.size();
        int maxY = -1;
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < rows.get(y).length(); x++) {
                if (rows.get(y).charAt(x) != ' ') {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            throw new IllegalArgumentException("A door recipe cannot be empty");
        }
        List<String> trimmed = new ArrayList<>(maxY - minY + 1);
        for (int y = minY; y <= maxY; y++) {
            trimmed.add(rows.get(y).substring(minX, maxX + 1));
        }
        return new Grid(List.copyOf(trimmed), Map.copyOf(cells));
    }

    private static ResourceKey<Recipe<?>> key(String name) {
        return ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("wormholes", name));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection<RecipeHolder<?>> holders(RecipeMap map, RecipeType<?> type) {
        return (Collection) map.byType((RecipeType) type);
    }

    private static ProductRecipe product(DoorCraftProduct product, RecipeConfig recipe) {
        if (recipe != null) {
            try {
                return new ProductRecipe(product, resolve(DoorRecipeSpec.parse(recipe.shape, recipe.ingredients)));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Invalid dimensional-door recipe {}; using its shipped recipe", product.recipeName(), exception);
            }
        }
        return new ProductRecipe(product, resolve(product.defaultSpec()));
    }

    private static Cell cell(String token) {
        String normalized = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.startsWith("#")) {
            return switch (normalized) {
                case "#doors" -> group(token, item -> formOf(item) == DoorForm.DOOR);
                case "#trapdoors" -> group(token, item -> item.builtInRegistryHolder().is(ItemTags.WOODEN_TRAPDOORS));
                case "#any-trapdoors" -> group(token, item -> formOf(item) == DoorForm.TRAPDOOR);
                case "#wormhole-rune" -> new Cell(Ingredient.of(Items.DARK_PRISMARINE), MinecraftDoorItems::isWormholeRune, true);
                default -> throw new IllegalArgumentException("Unknown door ingredient group " + token);
            };
        }
        List<Item> accepted = new ArrayList<>();
        for (String alternative : token.split("/")) {
            if (alternative.isBlank()) {
                continue;
            }
            Identifier id = Identifier.tryParse(alternative.trim().toLowerCase(Locale.ROOT));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) {
                throw new IllegalArgumentException("Unknown door ingredient " + alternative);
            }
            accepted.add(item);
        }
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("Door ingredient contains no items: " + token);
        }
        return plain(Ingredient.of(accepted.stream()));
    }

    private static Cell group(String token, Predicate<Item> member) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (member.test(item)) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Door ingredient group matches no items: " + token);
        }
        return plain(Ingredient.of(items.stream()));
    }

    private static Cell plain(Ingredient ingredient) {
        return new Cell(ingredient, ingredient, false);
    }

    private static DoorForm formOf(Item item) {
        if (!(item instanceof BlockItem block)) {
            return null;
        }
        if (block.getBlock() instanceof DoorBlock) {
            return DoorForm.DOOR;
        }
        return block.getBlock() instanceof TrapDoorBlock ? DoorForm.TRAPDOOR : null;
    }

    private static ItemStackTemplate preview(DoorCraftProduct product) {
        ItemStack item = mint(product);
        item.remove(DataComponents.CUSTOM_DATA);
        return ItemStackTemplate.fromNonEmptyStack(item);
    }

    private static ItemStackTemplate skinPreview(DoorForm form) {
        ItemStack item = new ItemStack(form == DoorForm.DOOR ? Items.OAK_DOOR : Items.OAK_TRAPDOOR);
        item.set(DataComponents.CUSTOM_NAME, Component.literal(form == DoorForm.DOOR ? "Dimensional Door Skin" : "Dimensional Trapdoor Skin"));
        return ItemStackTemplate.fromNonEmptyStack(item);
    }

    private static Ingredient skinSource(DoorForm form) {
        return group(form.name(), item -> formOf(item) == form).ingredient();
    }

    private static Ingredient skinTarget(DoorForm form) {
        return form == DoorForm.DOOR ? skinSource(form) : group(form.name(), item -> item.builtInRegistryHolder().is(ItemTags.WOODEN_TRAPDOORS)).ingredient();
    }

    public interface DoorRecipe {
        RecipeBookMenu.PostPlaceAction place(MinecraftDoorRecipePlacement.Placement<?> placement);
    }

    private static final class ProductRecipe extends ShapedRecipe implements DoorRecipe {
        private final DoorCraftProduct product;
        private final Grid grid;

        private ProductRecipe(DoorCraftProduct product, Grid grid) {
            super(COMMON, BOOK, grid.pattern(), preview(product));
            this.product = product;
            this.grid = grid;
        }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            return grid.matches(input);
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            return mint(product);
        }

        @Override
        public List<RecipeDisplay> display() {
            List<SlotDisplay> slots = new ArrayList<>(getWidth() * getHeight());
            for (String row : grid.rows()) {
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    slots.add(symbol == ' ' ? SlotDisplay.Empty.INSTANCE : grid.cells().get(symbol).display());
                }
            }
            return List.of(new ShapedCraftingRecipeDisplay(getWidth(), getHeight(), slots, new SlotDisplay.ItemStackSlotDisplay(preview(product)), STATION));
        }

        @Override
        public RecipeBookMenu.PostPlaceAction place(MinecraftDoorRecipePlacement.Placement<?> placement) {
            return MinecraftDoorRecipePlacement.shaped(placement, this, grid.placement());
        }
    }

    private static final class SkinRecipe extends ShapelessRecipe implements DoorRecipe {
        private final DoorForm form;

        private SkinRecipe(DoorForm form) {
            super(COMMON, BOOK, skinPreview(form), List.of(skinSource(form), skinTarget(form)));
            this.form = form;
        }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            return !skin(form, input).isEmpty();
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            return skin(form, input);
        }

        @Override
        public List<RecipeDisplay> display() {
            DoorCraftProduct personal = form == DoorForm.DOOR ? DoorCraftProduct.PERSONAL_DOOR : DoorCraftProduct.PERSONAL_TRAPDOOR;
            DoorCraftProduct shared = form == DoorForm.DOOR ? DoorCraftProduct.PUBLIC_DOOR : DoorCraftProduct.PUBLIC_TRAPDOOR;
            SlotDisplay source = new SlotDisplay.Composite(List.of(new SlotDisplay.ItemStackSlotDisplay(preview(personal)),
                new SlotDisplay.ItemStackSlotDisplay(preview(shared))));
            return List.of(new ShapelessCraftingRecipeDisplay(List.of(source, skinTarget(form).display()),
                new SlotDisplay.ItemStackSlotDisplay(skinPreview(form)), STATION));
        }

        @Override
        public RecipeBookMenu.PostPlaceAction place(MinecraftDoorRecipePlacement.Placement<?> placement) {
            return MinecraftDoorRecipePlacement.reskin(placement, this, form);
        }
    }

    record Cell(Ingredient ingredient, Predicate<ItemStack> accepts, boolean exact) {
        SlotDisplay display() {
            return exact ? new SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate.fromNonEmptyStack(MinecraftDoorItems.wormholeRune())) : ingredient.display();
        }
    }

    record Grid(List<String> rows, Map<Character, Cell> cells) {
        boolean matches(CraftingInput input) {
            int width = rows.getFirst().length();
            if (input.width() != width || input.height() != rows.size()) {
                return false;
            }
            return matches(input, false) || matches(input, true);
        }

        ShapedRecipePattern pattern() {
            Map<Character, Ingredient> key = new LinkedHashMap<>();
            for (Map.Entry<Character, Cell> cell : cells.entrySet()) {
                key.put(cell.getKey(), cell.getValue().ingredient());
            }
            return ShapedRecipePattern.of(key, rows);
        }

        List<Predicate<ItemStack>> placement() {
            List<Predicate<ItemStack>> slots = new ArrayList<>();
            for (String row : rows) {
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (symbol != ' ') {
                        Cell cell = cells.get(symbol);
                        slots.add(cell.exact() ? cell.accepts() : MinecraftDoorRecipePlacement.plain(cell.accepts()));
                    }
                }
            }
            return List.copyOf(slots);
        }

        private boolean matches(CraftingInput input, boolean mirrored) {
            int width = rows.getFirst().length();
            for (int y = 0; y < rows.size(); y++) {
                for (int x = 0; x < width; x++) {
                    char symbol = rows.get(y).charAt(mirrored ? width - x - 1 : x);
                    ItemStack item = input.getItem(x, y);
                    if (symbol == ' ' ? !item.isEmpty() : item.isEmpty() || !cells.get(symbol).accepts().test(item)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
