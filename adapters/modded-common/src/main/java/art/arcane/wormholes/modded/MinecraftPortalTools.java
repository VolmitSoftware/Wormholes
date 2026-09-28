package art.arcane.wormholes.modded;

import art.arcane.wormholes.WandSelectionGeometry;
import art.arcane.wormholes.portal.PortalType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import art.arcane.wormholes.localization.WormholesMessages;
import java.util.UUID;

public final class MinecraftPortalTools implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    static final CompoundTag WAND_IDENTITY = wandIdentity();
    public static final ResourceKey<Recipe<?>> WAND_RECIPE = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("wormholes", "portal_wand"));

    private final WormholesModRuntime runtime;
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Long> blockInputs = new HashMap<>();

    public MinecraftPortalTools(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes")
            .then(Commands.literal("edit").executes(context -> runtime.menus().openSelection(context.getSource().getPlayerOrException()))
                .then(Commands.argument("portal", UuidArgument.uuid()).executes(context ->
                    runtime.menus().open(context.getSource().getPlayerOrException(), UuidArgument.getUuid(context, "portal")))))
            .then(Commands.literal("transfer").then(Commands.argument("portal", UuidArgument.uuid())
                .then(Commands.argument("owner", UuidArgument.uuid()).executes(context -> runtime.menus().transfer(
                    context.getSource().getPlayerOrException(), UuidArgument.getUuid(context, "portal"), UuidArgument.getUuid(context, "owner")) ? 1 : 0))))
            .then(Commands.literal("wand").requires(source -> runtime.access().permission(source, "wormholes.admin.items"))
                .executes(context -> giveWand(context.getSource(), true))
                .then(Commands.argument("rune", BoolArgumentType.bool()).executes(context ->
                    giveWand(context.getSource(), BoolArgumentType.getBool(context, "rune")))))
            .then(Commands.literal("build").requires(this::canBuild)
                .executes(context -> build(context.getSource().getPlayerOrException(), PortalType.PORTAL))
                .then(Commands.argument("type", StringArgumentType.word()).suggests((context, suggestions) -> {
                    for (PortalType type : PortalType.values()) {
                        suggestions.suggest(type.name().toLowerCase(Locale.ROOT));
                    }
                    return suggestions.buildFuture();
                }).executes(context -> buildType(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "type")))))
            .then(Commands.literal("portals").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("info").then(Commands.argument("portal", UuidArgument.uuid())
                    .executes(context -> info(context.getSource(), UuidArgument.getUuid(context, "portal")))))
                .then(Commands.literal("retarget").then(Commands.argument("portal", UuidArgument.uuid())
                    .then(Commands.argument("destination", UuidArgument.uuid()).executes(context ->
                        link(context.getSource(), UuidArgument.getUuid(context, "portal"), UuidArgument.getUuid(context, "destination"))))))
                .then(Commands.literal("unlink").then(Commands.argument("portal", UuidArgument.uuid())
                    .executes(context -> link(context.getSource(), UuidArgument.getUuid(context, "portal"), null))))
                .then(Commands.literal("remove").then(Commands.argument("portal", UuidArgument.uuid())
                    .executes(context -> remove(context.getSource(), UuidArgument.getUuid(context, "portal")))))
                .then(Commands.literal("name").then(Commands.argument("portal", UuidArgument.uuid())
                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context ->
                        rename(context.getSource(), UuidArgument.getUuid(context, "portal"), StringArgumentType.getString(context, "name"))))))));
    }

    public void suppressSwing(ServerPlayer player) {
        blockInputs.put(player.getUUID(), player.level().getGameTime() + 2L);
    }

    public boolean attackAir(ServerPlayer player) {
        Long until = blockInputs.remove(player.getUUID());
        if (until != null && player.level().getGameTime() <= until) {
            return true;
        }
        if (!isWand(player.getMainHandItem())) {
            return false;
        }
        if (MinecraftPortalInteractions.wand(runtime, player, InteractionHand.MAIN_HAND)) {
            return true;
        }
        Selection selection = selections.get(player.getUUID());
        if (selection != null && selection.level == player.level() && selection.complete() && buildClick(player, selection, null)) {
            build(player, PortalType.PORTAL);
            return true;
        }
        return false;
    }

    public boolean attackBlock(ServerPlayer player, BlockPos position) {
        runtime.requireServerThread();
        if (!isWand(player.getMainHandItem())) {
            return false;
        }
        if (!canUse(player)) {
            return true;
        }
        Selection selection = selections.get(player.getUUID());
        if (selection != null && selection.level == player.level() && selection.complete() && buildClick(player, selection, position)) {
            build(player, PortalType.PORTAL);
        } else {
            select(player, position, true);
        }
        return true;
    }

    public boolean useBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        runtime.requireServerThread();
        if (hand != InteractionHand.MAIN_HAND || !isWand(player.getMainHandItem())) {
            return false;
        }
        if (canUse(player)) {
            select(player, hit.getBlockPos(), false);
        }
        return true;
    }

    public void playerDisconnected(ServerPlayer player) {
        runtime.requireServerThread();
        clear(player);
        blockInputs.remove(player.getUUID());
    }

    public void tick() {
        runtime.requireServerThread();
        Iterator<Map.Entry<UUID, Selection>> iterator = selections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Selection> entry = iterator.next();
            ServerPlayer player = runtime.server().getPlayerList().getPlayer(entry.getKey());
            Selection selection = entry.getValue();
            if (player == null || player.hasDisconnected() || player.level() != selection.level || !isWand(player.getMainHandItem())) {
                if (player != null) {
                    selection.preview.remove(player);
                }
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        for (Map.Entry<UUID, Selection> entry : selections.entrySet()) {
            ServerPlayer player = runtime.server().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().preview.remove(player);
            }
        }
        selections.clear();
        blockInputs.clear();
    }

    static RecipeHolder<ShapedRecipe> wandRecipe(MinecraftPortalItems items) {
        ShapedRecipePattern pattern = ShapedRecipePattern.of(Map.of('d', Ingredient.of(Items.GLOWSTONE_DUST), 'r', Ingredient.of(Items.BLAZE_ROD)),
            "d d", " r ", " d ");
        return new RecipeHolder<>(WAND_RECIPE, new ShapedRecipe(new Recipe.CommonInfo(true),
            new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""), pattern, ItemStackTemplate.fromNonEmptyStack(items.wand())));
    }

    static boolean isWand(ItemStack item) {
        return item.is(Items.BLAZE_ROD)
            && item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).matchedBy(WAND_IDENTITY);
    }

    static boolean isPortalTool(ItemStack item) {
        return isWand(item) || MinecraftPortalConstruction.runeType(item).isPresent();
    }

    private static CompoundTag wandIdentity() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("wormholes:wand", true);
        return tag;
    }

    private boolean canBuild(CommandSourceStack source) {
        return runtime.access().permission(source, "wormholes.portals.portal")
            || runtime.access().permission(source, "wormholes.portals.wormhole") || runtime.access().permission(source, "wormholes.gateway");
    }

    private boolean canUse(ServerPlayer player) {
        if (canBuild(player.createCommandSourceStack())) {
            return true;
        }
        player.sendSystemMessage(Component.literal("You do not have permission to use the Wormholes wand."));
        return false;
    }

    private int giveWand(CommandSourceStack source, boolean rune) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftPortalItems items = MinecraftPortalItems.of(runtime);
        if (!player.getInventory().add(items.wand())) {
            source.sendFailure(Component.literal("Your inventory is full."));
            return 0;
        }
        if (rune) {
            ItemStack item = items.wormholeRune();
            if (!player.getInventory().add(item)) {
                player.drop(item, false);
            }
        }
        source.sendSuccess(() -> Component.literal(rune ? "Wormholes wand and rune added to your inventory." : "Wormholes wand added to your inventory."), false);
        return 1;
    }

    private void select(ServerPlayer player, BlockPos position, boolean primary) {
        Selection previous = selections.get(player.getUUID());
        int[] clicked = new int[] {position.getX(), position.getY(), position.getZ()};
        boolean sameWorld = previous != null && previous.level == player.level();
        int[] a = primary ? clicked : sameWorld ? previous.a : null;
        int[] b = primary ? sameWorld ? previous.b : null : clicked;
        clear(player);
        Selection selection = new Selection(player.level(), a, b, new MinecraftWandPreview(player, a, b));
        selections.put(player.getUUID(), selection);
        String message = primary ? "First corner selected." : "Second corner selected.";
        if (selection.complete()) {
            String failure = invalid(selection);
            message = failure == null ? "Selected " + count(selection) + " blocks. Left click the selection to open a portal." : failure;
        }
        player.sendSystemMessage(Component.literal(message));
    }

    private int buildType(ServerPlayer player, String type) {
        PortalType selected;
        try {
            selected = PortalType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            player.sendSystemMessage(Component.literal("Portal type must be portal, wormhole, gateway, or rtp."));
            return 0;
        }
        return build(player, selected);
    }

    private int build(ServerPlayer player, PortalType type) {
        runtime.requireServerThread();
        String permission = switch (type) {
            case GATEWAY -> "wormholes.gateway";
            case WORMHOLE -> "wormholes.portals.wormhole";
            case PORTAL, RTP -> "wormholes.portals.portal";
        };
        if (!runtime.access().permission(player, permission)) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.COMMAND_NO_PERMISSION, Map.of()));
            return 0;
        }
        Selection selection = selections.get(player.getUUID());
        if (selection == null || selection.level != player.level() || !selection.complete()) {
            player.sendSystemMessage(Component.literal("Select both corners with the Wormholes wand first."));
            return 0;
        }
        String failure = invalid(selection);
        if (failure != null) {
            player.sendSystemMessage(Component.literal(failure));
            return 0;
        }
        int[] min = WandSelectionGeometry.selectionMin(selection.a, selection.b);
        int[] max = WandSelectionGeometry.selectionMax(selection.a, selection.b);
        List<BlockPos> cells = new ArrayList<>((int) count(selection));
        for (int x = min[0]; x <= max[0]; x++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int z = min[2]; z <= max[2]; z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        if (!runtime.access().canConstruct(player) || !runtime.access().canPlace(new MinecraftAccessService.Placement(
            player, player.level(), cells, MinecraftAccessService.PlacementKind.WAND))) {
            return 0;
        }
        MinecraftPortal portal;
        try {
            portal = runtime.portals().create(player.getUUID(), player.level(), cells, type, player.getLookAngle());
        } catch (IllegalArgumentException error) {
            LOGGER.warn("Portal selection rejected for player {}", player.getUUID(), error);
            player.sendSystemMessage(Component.literal("The portal could not be opened: " + error.getMessage()));
            return 0;
        }
        if (portal == null) {
            player.sendSystemMessage(Component.literal("The portal could not be opened at this selection."));
            return 0;
        }
        runtime.effects().created(portal, Map.of());
        clear(player);
        player.sendSystemMessage(Component.literal("Opened portal " + portal.getId() + "."));
        return 1;
    }

    private int list(CommandSourceStack source) {
        List<MinecraftPortal> portals = runtime.portals().snapshot();
        for (MinecraftPortal portal : portals) {
            source.sendSuccess(() -> Component.literal(portal.getId() + " " + portal.getName() + " [" + portal.getWorldKey() + "]"), false);
        }
        source.sendSuccess(() -> Component.literal(portals.size() + " portals."), false);
        return portals.size();
    }

    private int info(CommandSourceStack source, UUID id) {
        MinecraftPortal portal = runtime.portals().get(id);
        if (portal == null) {
            source.sendFailure(Component.literal("Portal not found."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(portal.getName() + " (" + id + ")\nWorld: " + portal.getWorldKey()
            + "\nType: " + portal.getType() + "\nDestination: " + portal.getDestinationId()), false);
        return 1;
    }

    private int link(CommandSourceStack source, UUID id, UUID destination) throws CommandSyntaxException {
        boolean changed = runtime.portals().link(source.getPlayerOrException(), id, destination);
        return changed(source, changed, destination == null ? "Portal unlinked." : "Portal destination updated.");
    }

    private int remove(CommandSourceStack source, UUID id) throws CommandSyntaxException {
        return changed(source, runtime.portals().remove(source.getPlayerOrException(), id), "Portal removed.");
    }

    private int rename(CommandSourceStack source, UUID id, String name) throws CommandSyntaxException {
        return changed(source, runtime.portals().update(source.getPlayerOrException(), id, portal -> portal.setName(name)), "Portal renamed.");
    }

    private static int changed(CommandSourceStack source, boolean changed, String message) {
        if (!changed) {
            source.sendFailure(Component.literal("Portal operation failed. Check the portal IDs and your access."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private void clear(ServerPlayer player) {
        Selection previous = selections.remove(player.getUUID());
        if (previous != null) {
            previous.preview.remove(player);
        }
    }

    private static String invalid(Selection selection) {
        int[] min = WandSelectionGeometry.selectionMin(selection.a, selection.b);
        int[] max = WandSelectionGeometry.selectionMax(selection.a, selection.b);
        if (WandSelectionGeometry.flatAxis(min, max) < 0) {
            return "The selection must be flat on one axis.";
        }
        if (WandSelectionGeometry.cellCount(min, max) > WandSelectionGeometry.MAX_DRAWN_CELLS) {
            return "The selection exceeds " + WandSelectionGeometry.MAX_DRAWN_CELLS + " blocks.";
        }
        return null;
    }

    private static long count(Selection selection) {
        return WandSelectionGeometry.cellCount(WandSelectionGeometry.selectionMin(selection.a, selection.b),
            WandSelectionGeometry.selectionMax(selection.a, selection.b));
    }

    private static boolean buildClick(ServerPlayer player, Selection selection, BlockPos position) {
        int[] min = WandSelectionGeometry.selectionMin(selection.a, selection.b);
        int[] max = WandSelectionGeometry.selectionMax(selection.a, selection.b);
        if (position != null && position.getX() >= min[0] && position.getX() <= max[0]
            && position.getY() >= min[1] && position.getY() <= max[1]
            && position.getZ() >= min[2] && position.getZ() <= max[2]) {
            return true;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        return WandSelectionGeometry.rayIntersectsBox(eye.x, eye.y, eye.z, look.x, look.y, look.z,
            min[0], min[1], min[2], max[0] + 1.0, max[1] + 1.0, max[2] + 1.0, 64);
    }

    private record Selection(ServerLevel level, int[] a, int[] b, MinecraftWandPreview preview) {
        boolean complete() {
            return a != null && b != null;
        }
    }
}
