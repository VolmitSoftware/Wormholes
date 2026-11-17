package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.RecipesConfig;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorRecipeSettings;
import art.arcane.wormholes.door.DoorRecipeSpec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class MinecraftDoorRecipes {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final CraftingBookCategory BOOK = CraftingBookCategory.MISC;

    private MinecraftDoorRecipes() {
    }

    public static ResourceLocation key(DoorCraftProduct product) {
        return key(product.recipeName());
    }

    public static ResourceLocation skinKey(DoorForm form) {
        return key(form == DoorForm.DOOR ? "dimensional_door_skin" : "dimensional_trapdoor_skin");
    }

    public static List<ResourceLocation> keys() {
        List<ResourceLocation> keys = new ArrayList<>(DoorCraftProduct.values().length + DoorForm.values().length);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            keys.add(key(product));
        }
        for (DoorForm form : DoorForm.values()) {
            keys.add(skinKey(form));
        }
        return List.copyOf(keys);
    }

    static List<Recipe<?>> build(RecipesConfig configured, MinecraftPortalItems items) {
        DoorRecipeSettings recipes = DoorRecipeSettings.from(configured, (product, rejected) ->
            LOGGER.warn("Invalid dimensional-door recipe {}; using its shipped recipe", product.recipeName(), rejected));
        List<Recipe<?>> built = new ArrayList<>(DoorCraftProduct.values().length + DoorForm.values().length);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            DoorRecipeSpec recipe = recipes.products().get(product);
            if (recipe != null) {
                built.add(product(product, recipe, items));
            }
        }
        if (recipes.doorSkinEnabled()) {
            built.add(new SkinRecipe(DoorForm.DOOR));
        }
        if (recipes.trapdoorSkinEnabled()) {
            built.add(new SkinRecipe(DoorForm.TRAPDOOR));
        }
        return List.copyOf(built);
    }

    static ItemStack mint(DoorCraftProduct product) {
        return switch (product.kind()) {
            case PAIR -> MinecraftDoorItems.pairKit(product.form());
            case PERSONAL -> MinecraftDoorItems.door(DoorItemIdentity.newPersonal(product.form()));
            case PUBLIC -> MinecraftDoorItems.door(DoorItemIdentity.newPublic(product.form()));
            case RETURN -> throw new IllegalArgumentException("Return doors cannot be crafted");
        };
    }

    static ItemStack skin(DoorForm form, List<ItemStack> input) {
        int count = 0;
        for (ItemStack item : input) {
            if (!item.isEmpty()) {
                count++;
            }
        }
        if (count != 2) {
            return ItemStack.EMPTY;
        }
        ItemStack source = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;
        DoorItemIdentity identity = null;
        for (ItemStack item : input) {
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

    static Grid resolve(DoorRecipeSpec spec, MinecraftPortalItems items) {
        Map<Character, Cell> cells = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : spec.ingredients().entrySet()) {
            cells.put(entry.getKey(), cell(entry.getValue(), items));
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

    private static ResourceLocation key(String name) {
        return new ResourceLocation("wormholes", name);
    }

    private static ProductRecipe product(DoorCraftProduct product, DoorRecipeSpec recipe, MinecraftPortalItems items) {
        try {
            return new ProductRecipe(product, resolve(recipe, items));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid dimensional-door recipe {}; using its shipped recipe", product.recipeName(), exception);
        }
        return new ProductRecipe(product, resolve(product.defaultSpec(), items));
    }

    private static Cell cell(String token, MinecraftPortalItems items) {
        String normalized = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.startsWith("#")) {
            return switch (normalized) {
                case "#doors" -> group(token, item -> formOf(item) == DoorForm.DOOR);
                case "#trapdoors" -> group(token, item -> item.builtInRegistryHolder().is(ItemTags.WOODEN_TRAPDOORS));
                case "#any-trapdoors" -> group(token, item -> formOf(item) == DoorForm.TRAPDOOR);
                case "#wormhole-rune" -> new Cell(Ingredient.of(items.wormholeRune()), MinecraftDoorItems::isWormholeRune, true);
                default -> throw new IllegalArgumentException("Unknown door ingredient group " + token);
            };
        }
        List<Item> accepted = new ArrayList<>();
        for (String alternative : token.split("/")) {
            if (alternative.isBlank()) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(alternative.trim().toLowerCase(Locale.ROOT));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null || item == Items.AIR) {
                throw new IllegalArgumentException("Unknown door ingredient " + alternative);
            }
            accepted.add(item);
        }
        if (accepted.isEmpty()) {
            throw new IllegalArgumentException("Door ingredient contains no items: " + token);
        }
        return plain(Ingredient.of(accepted.toArray(new Item[0])));
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
        return plain(Ingredient.of(items.toArray(new Item[0])));
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

    private static ItemStack preview(DoorCraftProduct product) {
        ItemStack item = mint(product);
        CompoundTag tag = item.getTag();
        if (tag != null) {
            for (String key : List.copyOf(tag.getAllKeys())) {
                if (!ItemStack.TAG_DISPLAY.equals(key)) {
                    tag.remove(key);
                }
            }
        }
        return item;
    }

    private static ItemStack skinPreview(DoorForm form) {
        ItemStack item = new ItemStack(form == DoorForm.DOOR ? Items.OAK_DOOR : Items.OAK_TRAPDOOR);
        item.setHoverName(Component.literal(form == DoorForm.DOOR ? "Dimensional Door Skin" : "Dimensional Trapdoor Skin"));
        return item;
    }

    private static Ingredient skinSource(DoorForm form) {
        DoorCraftProduct personal = form == DoorForm.DOOR ? DoorCraftProduct.PERSONAL_DOOR : DoorCraftProduct.PERSONAL_TRAPDOOR;
        DoorCraftProduct shared = form == DoorForm.DOOR ? DoorCraftProduct.PUBLIC_DOOR : DoorCraftProduct.PUBLIC_TRAPDOOR;
        Stream<ItemStack> previews = Stream.of(preview(personal), preview(shared));
        Stream<ItemStack> members = Stream.of(group(form.name(), item -> formOf(item) == form).ingredient().getItems());
        return Ingredient.of(Stream.concat(previews, members));
    }

    private static Ingredient skinTarget(DoorForm form) {
        return form == DoorForm.DOOR ? group(form.name(), item -> formOf(item) == form).ingredient()
            : group(form.name(), item -> item.builtInRegistryHolder().is(ItemTags.WOODEN_TRAPDOORS)).ingredient();
    }

    public interface DoorRecipe {
        void place(MinecraftDoorRecipePlacement.Placement<?> placement);
    }

    private static final class ProductRecipe extends ShapedRecipe implements DoorRecipe {
        private final DoorCraftProduct product;
        private final Grid grid;

        private ProductRecipe(DoorCraftProduct product, Grid grid) {
            super(key(product), "", BOOK, grid.width(), grid.rows().size(), grid.ingredients(), preview(product));
            this.product = product;
            this.grid = grid;
        }

        @Override
        public boolean matches(CraftingContainer input, Level level) {
            return grid.matches(input);
        }

        @Override
        public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
            return mint(product);
        }

        @Override
        public void place(MinecraftDoorRecipePlacement.Placement<?> placement) {
            MinecraftDoorRecipePlacement.shaped(placement, this, grid.placement());
        }
    }

    private static final class SkinRecipe extends ShapelessRecipe implements DoorRecipe {
        private final DoorForm form;

        private SkinRecipe(DoorForm form) {
            super(skinKey(form), "", BOOK, skinPreview(form), NonNullList.of(Ingredient.EMPTY, skinSource(form), skinTarget(form)));
            this.form = form;
        }

        @Override
        public boolean matches(CraftingContainer input, Level level) {
            return !skin(form, input.getItems()).isEmpty();
        }

        @Override
        public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
            return skin(form, input.getItems());
        }

        @Override
        public void place(MinecraftDoorRecipePlacement.Placement<?> placement) {
            MinecraftDoorRecipePlacement.reskin(placement, this, form);
        }
    }

    record Cell(Ingredient ingredient, Predicate<ItemStack> accepts, boolean exact) {
    }

    record Grid(List<String> rows, Map<Character, Cell> cells) {
        int width() {
            return rows.getFirst().length();
        }

        boolean matches(CraftingContainer input) {
            int width = width();
            int height = rows.size();
            for (int offsetX = 0; offsetX <= input.getWidth() - width; offsetX++) {
                for (int offsetY = 0; offsetY <= input.getHeight() - height; offsetY++) {
                    if (matches(input, offsetX, offsetY, false) || matches(input, offsetX, offsetY, true)) {
                        return true;
                    }
                }
            }
            return false;
        }

        NonNullList<Ingredient> ingredients() {
            NonNullList<Ingredient> ingredients = NonNullList.withSize(width() * rows.size(), Ingredient.EMPTY);
            int index = 0;
            for (String row : rows) {
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (symbol != ' ') {
                        ingredients.set(index, cells.get(symbol).ingredient());
                    }
                    index++;
                }
            }
            return ingredients;
        }

        // One entry per pattern cell in row order; null marks an empty cell.
        List<Predicate<ItemStack>> placement() {
            List<Predicate<ItemStack>> slots = new ArrayList<>();
            for (String row : rows) {
                for (int x = 0; x < row.length(); x++) {
                    char symbol = row.charAt(x);
                    if (symbol == ' ') {
                        slots.add(null);
                    } else {
                        Cell cell = cells.get(symbol);
                        slots.add(cell.exact() ? cell.accepts() : MinecraftDoorRecipePlacement.plain(cell.accepts()));
                    }
                }
            }
            return Collections.unmodifiableList(slots);
        }

        private boolean matches(CraftingContainer input, int offsetX, int offsetY, boolean mirrored) {
            int width = width();
            int height = rows.size();
            for (int y = 0; y < input.getHeight(); y++) {
                for (int x = 0; x < input.getWidth(); x++) {
                    int localX = x - offsetX;
                    int localY = y - offsetY;
                    char symbol = ' ';
                    if (localX >= 0 && localY >= 0 && localX < width && localY < height) {
                        symbol = rows.get(localY).charAt(mirrored ? width - localX - 1 : localX);
                    }
                    ItemStack item = input.getItem(x + y * input.getWidth());
                    if (symbol == ' ' ? !item.isEmpty() : item.isEmpty() || !cells.get(symbol).accepts().test(item)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
