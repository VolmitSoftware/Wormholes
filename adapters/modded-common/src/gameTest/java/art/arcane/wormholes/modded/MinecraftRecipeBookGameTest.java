package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorRecipeSpec;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public final class MinecraftRecipeBookGameTest {
    private static final String NAME = "recipe-book";

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final UUID playerId = NameAndId.createOffline(NAME).id();
    private final BlockPos table;
    private boolean permitted = true;
    private AutoCloseable permissions;
    private MinecraftGameTestPlayer connection;
    private ServerPlayer player;
    private CompletableFuture<Void> persisted;
    private RecipeManager loadedRecipes;
    private boolean cleaned;

    private MinecraftRecipeBookGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        server = runtime.server();
        level = helper.getLevel();
        table = helper.absolutePos(new BlockPos(2, 2, 2));
    }

    public static void run(GameTestHelper helper) {
        MinecraftRecipeBookGameTest test = new MinecraftRecipeBookGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException failure) {
            test.cleanup();
            throw failure;
        }
    }

    private void start() {
        runtime.schedule(this::cleanup, 1150);
        permissions = runtime.access().register((actor, node) -> !actor.getUUID().equals(playerId) || !node.equals(DoorAccessPolicy.CRAFT_NODE)
            ? MinecraftAccessService.Decision.UNSET : permitted ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.DENY);
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            RecipeHolder<?> holder = registered(MinecraftDoorRecipes.key(product));
            helper.assertTrue(holder.value() instanceof MinecraftDoorRecipes.DoorRecipe, "Door recipe has the wrong type: " + holder.id());
        }
        for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
            RecipeHolder<?> holder = registered(key);
            helper.assertTrue(!holder.value().isSpecial() && !holder.value().placementInfo().isImpossibleToPlace(),
                "Door recipe is hidden from the recipe book: " + key);
            helper.assertTrue(displays(key).size() == 1, "Door recipe has no recipe-book display: " + key);
        }
        helper.assertTrue(runtime.doors().recipes().registered().size() == MinecraftDoorRecipes.keys().size(),
            "Enabled door recipes were not all registered");
        level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        connection = MinecraftGameTestPlayer.connect(runtime, level, NAME);
        player = connection.player();
        player.setPos(table.getX() + 0.5, table.getY(), table.getZ() + 1.5);
        assertBook(MinecraftDoorRecipes.keys(), List.of());
        Set<RecipeDisplayId> added = addedDisplays(connection.drainPackets());
        for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
            helper.assertTrue(added.containsAll(displays(key)), "Join did not send the door recipe to the client book: " + key);
        }
        for (DoorCraftProduct product : DoorCraftProduct.values()) {
            autofillProduct(product);
        }
        autofillMaximum();
        for (DoorForm form : DoorForm.values()) {
            autofillSkin(form, openTable());
            autofillSkin(form, player.inventoryMenu);
        }
        rejectMissingRune();
        rejectOversizedInventoryPlacement();
        LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS recipe_book_runtime registered unlocked autofill_products autofill_maximum autofill_skins ghost_missing_rune");
        permitted = false;
        command("wormholes reload");
        helper.startSequence()
            .thenWaitUntil(() -> assertBook(List.of(), MinecraftDoorRecipes.keys()))
            .thenIdle(20)
            .thenExecute(this::assertRevoked)
            .thenExecute(() -> {
                permitted = true;
                runtime.configuration().settings().getRecipes().pairKit.enabled = false;
                persisted = runtime.configuration().persist();
            })
            .thenWaitUntil(() -> helper.assertTrue(persisted.isDone(), "Disabled recipe setting was not saved"))
            .thenExecute(() -> {
                persisted.join();
                command("wormholes reload");
            })
            .thenWaitUntil(() -> helper.assertTrue(server.getRecipeManager().byKey(MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT)).isEmpty(),
                "Disabled pair kit recipe stayed registered"))
            .thenIdle(20)
            .thenExecute(this::assertDisabled)
            .thenExecute(() -> {
                runtime.configuration().settings().getRecipes().pairKit.enabled = true;
                persisted = runtime.configuration().persist();
            })
            .thenWaitUntil(() -> helper.assertTrue(persisted.isDone(), "Enabled recipe setting was not saved"))
            .thenExecute(() -> {
                persisted.join();
                command("wormholes reload");
            })
            .thenWaitUntil(() -> helper.assertTrue(server.getRecipeManager().byKey(MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT)).isPresent(),
                "Re-enabled pair kit recipe was not registered"))
            .thenIdle(20)
            .thenExecute(() -> {
                assertBook(MinecraftDoorRecipes.keys(), List.of());
                helper.assertTrue(addedDisplays(connection.drainPackets()).containsAll(displays(MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT))),
                    "Re-enabled pair kit recipe was not sent to the client book");
                loadedRecipes = server.getRecipeManager();
                command("reload");
            })
            .thenWaitUntil(() -> helper.assertTrue(server.getRecipeManager() != loadedRecipes, "Datapack reload did not finish"))
            .thenExecute(() -> {
                for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
                    helper.assertTrue(registered(key).value() instanceof MinecraftDoorRecipes.DoorRecipe && displays(key).size() == 1,
                        "Datapack reload dropped the door recipe: " + key);
                }
                assertBook(MinecraftDoorRecipes.keys(), List.of());
                Set<RecipeDisplayId> resent = addedDisplays(connection.drainPackets());
                for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
                    helper.assertTrue(resent.containsAll(displays(key)), "Datapack reload did not resend the door recipe: " + key);
                }
                autofillProduct(DoorCraftProduct.PERSONAL_TRAPDOOR);
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS recipe_book_runtime permission_resync disabled_absent reenabled_unlocked datapack_reload");
                cleanup();
            })
            .thenSucceed();
    }

    private void autofillProduct(DoorCraftProduct product) {
        clearInventory();
        DoorRecipeSpec spec = product.defaultSpec();
        give(ingredients(spec, 1));
        give(List.of(new ItemStack(Items.DARK_PRISMARINE, 4), MinecraftDoorItems.door(DoorItemIdentity.newPersonal(product.form()))));
        CraftingMenu menu = openTable();
        place(menu, MinecraftDoorRecipes.key(product), false);
        helper.assertTrue(gridCount(menu, MinecraftDoorItems::isWormholeRune) == symbolCount(spec, "#wormhole-rune"),
            "Autofill did not move the exact Wormhole Runes for " + product);
        helper.assertTrue(gridCount(menu, item -> item.is(Items.DARK_PRISMARINE) && !MinecraftDoorItems.isWormholeRune(item)) == 0,
            "Autofill used ordinary dark prismarine as a Wormhole Rune for " + product);
        helper.assertTrue(gridCount(menu, item -> MinecraftDoorItems.identity(item).isPresent()) == 0,
            "Autofill consumed a bound dimensional door as a plain ingredient for " + product);
        ItemStack autofilled = take(menu);
        helper.assertTrue(gridCount(menu, item -> !item.isEmpty()) == 0, "Autofilled craft did not consume exactly one recipe for " + product);
        helper.assertTrue(inventoryCount(item -> item.is(Items.DARK_PRISMARINE) && !MinecraftDoorItems.isWormholeRune(item)) == 4
            && inventoryCount(item -> MinecraftDoorItems.identity(item).isPresent()) == 1, "Autofill touched decoy items for " + product);
        fill(menu, spec);
        ItemStack manual = menu.getResultSlot().getItem().copy();
        clearGrid(menu);
        assertSameProduct(product, autofilled, manual);
        player.closeContainer();
    }

    private void autofillMaximum() {
        clearInventory();
        DoorRecipeSpec spec = DoorCraftProduct.PAIR_KIT.defaultSpec();
        give(ingredients(spec, 2));
        CraftingMenu menu = openTable();
        place(menu, MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT), true);
        for (Slot slot : menu.getInputGridSlots()) {
            helper.assertTrue(slot.getItem().isEmpty() || slot.getItem().getCount() == 2, "Maximum autofill did not place two crafts per slot");
        }
        helper.assertTrue(MinecraftDoorItems.kit(take(menu)).isPresent(), "Maximum autofill did not produce a pair kit");
        helper.assertTrue(gridCount(menu, item -> !item.isEmpty()) == symbolCount(spec, null), "Maximum autofill craft consumed more than one recipe");
        clearGrid(menu);
        player.closeContainer();
    }

    private void autofillSkin(DoorForm form, AbstractCraftingMenu menu) {
        clearInventory();
        DoorItemIdentity identity = DoorItemIdentity.newPersonal(form);
        ItemStack source = MinecraftDoorItems.door(identity);
        Item target = form == DoorForm.DOOR ? Items.BIRCH_DOOR : Items.BIRCH_TRAPDOOR;
        give(List.of(new ItemStack(source.getItem()), source.copy(), new ItemStack(target)));
        place(menu, MinecraftDoorRecipes.skinKey(form), false);
        helper.assertTrue(gridCount(menu, item -> MinecraftDoorItems.identity(item).filter(identity::equals).isPresent()) == 1
            && gridCount(menu, item -> item.is(target)) == 1, "Skin autofill did not place the dimensional door and its new skin for " + form);
        ItemStack autofilled = take(menu);
        helper.assertTrue(autofilled.is(target) && MinecraftDoorItems.identity(autofilled).filter(identity::equals).isPresent(),
            "Skin autofill did not keep the door identity for " + form);
        helper.assertTrue(inventoryCount(item -> item.is(source.getItem()) && MinecraftDoorItems.identity(item).isEmpty()) == 1,
            "Skin autofill consumed the same-material decoy for " + form);
        menu.getInputGridSlots().get(0).set(source.copy());
        menu.getInputGridSlots().get(1).set(new ItemStack(target));
        ItemStack manual = menu.getResultSlot().getItem().copy();
        clearGrid(menu);
        helper.assertTrue(ItemStack.isSameItemSameComponents(autofilled, manual), "Skin autofill result differs from manual crafting for " + form);
        if (menu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private void rejectMissingRune() {
        clearInventory();
        List<ItemStack> ingredients = new ArrayList<>(ingredients(DoorCraftProduct.PAIR_KIT.defaultSpec(), 1));
        ingredients.removeIf(MinecraftDoorItems::isWormholeRune);
        ingredients.add(new ItemStack(Items.DARK_PRISMARINE));
        give(ingredients);
        CraftingMenu menu = openTable();
        connection.drainPackets();
        place(menu, MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT), false);
        helper.assertTrue(gridCount(menu, item -> !item.isEmpty()) == 0 && menu.getResultSlot().getItem().isEmpty(),
            "Autofill accepted ordinary dark prismarine as a Wormhole Rune");
        boolean ghost = false;
        for (Object packet : connection.drainPackets()) {
            ghost |= packet instanceof ClientboundPlaceGhostRecipePacket placed && placed.containerId() == menu.containerId;
        }
        helper.assertTrue(ghost, "Missing rune autofill did not show the ghost recipe");
        player.closeContainer();
    }

    private void rejectOversizedInventoryPlacement() {
        clearInventory();
        give(ingredients(DoorCraftProduct.PUBLIC_DOOR.defaultSpec(), 1));
        place(player.inventoryMenu, MinecraftDoorRecipes.key(DoorCraftProduct.PUBLIC_DOOR), false);
        helper.assertTrue(gridCount(player.inventoryMenu, item -> !item.isEmpty()) == 0,
            "A three-row door recipe was placed into the two-by-two inventory grid");
        clearInventory();
    }

    private void assertRevoked() {
        helper.assertTrue(runtime.doors().recipes().registered().size() == MinecraftDoorRecipes.keys().size(),
            "Revoking one player's permission removed server recipes");
        Set<RecipeDisplayId> removed = new HashSet<>();
        for (Object packet : connection.drainPackets()) {
            if (packet instanceof ClientboundRecipeBookRemovePacket remove) {
                removed.addAll(remove.recipes());
            }
        }
        for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
            helper.assertTrue(removed.containsAll(displays(key)), "Revoked door recipe was not removed from the client book: " + key);
        }
        CraftingMenu menu = openTable();
        fill(menu, DoorCraftProduct.PERSONAL_DOOR.defaultSpec());
        helper.assertTrue(MinecraftDoorItems.identity(menu.getResultSlot().getItem()).isPresent(),
            "Manual crafting no longer matched the registered personal door recipe");
        clearGrid(menu);
        player.closeContainer();
    }

    private void assertDisabled() {
        ResourceKey<Recipe<?>> pair = MinecraftDoorRecipes.key(DoorCraftProduct.PAIR_KIT);
        List<ResourceKey<Recipe<?>>> remaining = new ArrayList<>(MinecraftDoorRecipes.keys());
        remaining.remove(pair);
        assertBook(remaining, List.of(pair));
        helper.assertTrue(runtime.doors().recipes().registered().size() == remaining.size(), "Disabled pair kit recipe is still registered");
        helper.assertTrue(displays(pair).isEmpty(), "Disabled pair kit recipe still has a recipe-book display");
        Set<RecipeDisplayId> added = addedDisplays(connection.drainPackets());
        for (ResourceKey<Recipe<?>> key : remaining) {
            helper.assertTrue(added.containsAll(displays(key)), "Restored permission did not resend the door recipe: " + key);
        }
        CraftingMenu menu = openTable();
        fill(menu, DoorCraftProduct.PAIR_KIT.defaultSpec());
        helper.assertTrue(menu.getResultSlot().getItem().isEmpty(), "Disabled pair kit recipe still crafted");
        clearGrid(menu);
        player.closeContainer();
    }

    private void assertSameProduct(DoorCraftProduct product, ItemStack autofilled, ItemStack manual) {
        helper.assertTrue(autofilled.is(manual.getItem()) && Objects.equals(autofilled.get(DataComponents.CUSTOM_NAME), manual.get(DataComponents.CUSTOM_NAME)),
            "Autofilled " + product + " differs from manual crafting");
        switch (product.kind()) {
            case PAIR -> {
                MinecraftDoorItems.PairKit filled = MinecraftDoorItems.kit(autofilled).orElse(null);
                MinecraftDoorItems.PairKit crafted = MinecraftDoorItems.kit(manual).orElse(null);
                helper.assertTrue(filled != null && crafted != null && filled.form() == product.form() && crafted.form() == product.form()
                    && !filled.kitId().equals(crafted.kitId()), "Autofilled " + product + " kit differs from manual crafting");
            }
            case PERSONAL, PUBLIC -> {
                DoorItemIdentity filled = MinecraftDoorItems.identity(autofilled).orElse(null);
                DoorItemIdentity crafted = MinecraftDoorItems.identity(manual).orElse(null);
                helper.assertTrue(filled != null && crafted != null && filled.kind() == product.kind() && crafted.kind() == product.kind()
                    && filled.form() == product.form() && crafted.form() == product.form() && !filled.itemId().equals(crafted.itemId()),
                    "Autofilled " + product + " identity differs from manual crafting");
            }
            case RETURN -> throw new IllegalStateException("Return doors are not crafted");
        }
    }

    private void assertBook(List<ResourceKey<Recipe<?>>> present, List<ResourceKey<Recipe<?>>> absent) {
        for (ResourceKey<Recipe<?>> key : present) {
            helper.assertTrue(player.getRecipeBook().contains(key), "Recipe book is missing " + key);
        }
        for (ResourceKey<Recipe<?>> key : absent) {
            helper.assertTrue(!player.getRecipeBook().contains(key), "Recipe book still contains " + key);
        }
    }

    private RecipeHolder<?> registered(ResourceKey<Recipe<?>> key) {
        Optional<RecipeHolder<?>> holder = server.getRecipeManager().byKey(key);
        helper.assertTrue(holder.isPresent(), "Door recipe is missing from the server recipe manager: " + key);
        return holder.orElseThrow();
    }

    private List<RecipeDisplayId> displays(ResourceKey<Recipe<?>> key) {
        List<RecipeDisplayId> ids = new ArrayList<>(1);
        server.getRecipeManager().listDisplaysForRecipe(key, entry -> ids.add(entry.id()));
        return ids;
    }

    private static Set<RecipeDisplayId> addedDisplays(List<Object> packets) {
        Set<RecipeDisplayId> ids = new HashSet<>();
        for (Object packet : packets) {
            if (packet instanceof ClientboundRecipeBookAddPacket add) {
                for (ClientboundRecipeBookAddPacket.Entry entry : add.entries()) {
                    ids.add(entry.contents().id());
                }
            }
        }
        return ids;
    }

    private void place(AbstractCraftingMenu menu, ResourceKey<Recipe<?>> key, boolean maximum) {
        List<RecipeDisplayId> ids = displays(key);
        helper.assertTrue(ids.size() == 1, "Door recipe has no display to place: " + key);
        player.connection.handlePlaceRecipe(new ServerboundPlaceRecipePacket(menu.containerId, ids.getFirst(), maximum));
    }

    private CraftingMenu openTable() {
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CraftingMenu(id, inventory, ContainerLevelAccess.create(level, table)),
            Component.literal("Crafting")));
        helper.assertTrue(player.containerMenu instanceof CraftingMenu, "Crafting table menu did not open");
        return (CraftingMenu) player.containerMenu;
    }

    private ItemStack take(AbstractCraftingMenu menu) {
        menu.clicked(0, 0, ContainerInput.PICKUP, player);
        ItemStack carried = menu.getCarried().copy();
        menu.setCarried(ItemStack.EMPTY);
        helper.assertTrue(!carried.isEmpty(), "Autofilled recipe produced no result");
        return carried;
    }

    private void fill(CraftingMenu menu, DoorRecipeSpec spec) {
        List<String> rows = spec.shape().rows();
        for (int y = 0; y < rows.size(); y++) {
            for (int x = 0; x < rows.get(y).length(); x++) {
                char symbol = rows.get(y).charAt(x);
                if (symbol != ' ') {
                    menu.getInputGridSlots().get(x + y * 3).set(sample(spec.ingredients().get(symbol)));
                }
            }
        }
    }

    private void clearGrid(AbstractCraftingMenu menu) {
        for (Slot slot : menu.getInputGridSlots()) {
            slot.set(ItemStack.EMPTY);
        }
        menu.getResultSlot().set(ItemStack.EMPTY);
    }

    private static List<ItemStack> ingredients(DoorRecipeSpec spec, int sets) {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (String row : spec.shape().rows()) {
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) != ' ') {
                    counts.merge(row.charAt(x), 1, Integer::sum);
                }
            }
        }
        List<ItemStack> stacks = new ArrayList<>(counts.size());
        for (Map.Entry<Character, Integer> count : counts.entrySet()) {
            stacks.add(sample(spec.ingredients().get(count.getKey())).copyWithCount(count.getValue() * sets));
        }
        return stacks;
    }

    private static int symbolCount(DoorRecipeSpec spec, String token) {
        int count = 0;
        for (String row : spec.shape().rows()) {
            for (int x = 0; x < row.length(); x++) {
                char symbol = row.charAt(x);
                if (symbol != ' ' && (token == null || spec.ingredients().get(symbol).equals(token))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ItemStack sample(String token) {
        return switch (token) {
            case "#wormhole-rune" -> MinecraftDoorItems.wormholeRune();
            case "#doors" -> new ItemStack(Items.OAK_DOOR);
            case "#trapdoors" -> new ItemStack(Items.OAK_TRAPDOOR);
            default -> new ItemStack(BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("minecraft", token.toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalStateException("Unknown recipe sample " + token)));
        };
    }

    private int gridCount(AbstractCraftingMenu menu, Predicate<ItemStack> filter) {
        int count = 0;
        for (Slot slot : menu.getInputGridSlots()) {
            if (!slot.getItem().isEmpty() && filter.test(slot.getItem())) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    private int inventoryCount(Predicate<ItemStack> filter) {
        int count = 0;
        for (ItemStack item : player.getInventory().getNonEquipmentItems()) {
            if (!item.isEmpty() && filter.test(item)) {
                count += item.getCount();
            }
        }
        return count;
    }

    private void give(List<ItemStack> items) {
        for (ItemStack item : items) {
            helper.assertTrue(player.getInventory().add(item.copy()), "Test player inventory is full");
        }
    }

    private void clearInventory() {
        player.getInventory().clearContent();
    }

    private void command(String input) {
        try {
            server.getCommands().getDispatcher().execute(input, server.createCommandSourceStack());
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("Could not run recipe book command: " + input, failure);
        }
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        runtime.configuration().settings().getRecipes().pairKit.enabled = true;
        if (player != null) {
            player.closeContainer();
            player.getInventory().clearContent();
        }
        if (connection != null) {
            connection.close();
        }
        if (permissions != null) {
            try {
                permissions.close();
            } catch (Exception failure) {
                LoggerFactory.getLogger("WormholesGameTest").error("Could not remove recipe book permissions", failure);
            }
        }
        level.setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
    }
}
