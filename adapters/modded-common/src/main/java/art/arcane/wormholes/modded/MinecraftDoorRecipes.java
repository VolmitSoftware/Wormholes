package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.RecipeConfig;
import art.arcane.wormholes.config.toml.RecipesConfig;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorKind;
import art.arcane.wormholes.door.DoorRecipeSpec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public final class MinecraftDoorRecipes {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<Identifier, RecipeSerializer<?>> SERIALIZERS = createSerializers();

    private final WormholesModRuntime runtime;
    private final Map<DoorCraftProduct, Grid> grids = new EnumMap<>(DoorCraftProduct.class);
    private RecipesConfig configured;

    MinecraftDoorRecipes(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public static Map<Identifier, RecipeSerializer<?>> serializers() {
        return SERIALIZERS;
    }

    public boolean matches(DoorCraftProduct product, CraftingInput input) {
        refresh();
        Grid grid = grids.get(product);
        return grid != null && grid.matches(input);
    }

    public boolean matchesSkin(DoorForm form, CraftingInput input) {
        refresh();
        boolean enabled = form == DoorForm.DOOR ? configured.doorSkin.enabled : configured.trapdoorSkin.enabled;
        return enabled && !skin(form, input).isEmpty();
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
            || formOf(source) != form || formOf(target) != form || form == DoorForm.TRAPDOOR && !target.is(ItemTags.WOODEN_TRAPDOORS)) {
            return ItemStack.EMPTY;
        }
        return MinecraftDoorItems.door(identity, target.getItem());
    }

    static Grid resolve(DoorRecipeSpec spec) {
        Map<Character, Predicate<ItemStack>> ingredients = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : spec.ingredients().entrySet()) {
            ingredients.put(entry.getKey(), ingredient(entry.getValue()));
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
        return new Grid(List.copyOf(trimmed), Map.copyOf(ingredients));
    }

    private void refresh() {
        RecipesConfig current = runtime.configuration().settings().getRecipes();
        if (current == configured) {
            return;
        }
        grids.clear();
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            RecipeConfig recipe = current.forProduct(product);
            if (recipe != null && !recipe.enabled) {
                continue;
            }
            try {
                grids.put(product, resolve(recipe == null ? product.defaultSpec() : DoorRecipeSpec.parse(recipe.shape, recipe.ingredients)));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Invalid dimensional-door recipe {}; using its shipped recipe", product.recipeName(), exception);
                grids.put(product, resolve(product.defaultSpec()));
            }
        }
        configured = current;
    }

    private static Predicate<ItemStack> ingredient(String token) {
        String normalized = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.startsWith("#")) {
            return switch (normalized) {
                case "#doors" -> item -> formOf(item) == DoorForm.DOOR;
                case "#trapdoors" -> item -> item.is(ItemTags.WOODEN_TRAPDOORS);
                case "#any-trapdoors" -> item -> formOf(item) == DoorForm.TRAPDOOR;
                case "#wormhole-rune" -> MinecraftDoorItems::isWormholeRune;
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
        return item -> !item.isEmpty() && accepted.contains(item.getItem());
    }

    private static DoorForm formOf(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem block)) {
            return null;
        }
        if (block.getBlock() instanceof DoorBlock) {
            return DoorForm.DOOR;
        }
        return block.getBlock() instanceof TrapDoorBlock ? DoorForm.TRAPDOOR : null;
    }

    private static Map<Identifier, RecipeSerializer<?>> createSerializers() {
        Map<Identifier, RecipeSerializer<?>> serializers = new LinkedHashMap<>();
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            Identifier id = Identifier.fromNamespaceAndPath("wormholes", product.recipeName());
            ProductRecipe recipe = new ProductRecipe(product);
            serializers.put(id, new RecipeSerializer<>(MapCodec.unit(() -> recipe), StreamCodec.<RegistryFriendlyByteBuf, ProductRecipe>unit(recipe)));
        }
        for (DoorForm form : DoorForm.values()) {
            Identifier id = skinKey(form);
            SkinRecipe recipe = new SkinRecipe(form);
            serializers.put(id, new RecipeSerializer<>(MapCodec.unit(() -> recipe), StreamCodec.<RegistryFriendlyByteBuf, SkinRecipe>unit(recipe)));
        }
        return Map.copyOf(serializers);
    }

    private static Identifier skinKey(DoorForm form) {
        return Identifier.fromNamespaceAndPath("wormholes", form == DoorForm.DOOR ? "dimensional_door_skin" : "dimensional_trapdoor_skin");
    }

    public abstract static class DoorRecipe extends CustomRecipe {
    }

    private static final class ProductRecipe extends DoorRecipe {
        private final DoorCraftProduct product;

        private ProductRecipe(DoorCraftProduct product) {
            this.product = product;
        }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            MinecraftDoorService service = level instanceof ServerLevel serverLevel ? MinecraftDoorService.forServer(serverLevel.getServer()) : null;
            return service != null && service.recipes().matches(product, input);
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            return mint(product);
        }

        @Override
        @SuppressWarnings("unchecked")
        public RecipeSerializer<ProductRecipe> getSerializer() {
            return (RecipeSerializer<ProductRecipe>) SERIALIZERS.get(Identifier.fromNamespaceAndPath("wormholes", product.recipeName()));
        }
    }

    private static final class SkinRecipe extends DoorRecipe {
        private final DoorForm form;

        private SkinRecipe(DoorForm form) {
            this.form = form;
        }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            MinecraftDoorService service = level instanceof ServerLevel serverLevel ? MinecraftDoorService.forServer(serverLevel.getServer()) : null;
            return service != null && service.recipes().matchesSkin(form, input);
        }

        @Override
        public ItemStack assemble(CraftingInput input) {
            return skin(form, input);
        }

        @Override
        @SuppressWarnings("unchecked")
        public RecipeSerializer<SkinRecipe> getSerializer() {
            return (RecipeSerializer<SkinRecipe>) SERIALIZERS.get(skinKey(form));
        }
    }

    record Grid(List<String> rows, Map<Character, Predicate<ItemStack>> ingredients) {
        boolean matches(CraftingInput input) {
            int width = rows.getFirst().length();
            if (input.width() != width || input.height() != rows.size()) {
                return false;
            }
            return matches(input, false) || matches(input, true);
        }

        private boolean matches(CraftingInput input, boolean mirrored) {
            int width = rows.getFirst().length();
            for (int y = 0; y < rows.size(); y++) {
                for (int x = 0; x < width; x++) {
                    char symbol = rows.get(y).charAt(mirrored ? width - x - 1 : x);
                    ItemStack item = input.getItem(x, y);
                    if (symbol == ' ' ? !item.isEmpty() : !ingredients.get(symbol).test(item)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
