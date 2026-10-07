package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.door.DoorAccessPolicy;
import art.arcane.wormholes.door.DoorCraftProduct;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorRecipeSpec;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import art.arcane.wormholes.localization.WormholesMessages;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final String ITEMS_NODE = "wormholes.admin.items";
    private static final String TEST_LOCALE = "de_DE";
    private static final String TEST_WAND_NAME = "Testwand";

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
    private String originalLanguage;
    private Path languageFile;
    private String originalLanguageFile;
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
        RuntimeBaselineEnvironment.cleanupOnTeardown(this::cleanup);
        permissions = runtime.access().register((actor, node) -> !actor.getUUID().equals(playerId) ? MinecraftAccessService.Decision.UNSET
            : node.equals(ITEMS_NODE) ? MinecraftAccessService.Decision.ALLOW
            : !node.equals(DoorAccessPolicy.CRAFT_NODE) ? MinecraftAccessService.Decision.UNSET
            : permitted ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.DENY);
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
        helper.assertTrue(runtime.recipeBook().doorRecipes().size() == MinecraftDoorRecipes.keys().size(),
            "Enabled door recipes were not all registered");
        RecipeHolder<?> wand = registered(MinecraftPortalTools.WAND_RECIPE);
        helper.assertTrue(wand.value() instanceof ShapedRecipe && !(wand.value() instanceof MinecraftDoorRecipes.DoorRecipe)
            && !wand.value().isSpecial() && displays(MinecraftPortalTools.WAND_RECIPE).size() == 1, "Portal Wand recipe is not a recipe-book recipe");
        helper.assertTrue(runtime.recipeBook().sharedRecipes().contains(wand), "Portal Wand recipe is not shared with every player");
        level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        connection = MinecraftGameTestPlayer.connect(runtime, level, NAME);
        player = connection.player();
        player.setPos(table.getX() + 0.5, table.getY(), table.getZ() + 1.5);
        assertBook(MinecraftRecipeBook.keys(), List.of());
        Set<RecipeDisplayId> added = addedDisplays(connection.drainPackets());
        for (ResourceKey<Recipe<?>> key : MinecraftRecipeBook.keys()) {
            helper.assertTrue(added.containsAll(displays(key)), "Join did not send the recipe to the client book: " + key);
        }
        autofillWand(openTable());
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
        disableDoorsKeepsWand();
        LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS recipe_book_runtime registered unlocked autofill_wand autofill_products autofill_maximum autofill_skins ghost_missing_rune doors_disabled_wand_kept");
        permitted = false;
        command("wormholes reload");
        helper.startSequence()
            .thenWaitUntil(() -> assertBook(List.of(MinecraftPortalTools.WAND_RECIPE), MinecraftDoorRecipes.keys()))
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
                assertBook(MinecraftRecipeBook.keys(), List.of());
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
                helper.assertTrue(displays(MinecraftPortalTools.WAND_RECIPE).size() == 1, "Datapack reload dropped the Portal Wand recipe");
                assertBook(MinecraftRecipeBook.keys(), List.of());
                Set<RecipeDisplayId> resent = addedDisplays(connection.drainPackets());
                for (ResourceKey<Recipe<?>> key : MinecraftRecipeBook.keys()) {
                    helper.assertTrue(resent.containsAll(displays(key)), "Datapack reload did not resend the recipe: " + key);
                }
                autofillProduct(DoorCraftProduct.PERSONAL_TRAPDOOR);
                autofillWand(openTable());
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS recipe_book_runtime permission_resync disabled_absent reenabled_unlocked datapack_reload");
                originalLanguage = runtime.configuration().settings().getLanguage();
                installTestLanguage();
                persisted = runtime.configuration().setLanguage(TEST_LOCALE);
            })
            .thenWaitUntil(() -> helper.assertTrue(persisted.isDone(), "Test language setting was not saved"))
            .thenExecute(() -> {
                persisted.join();
                connection.drainPackets();
                command("wormholes reload");
            })
            .thenWaitUntil(() -> helper.assertTrue(TEST_WAND_NAME.equals(wandResult().getHoverName().getString()),
                "Language reload did not rebuild the Portal Wand recipe result"))
            .thenExecute(() -> {
                ItemStack resent = ItemStack.EMPTY;
                for (Object packet : connection.drainPackets()) {
                    if (packet instanceof ClientboundRecipeBookAddPacket add) {
                        for (ClientboundRecipeBookAddPacket.Entry entry : add.entries()) {
                            if (entry.contents().display().result() instanceof SlotDisplay.ItemStackSlotDisplay result
                                && MinecraftPortalTools.isWand(result.stack().create())) {
                                resent = result.stack().create();
                            }
                        }
                    }
                }
                helper.assertTrue(TEST_WAND_NAME.equals(resent.getHoverName().getString()),
                    "Language reload did not resend the localized Portal Wand to the client recipe book");
                ItemStack localized = autofillWand(openTable());
                helper.assertTrue(TEST_WAND_NAME.equals(localized.getHoverName().getString()), "The /wormholes wand item ignored the server language");
                persisted = runtime.configuration().setLanguage(originalLanguage);
            })
            .thenWaitUntil(() -> helper.assertTrue(persisted.isDone(), "Original language setting was not saved"))
            .thenExecute(() -> {
                persisted.join();
                command("wormholes reload");
            })
            .thenWaitUntil(() -> helper.assertTrue(!TEST_WAND_NAME.equals(wandResult().getHoverName().getString()),
                "Restoring the language did not rebuild the Portal Wand recipe result"))
            .thenExecute(() -> {
                restoreTestLanguage();
                autofillWand(openTable());
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS recipe_book_runtime bukkit_tool_items language_rebuild command_wand_matches_recipe");
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

    private ItemStack autofillWand(CraftingMenu menu) {
        clearInventory();
        playerCommand("wormholes wand true");
        ItemStack commanded = only(MinecraftPortalTools::isWand);
        assertBukkitTool(commanded, Items.BLAZE_ROD, WormholesMessages.ITEM_PORTAL_WAND);
        assertBukkitTool(only(MinecraftDoorItems::isWormholeRune), Items.DARK_PRISMARINE, WormholesMessages.ITEM_WORMHOLE_RUNE);
        clearInventory();
        give(List.of(new ItemStack(Items.GLOWSTONE_DUST, 3), new ItemStack(Items.BLAZE_ROD), commanded));
        place(menu, MinecraftPortalTools.WAND_RECIPE, false);
        helper.assertTrue(gridCount(menu, item -> item.is(Items.GLOWSTONE_DUST)) == 3
            && gridCount(menu, item -> item.is(Items.BLAZE_ROD) && !MinecraftPortalTools.isWand(item)) == 1
            && gridCount(menu, MinecraftPortalTools::isWand) == 0, "Autofill did not place the Portal Wand ingredients");
        ItemStack autofilled = take(menu);
        helper.assertTrue(MinecraftPortalTools.isWand(autofilled) && ItemStack.isSameItemSameComponents(autofilled, commanded),
            "Autofilled Portal Wand differs from the /wormholes wand item");
        helper.assertTrue(ItemStack.isSameItemSameComponents(autofilled, wandResult()), "Autofilled Portal Wand differs from the recipe-book result");
        helper.assertTrue(gridCount(menu, item -> !item.isEmpty()) == 0 && inventoryCount(MinecraftPortalTools::isWand) == 1,
            "Portal Wand autofill consumed the wrong items");
        menu.getInputGridSlots().get(0).set(new ItemStack(Items.GLOWSTONE_DUST));
        menu.getInputGridSlots().get(2).set(new ItemStack(Items.GLOWSTONE_DUST));
        menu.getInputGridSlots().get(4).set(new ItemStack(Items.BLAZE_ROD));
        menu.getInputGridSlots().get(7).set(new ItemStack(Items.GLOWSTONE_DUST));
        ItemStack manual = menu.getResultSlot().getItem().copy();
        clearGrid(menu);
        helper.assertTrue(ItemStack.isSameItemSameComponents(autofilled, manual), "Autofilled Portal Wand differs from manual crafting");
        player.closeContainer();
        return autofilled;
    }

    private void assertBukkitTool(ItemStack item, Item material, LinesKey name) {
        String legacy = WormholesMessageRenderer.legacyLines(runtime.localization().snapshot(null).resolve(name, MessageArgs.empty())).getFirst();
        Holder<Enchantment> infinity = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY);
        helper.assertTrue(item.is(material) && item.getCount() == 1, "Wormholes tool has the wrong item: " + item);
        helper.assertTrue(MinecraftLegacyText.component(legacy).equals(item.get(DataComponents.CUSTOM_NAME)),
            "Wormholes tool name differs from the server language: " + item.get(DataComponents.CUSTOM_NAME));
        helper.assertTrue(item.getEnchantments().size() == 1 && item.getEnchantments().getLevel(infinity) == 1
            && item.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).shows(DataComponents.ENCHANTMENTS)
            && !item.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "Wormholes tool does not carry a visible Infinity enchantment: " + item);
        helper.assertTrue(item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty(), "Wormholes tool has lore: " + item);
        List<String> tooltip = new ArrayList<>(2);
        for (Component line : item.getTooltipLines(Item.TooltipContext.of(server.registryAccess()), player, TooltipFlag.NORMAL)) {
            tooltip.add(line.getString());
        }
        helper.assertTrue(tooltip.equals(List.of(item.getHoverName().getString(), Enchantment.getFullname(infinity, 1).getString())),
            "Wormholes tool tooltip differs from the Bukkit item: " + tooltip);
    }

    private ItemStack wandResult() {
        List<ItemStack> results = new ArrayList<>(1);
        server.getRecipeManager().listDisplaysForRecipe(MinecraftPortalTools.WAND_RECIPE, entry -> {
            if (entry.display().result() instanceof SlotDisplay.ItemStackSlotDisplay result) {
                results.add(result.stack().create());
            }
        });
        helper.assertTrue(results.size() == 1, "Portal Wand recipe has no item result");
        return results.getFirst();
    }

    private ItemStack only(Predicate<ItemStack> filter) {
        ItemStack found = ItemStack.EMPTY;
        for (ItemStack item : player.getInventory().getNonEquipmentItems()) {
            if (!item.isEmpty() && filter.test(item)) {
                helper.assertTrue(found.isEmpty(), "The /wormholes wand command gave duplicate items");
                found = item.copy();
            }
        }
        helper.assertTrue(!found.isEmpty(), "The /wormholes wand command did not give the expected item");
        return found;
    }

    private void installTestLanguage() {
        languageFile = server.getServerDirectory().resolve("config/wormholes/languages/" + TEST_LOCALE + ".toml");
        try {
            originalLanguageFile = Files.exists(languageFile) ? Files.readString(languageFile) : null;
            Files.createDirectories(languageFile.getParent());
            Files.writeString(languageFile, "[item]\nportal_wand = [\"&b&l" + TEST_WAND_NAME + "&r\"]\n");
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write the test language file", failure);
        }
    }

    private void restoreTestLanguage() {
        if (languageFile == null) {
            return;
        }
        try {
            if (originalLanguageFile == null) {
                Files.deleteIfExists(languageFile);
            } else {
                Files.writeString(languageFile, originalLanguageFile);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not restore the test language file", failure);
        }
        languageFile = null;
    }

    private void disableDoorsKeepsWand() {
        runtime.configuration().settings().getMain().dimensionalDoorsEnabled = false;
        try {
            runtime.recipeBook().refresh();
            helper.assertTrue(runtime.recipeBook().doorRecipes().isEmpty() && displays(MinecraftPortalTools.WAND_RECIPE).size() == 1,
                "Disabling Dimensional Doors did not leave only the Portal Wand recipe");
            for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
                helper.assertTrue(server.getRecipeManager().byKey(key).isEmpty(), "Disabled Dimensional Doors kept the recipe " + key);
            }
            assertBook(List.of(MinecraftPortalTools.WAND_RECIPE), MinecraftDoorRecipes.keys());
        } finally {
            runtime.configuration().settings().getMain().dimensionalDoorsEnabled = true;
            runtime.recipeBook().refresh();
        }
        assertBook(MinecraftRecipeBook.keys(), List.of());
        connection.drainPackets();
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
        helper.assertTrue(runtime.recipeBook().doorRecipes().size() == MinecraftDoorRecipes.keys().size(),
            "Revoking one player's permission removed server recipes");
        List<Object> packets = connection.drainPackets();
        Set<RecipeDisplayId> removed = new HashSet<>();
        for (Object packet : packets) {
            if (packet instanceof ClientboundRecipeBookRemovePacket remove) {
                removed.addAll(remove.recipes());
            }
        }
        for (ResourceKey<Recipe<?>> key : MinecraftDoorRecipes.keys()) {
            helper.assertTrue(removed.containsAll(displays(key)), "Revoked door recipe was not removed from the client book: " + key);
        }
        List<RecipeDisplayId> wand = displays(MinecraftPortalTools.WAND_RECIPE);
        helper.assertTrue(addedDisplays(packets).containsAll(wand) && !removed.containsAll(wand),
            "Revoking door crafting removed the Portal Wand recipe from the client book");
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
        helper.assertTrue(runtime.recipeBook().doorRecipes().size() == remaining.size(), "Disabled pair kit recipe is still registered");
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

    private List<ItemStack> ingredients(DoorRecipeSpec spec, int sets) {
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

    private ItemStack sample(String token) {
        return switch (token) {
            case "#wormhole-rune" -> MinecraftPortalItems.of(runtime).wormholeRune();
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

    private void playerCommand(String input) {
        try {
            helper.assertTrue(server.getCommands().getDispatcher().execute(input, player.createCommandSourceStack()) == 1,
                "Player command failed: " + input);
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("Could not run player command: " + input, failure);
        }
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        runtime.configuration().settings().getRecipes().pairKit.enabled = true;
        if (languageFile != null) {
            restoreTestLanguage();
            runtime.configuration().setLanguage(originalLanguage);
            command("wormholes reload");
        }
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
