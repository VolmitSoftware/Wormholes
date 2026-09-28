package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HiddenByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class MinecraftMenuParityGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesGameTest");
    private static final String GOLDEN = "/menu-parity/bukkit-menus.json";
    private static final int SETTLE_TICKS = 10;
    private static final int SETUP_TICKS = 4;
    private static final List<String> FLAGS = List.of("bold", "italic", "underlined", "strikethrough", "obfuscated");
    private static final Map<String, String> HEX_COLORS = Map.ofEntries(
        Map.entry("#000000", "black"), Map.entry("#0000aa", "dark_blue"), Map.entry("#00aa00", "dark_green"),
        Map.entry("#00aaaa", "dark_aqua"), Map.entry("#aa0000", "dark_red"), Map.entry("#aa00aa", "dark_purple"),
        Map.entry("#ffaa00", "gold"), Map.entry("#aaaaaa", "gray"), Map.entry("#555555", "dark_gray"),
        Map.entry("#5555ff", "blue"), Map.entry("#55ff55", "green"), Map.entry("#55ffff", "aqua"),
        Map.entry("#ff5555", "red"), Map.entry("#ff55ff", "light_purple"), Map.entry("#ffff55", "yellow"),
        Map.entry("#ffffff", "white"));

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final JsonObject golden;
    private final List<Normalization> normalizations;
    private final ProtocolInfo<ClientGamePacketListener> clientbound;
    private final JsonArray paths;
    private final List<String> report = new ArrayList<>();
    private final List<MinecraftGameTestPlayer> peers = new ArrayList<>();
    private MinecraftGameTestPlayer connection;
    private int pathIndex;
    private int stepIndex;
    private Phase phase = Phase.SETUP;
    private long resumeTick;
    private int containerBefore;
    private int messagesBefore;
    private int opened;
    private Component lastTitle;
    private int windows;
    private int matched;
    private int mismatched;
    private int unreached;
    private boolean finished;
    private boolean reported;

    private MinecraftMenuParityGameTest(GameTestHelper helper, WormholesModRuntime runtime, JsonObject golden) {
        this.helper = helper;
        this.runtime = runtime;
        this.golden = golden;
        normalizations = normalizations(golden.getAsJsonArray("normalizations"));
        paths = golden.getAsJsonArray("paths");
        clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(runtime.server().registryAccess()));
    }

    public static void run(GameTestHelper helper) {
        MinecraftMenuParityGameTest test = new MinecraftMenuParityGameTest(helper, WormholesGameTests.RUNTIME, load());
        helper.onEachTick(test::tick);
    }

    private void tick() {
        if (reported || helper.getTick() < resumeTick) {
            return;
        }
        if (!finished) {
            try {
                switch (phase) {
                    case SETUP -> setup();
                    case ACT -> act();
                    case SETTLE -> settle();
                }
            } catch (RuntimeException error) {
                LOGGER.error("Menu parity replay failed in path {} step {}", pathName(), stepIndex, error);
                report.add("path " + pathName() + " step " + stepIndex + " threw " + error);
                abortPath();
            }
        }
        if (finished) {
            verdict();
        }
    }

    private void verdict() {
        reported = true;
        String summary = "menu parity windows=" + windows + " matched=" + matched + " mismatched=" + mismatched + " unreached=" + unreached;
        if (mismatched > 0 || unreached > 0) {
            helper.fail(summary + " (see WORMHOLES_MENU_PARITY log lines)");
        }
        LOGGER.info("WORMHOLES_GAME_TEST_PASS menu_parity_runtime {}", summary);
        helper.succeed();
    }

    private void setup() {
        teardown();
        if (pathIndex >= paths.size()) {
            finish();
            return;
        }
        stepIndex = 0;
        JsonObject state = path().getAsJsonObject("setup");
        connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), golden.get("player").getAsString());
        for (JsonElement name : state.getAsJsonArray("players")) {
            peers.add(MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), name.getAsString()));
        }
        ServerPlayer player = connection.player();
        runtime.server().getPlayerList().op(new NameAndId(player.getGameProfile()));
        player.setGameMode(GameType.valueOf(state.get("gameMode").getAsString().toUpperCase(Locale.ROOT)));
        JsonArray cleared = state.getAsJsonArray("cleared");
        clear(position(cleared.get(0).getAsJsonArray()), position(cleared.get(1).getAsJsonArray()));
        for (JsonElement entry : state.getAsJsonArray("platforms")) {
            JsonArray platform = entry.getAsJsonArray();
            fill(position(platform.get(0).getAsJsonArray()), position(platform.get(1).getAsJsonArray()));
        }
        for (JsonElement entry : state.getAsJsonArray("portals")) {
            createPortal(player, entry.getAsJsonObject());
        }
        Vec3 standing = vector(state.getAsJsonArray("player"));
        player.teleportTo(standing.x, standing.y, standing.z);
        connection.acknowledgePosition();
        for (JsonElement entry : state.getAsJsonArray("commands")) {
            runtime.server().getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                entry.getAsJsonObject().get("native").getAsString());
        }
        for (JsonElement entry : state.getAsJsonArray("actions")) {
            JsonObject action = entry.getAsJsonObject();
            if (!"use-item-on".equals(action.get("type").getAsString())) {
                throw new IllegalStateException("Unknown menu parity setup action " + action.get("type").getAsString());
            }
            useItemOn(player, action);
        }
        phase = Phase.ACT;
        resumeTick = helper.getTick() + SETUP_TICKS + state.get("idleTicks").getAsInt();
    }

    private void act() {
        JsonArray steps = path().getAsJsonArray("steps");
        if (stepIndex >= steps.size()) {
            pathIndex++;
            phase = Phase.SETUP;
            return;
        }
        ServerPlayer player = connection.player();
        drainPackets();
        containerBefore = player.containerMenu.containerId;
        messagesBefore = connection.messages().size();
        opened = 0;
        JsonObject action = steps.get(stepIndex).getAsJsonObject().getAsJsonObject("action");
        String type = action.get("type").getAsString();
        switch (type) {
            case "open-portal" -> runtime.menus().open(player, portal(action.get("portal").getAsString()).getId());
            case "command" -> runtime.server().getCommands().performPrefixedCommand(player.createCommandSourceStack(),
                action.get("command").getAsString());
            case "click" -> click(player, action);
            case "chat" -> chat(player, action.get("text").getAsString());
            case "close" -> player.connection.handleContainerClose(new ServerboundContainerClosePacket(player.containerMenu.containerId));
            case "sneak-use" -> sneakUse(player, action);
            default -> throw new IllegalStateException("Unknown menu parity action " + type);
        }
        phase = Phase.SETTLE;
        resumeTick = helper.getTick() + SETTLE_TICKS;
    }

    private void settle() {
        drainPackets();
        JsonObject step = path().getAsJsonArray("steps").get(stepIndex).getAsJsonObject();
        JsonObject actual = snapshot(connection.player());
        compare(step, actual);
        stepIndex++;
        phase = Phase.ACT;
        if (!actual.get("window").isJsonNull()) {
            return;
        }
        JsonArray steps = path().getAsJsonArray("steps");
        while (stepIndex < steps.size() && needsWindow(steps.get(stepIndex).getAsJsonObject())) {
            skip(steps.get(stepIndex).getAsJsonObject());
            stepIndex++;
        }
    }

    private static boolean needsWindow(JsonObject step) {
        String type = step.getAsJsonObject("action").get("type").getAsString();
        return type.equals("click") || type.equals("close");
    }

    private void skip(JsonObject step) {
        unreached++;
        windows++;
        LOGGER.warn("WORMHOLES_MENU_PARITY UNREACHED {} #{} {} ({}): no native window was open", pathName(), stepIndex,
            step.get("id").getAsString(), describe(step.getAsJsonObject("action")));
        report.add(pathName() + " #" + stepIndex + " " + step.get("id").getAsString() + ": unreached");
    }

    private void click(ServerPlayer player, JsonObject action) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            throw new IllegalStateException("No open window to click");
        }
        menu.clicked(action.get("slot").getAsInt(), action.get("button").getAsInt(),
            ContainerInput.valueOf(action.get("mode").getAsString()), player);
    }

    private void useItemOn(ServerPlayer player, JsonObject action) {
        Identifier wanted = Identifier.parse(action.get("item").getAsString());
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && wanted.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                ItemStack held = inventory.removeItemNoUpdate(slot);
                player.setItemInHand(InteractionHand.MAIN_HAND, held);
                use(player, position(action.getAsJsonArray("target")), Direction.byName(action.get("face").getAsString()));
                return;
            }
        }
        throw new IllegalStateException("Menu parity setup item missing: " + wanted);
    }

    private void sneakUse(ServerPlayer player, JsonObject action) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        use(player, position(action.getAsJsonArray("target")), Direction.UP);
        player.setShiftKeyDown(false);
    }

    private void use(ServerPlayer player, BlockPos position, Direction face) {
        Vec3 hit = Vec3.atCenterOf(position).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hit);
        runtime.useBlock(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, position, false));
    }

    private static void chat(ServerPlayer player, String message) {
        try {
            Method broadcast = ServerGamePacketListenerImpl.class.getDeclaredMethod("broadcastChatMessage", PlayerChatMessage.class);
            broadcast.setAccessible(true);
            broadcast.invoke(player.connection, PlayerChatMessage.unsigned(player.getUUID(), message));
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw new IllegalStateException("Menu parity chat could not reach the validated message stage", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Menu parity chat failed", error.getCause());
        }
    }

    private void compare(JsonObject step, JsonObject actual) {
        windows++;
        String label = pathName() + " #" + stepIndex + " " + step.get("id").getAsString() + " (" + describe(step.getAsJsonObject("action")) + ")";
        List<String> differences = new ArrayList<>();
        String expectedTransition = step.get("transition").getAsString();
        String actualTransition = actual.get("transition").getAsString();
        if (!expectedTransition.equals(actualTransition)) {
            differences.add("transition: expected " + expectedTransition + ", native " + actualTransition);
        }
        JsonElement expectedWindow = step.get("window");
        JsonElement actualWindow = actual.get("window");
        if (expectedWindow.isJsonNull() || actualWindow.isJsonNull()) {
            if (expectedWindow.isJsonNull() != actualWindow.isJsonNull()) {
                differences.add("window: expected " + (expectedWindow.isJsonNull() ? "none" : "open") + ", native "
                    + (actualWindow.isJsonNull() ? "none" : "open"));
            }
        } else {
            JsonArray groups = step.has("unordered") ? step.getAsJsonArray("unordered") : new JsonArray();
            compareWindow(unordered(expectedWindow.getAsJsonObject(), groups), unordered(actualWindow.getAsJsonObject(), groups), differences);
        }
        if (differences.isEmpty()) {
            matched++;
            LOGGER.info("WORMHOLES_MENU_PARITY MATCH {}", label);
            return;
        }
        mismatched++;
        report.add(label + ": " + differences.size() + " difference(s)");
        StringBuilder detail = new StringBuilder("WORMHOLES_MENU_PARITY MISMATCH ").append(label);
        for (String difference : differences) {
            detail.append("\n    ").append(difference);
        }
        List<Component> messages = connection.messages();
        for (Component message : messages.subList(Math.min(messagesBefore, messages.size()), messages.size())) {
            detail.append("\n    native message: ").append(message.getString());
        }
        LOGGER.warn(detail.toString());
    }

    private static JsonObject unordered(JsonObject window, JsonArray groups) {
        if (groups.isEmpty()) {
            return window;
        }
        JsonObject result = window.deepCopy();
        Map<Integer, JsonObject> slots = slots(result.getAsJsonArray("slots"));
        for (JsonElement group : groups) {
            List<Integer> positions = new ArrayList<>();
            List<JsonObject> contents = new ArrayList<>();
            for (JsonElement position : group.getAsJsonArray()) {
                int slot = position.getAsInt();
                JsonObject content = slots.getOrDefault(slot, emptySlot(slot)).deepCopy();
                content.remove("slot");
                positions.add(slot);
                contents.add(content);
            }
            contents.sort((left, right) -> left.toString().compareTo(right.toString()));
            for (int index = 0; index < positions.size(); index++) {
                JsonObject content = new JsonObject();
                content.addProperty("slot", positions.get(index));
                for (Map.Entry<String, JsonElement> entry : contents.get(index).entrySet()) {
                    content.add(entry.getKey(), entry.getValue());
                }
                slots.put(positions.get(index), content);
            }
        }
        JsonArray ordered = new JsonArray();
        for (JsonObject slot : slots.values()) {
            ordered.add(slot);
        }
        result.add("slots", ordered);
        return result;
    }

    private static void compareWindow(JsonObject expected, JsonObject actual, List<String> differences) {
        for (String key : List.of("menu", "rows")) {
            if (!expected.get(key).equals(actual.get(key))) {
                differences.add(key + ": expected " + expected.get(key) + ", native " + actual.get(key));
            }
        }
        if (!expected.get("title").equals(actual.get("title"))) {
            differences.add("title: expected " + render(expected.getAsJsonArray("title")) + ", native " + render(actual.getAsJsonArray("title")));
        }
        Map<Integer, JsonObject> expectedSlots = slots(expected.getAsJsonArray("slots"));
        Map<Integer, JsonObject> actualSlots = slots(actual.getAsJsonArray("slots"));
        int size = Math.max(expected.get("rows").getAsInt(), actual.get("rows").getAsInt()) * 9;
        for (int slot = 0; slot < size; slot++) {
            JsonObject left = expectedSlots.getOrDefault(slot, emptySlot(slot));
            JsonObject right = actualSlots.getOrDefault(slot, emptySlot(slot));
            if (left.equals(right)) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            for (String key : List.of("item", "count", "glint")) {
                if (!String.valueOf(left.get(key)).equals(String.valueOf(right.get(key)))) {
                    fields.add(key + " " + left.get(key) + " -> " + right.get(key));
                }
            }
            if (!String.valueOf(left.get("name")).equals(String.valueOf(right.get("name")))) {
                fields.add("name " + renderName(left.get("name")) + " -> " + renderName(right.get("name")));
            }
            if (!String.valueOf(left.get("lore")).equals(String.valueOf(right.get("lore")))) {
                fields.add("lore " + renderLore(left.get("lore")) + " -> " + renderLore(right.get("lore")));
            }
            differences.add("slot " + slot + ": " + String.join("; ", fields));
        }
    }

    private JsonObject snapshot(ServerPlayer player) {
        JsonObject result = new JsonObject();
        AbstractContainerMenu menu = player.containerMenu;
        boolean open = menu != player.inventoryMenu;
        String transition;
        if (!open) {
            transition = "closed";
        } else if (opened > 0 || menu.containerId != containerBefore) {
            transition = "opened";
        } else {
            transition = "updated";
        }
        result.addProperty("transition", transition);
        if (!open) {
            result.add("window", JsonNull.INSTANCE);
            return result;
        }
        JsonObject window = new JsonObject();
        String menuType = String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType()));
        int size = menu.slots.size() - 36;
        window.addProperty("menu", menuType);
        window.addProperty("rows", size / 9);
        window.add("title", lastTitle == null ? new JsonArray() : text(lastTitle, Context.TITLE));
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < size; slot++) {
            slots.add(item(slot, menu.getSlot(slot).getItem()));
        }
        window.add("slots", slots);
        result.add("window", window);
        return result;
    }

    private JsonObject item(int slot, ItemStack stack) {
        if (stack.isEmpty()) {
            return emptySlot(slot);
        }
        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        result.addProperty("count", stack.getCount());
        DataComponentPatch patch = stack.getComponentsPatch();
        Component customName = patched(patch, DataComponents.CUSTOM_NAME);
        Component itemName = patched(patch, DataComponents.ITEM_NAME);
        ItemLore lore = patched(patch, DataComponents.LORE);
        result.addProperty("glint", stack.hasFoil());
        if (customName != null) {
            result.add("name", text(customName, Context.NAME));
        } else if (itemName != null) {
            result.add("name", text(itemName, Context.ITEM_NAME));
        }
        if (lore != null && !lore.lines().isEmpty()) {
            JsonArray lines = new JsonArray();
            for (Component line : lore.lines()) {
                lines.add(text(line, Context.LORE));
            }
            result.add("lore", lines);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T patched(DataComponentPatch patch, DataComponentType<T> type) {
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getKey() == type) {
                return entry.getValue().map(value -> (T) value).orElse(null);
            }
        }
        return null;
    }

    private JsonArray text(Component component, Context context) {
        JsonElement encoded = ComponentSerialization.CODEC.encodeStart(
            RegistryOps.create(JsonOps.INSTANCE, runtime.server().registryAccess()), component).getOrThrow();
        List<Segment> raw = new ArrayList<>();
        flatten(encoded, new Style(null, new LinkedHashMap<>()), raw);
        JsonArray segments = new JsonArray();
        JsonObject previous = null;
        for (Segment entry : raw) {
            if (entry.text().isEmpty()) {
                continue;
            }
            JsonObject segment = new JsonObject();
            segment.addProperty("text", entry.text());
            String color = entry.style().color() == null ? context.color : entry.style().color();
            if (color != null && !color.equals(context.color)) {
                segment.addProperty("color", color);
            }
            for (String flag : FLAGS) {
                Boolean value = entry.style().flags().get(flag);
                boolean resolved = value != null ? value : "italic".equals(flag) && context.italic;
                if (resolved) {
                    segment.addProperty(flag, true);
                }
            }
            if (previous != null && sameStyle(previous, segment)) {
                previous.addProperty("text", previous.get("text").getAsString() + entry.text());
                continue;
            }
            segments.add(segment);
            previous = segment;
        }
        for (JsonElement element : segments) {
            JsonObject segment = element.getAsJsonObject();
            segment.addProperty("text", normalize(segment.get("text").getAsString()));
        }
        return segments;
    }

    private static void flatten(JsonElement node, Style style, List<Segment> output) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonPrimitive()) {
            output.add(new Segment(node.getAsString(), style));
            return;
        }
        if (node.isJsonArray()) {
            JsonArray array = node.getAsJsonArray();
            if (array.isEmpty()) {
                return;
            }
            Style parent = inherit(style, array.get(0));
            flatten(array.get(0), style, output);
            for (int index = 1; index < array.size(); index++) {
                flatten(array.get(index), parent, output);
            }
            return;
        }
        JsonObject object = node.getAsJsonObject();
        Style own = inherit(style, object);
        String text = "";
        if (object.has("text")) {
            text = object.get("text").getAsString();
        } else if (object.has("translate")) {
            List<Segment> arguments = new ArrayList<>();
            if (object.has("with")) {
                flatten(object.get("with"), own, arguments);
            }
            List<String> rendered = new ArrayList<>(arguments.size());
            for (Segment argument : arguments) {
                rendered.add(argument.text());
            }
            text = "<translate:" + object.get("translate").getAsString() + (rendered.isEmpty() ? "" : ":" + String.join("|", rendered)) + ">";
        } else if (object.has("keybind")) {
            text = "<keybind:" + object.get("keybind").getAsString() + ">";
        }
        output.add(new Segment(text, own));
        if (object.has("extra")) {
            for (JsonElement child : object.getAsJsonArray("extra")) {
                flatten(child, own, output);
            }
        }
    }

    private static Style inherit(Style parent, JsonElement node) {
        if (node == null || !node.isJsonObject()) {
            return parent;
        }
        JsonObject object = node.getAsJsonObject();
        String color = parent.color();
        if (object.has("color")) {
            String value = object.get("color").getAsString().toLowerCase(Locale.ROOT);
            color = HEX_COLORS.getOrDefault(value, value);
        }
        Map<String, Boolean> flags = new LinkedHashMap<>(parent.flags());
        for (String flag : FLAGS) {
            if (object.has(flag) && !object.get(flag).isJsonNull()) {
                JsonPrimitive value = object.getAsJsonPrimitive(flag);
                flags.put(flag, value.isBoolean() ? value.getAsBoolean() : value.getAsInt() != 0);
            }
        }
        return new Style(color, flags);
    }

    private static boolean sameStyle(JsonObject left, JsonObject right) {
        if (!String.valueOf(left.get("color")).equals(String.valueOf(right.get("color")))) {
            return false;
        }
        for (String flag : FLAGS) {
            if (left.has(flag) != right.has(flag)) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String text) {
        String result = text;
        for (Normalization normalization : normalizations) {
            result = normalization.pattern().matcher(result).replaceAll(normalization.replacement());
        }
        return result;
    }

    private void drainPackets() {
        Iterator<Object> packets = connection.channel().outboundMessages().iterator();
        while (packets.hasNext()) {
            Object pending = packets.next();
            packets.remove();
            try {
                Object packet = HiddenByteBuf.unpack(pending);
                if (packet instanceof ByteBuf bytes) {
                    packet = decode(bytes);
                }
                if (packet instanceof ClientboundOpenScreenPacket screen) {
                    opened++;
                    lastTitle = screen.getTitle();
                }
            } finally {
                ReferenceCountUtil.release(pending);
            }
        }
    }

    private Packet<?> decode(ByteBuf bytes) {
        try {
            return clientbound.codec().decode(bytes.duplicate());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void createPortal(ServerPlayer owner, JsonObject definition) {
        List<BlockPos> cells = new ArrayList<>();
        JsonArray from = definition.getAsJsonArray("from");
        JsonArray to = definition.getAsJsonArray("to");
        BlockPos first = position(from);
        BlockPos second = position(to);
        for (int x = Math.min(first.getX(), second.getX()); x <= Math.max(first.getX(), second.getX()); x++) {
            for (int y = Math.min(first.getY(), second.getY()); y <= Math.max(first.getY(), second.getY()); y++) {
                for (int z = Math.min(first.getZ(), second.getZ()); z <= Math.max(first.getZ(), second.getZ()); z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        for (BlockPos cell : cells) {
            helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
        }
        JsonArray look = definition.getAsJsonArray("look");
        MinecraftPortal portal = runtime.portals().create(owner.getUUID(), helper.getLevel(), cells,
            PortalType.valueOf(definition.get("type").getAsString()),
            new Vec3(look.get(0).getAsDouble(), look.get(1).getAsDouble(), look.get(2).getAsDouble()));
        String name = definition.get("name").getAsString();
        runtime.portals().update(owner, portal.getId(), created -> created.setName(name));
    }

    private void fill(BlockPos first, BlockPos second) {
        for (BlockPos position : BlockPos.betweenClosed(first, second)) {
            helper.getLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
        }
    }

    private MinecraftPortal portal(String name) {
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (portal.getName().equals(name)) {
                return portal;
            }
        }
        throw new IllegalStateException("Menu parity portal missing: " + name);
    }

    private static BlockPos position(JsonArray values) {
        return new BlockPos(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt());
    }

    private static Vec3 vector(JsonArray values) {
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    private void clear(BlockPos first, BlockPos second) {
        for (BlockPos position : BlockPos.betweenClosed(first, second)) {
            helper.getLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
    }

    private void teardown() {
        for (MinecraftGameTestPlayer peer : peers) {
            peer.close();
        }
        peers.clear();
        if (connection != null) {
            ServerPlayer player = connection.player();
            player.closeContainer();
            runtime.menus().playerDisconnected(player);
            connection.close();
            connection = null;
        }
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            runtime.portals().remove(portal.getId());
        }
        lastTitle = null;
    }

    private void abortPath() {
        if (pathIndex >= paths.size()) {
            finish();
            return;
        }
        JsonArray steps = path().getAsJsonArray("steps");
        while (stepIndex < steps.size()) {
            skip(steps.get(stepIndex).getAsJsonObject());
            stepIndex++;
        }
        pathIndex++;
        phase = Phase.SETUP;
    }

    private void finish() {
        finished = true;
        StringBuilder summary = new StringBuilder("WORMHOLES_MENU_PARITY SUMMARY windows=").append(windows)
            .append(" matched=").append(matched).append(" mismatched=").append(mismatched).append(" unreached=").append(unreached);
        for (String line : report) {
            summary.append("\n    ").append(line);
        }
        LOGGER.info(summary.toString());
    }

    private JsonObject path() {
        return paths.get(pathIndex).getAsJsonObject();
    }

    private String pathName() {
        return pathIndex < paths.size() ? path().get("name").getAsString() : "<none>";
    }

    private static String describe(JsonObject action) {
        return switch (action.get("type").getAsString()) {
            case "click" -> "click slot " + action.get("slot").getAsInt() + " button " + action.get("button").getAsInt() + " "
                + action.get("mode").getAsString();
            case "open-portal" -> "open portal " + action.get("portal").getAsString();
            case "command" -> "/" + action.get("command").getAsString();
            case "chat" -> "chat " + action.get("text").getAsString();
            default -> action.get("type").getAsString();
        };
    }

    private static Map<Integer, JsonObject> slots(JsonArray slots) {
        Map<Integer, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement slot : slots) {
            JsonObject object = slot.getAsJsonObject();
            result.put(object.get("slot").getAsInt(), object);
        }
        return result;
    }

    private static JsonObject emptySlot(int slot) {
        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("item", "minecraft:air");
        result.addProperty("count", 0);
        result.addProperty("glint", false);
        return result;
    }

    private static String renderName(JsonElement name) {
        return name == null ? "<none>" : render(name.getAsJsonArray());
    }

    private static String renderLore(JsonElement lore) {
        if (lore == null) {
            return "<none>";
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement line : lore.getAsJsonArray()) {
            lines.add(render(line.getAsJsonArray()));
        }
        return "[" + String.join(" | ", lines) + "]";
    }

    private static String render(JsonArray segments) {
        StringBuilder result = new StringBuilder("\"");
        for (JsonElement element : segments) {
            JsonObject segment = element.getAsJsonObject();
            List<String> style = new ArrayList<>();
            if (segment.has("color")) {
                style.add(segment.get("color").getAsString());
            }
            for (String flag : FLAGS) {
                if (segment.has(flag)) {
                    style.add(flag);
                }
            }
            if (!style.isEmpty()) {
                result.append('{').append(String.join(",", style)).append('}');
            }
            result.append(segment.get("text").getAsString());
        }
        return result.append('"').toString();
    }

    private static List<Normalization> normalizations(JsonArray entries) {
        List<Normalization> result = new ArrayList<>(entries.size());
        for (JsonElement entry : entries) {
            JsonObject object = entry.getAsJsonObject();
            result.add(new Normalization(Pattern.compile(object.get("pattern").getAsString()), object.get("replacement").getAsString()));
        }
        return result;
    }

    private static JsonObject load() {
        try (InputStream stream = MinecraftMenuParityGameTest.class.getResourceAsStream(GOLDEN)) {
            if (stream == null) {
                throw new IllegalStateException("Menu parity goldens are missing: " + GOLDEN);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not read menu parity goldens", error);
        }
    }

    private enum Phase {
        SETUP,
        ACT,
        SETTLE
    }

    private enum Context {
        TITLE(null, false),
        NAME(null, true),
        ITEM_NAME(null, false),
        LORE("dark_purple", true);

        private final String color;
        private final boolean italic;

        Context(String color, boolean italic) {
            this.color = color;
            this.italic = italic;
        }
    }

    private record Style(String color, Map<String, Boolean> flags) {
    }

    private record Segment(String text, Style style) {
    }

    private record Normalization(Pattern pattern, String replacement) {
    }
}
