package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import com.google.gson.JsonElement;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MinecraftMenuText {
    private MinecraftMenuText() {
    }

    public static Component text(ServerPlayer viewer, TextKey message, Map<String, ?> arguments) {
        return text(MinecraftLocalization.forPlayer(viewer).snapshot(viewer), message, arguments);
    }

    public static Component text(LocalizationSnapshot snapshot, TextKey message, Map<String, ?> arguments) {
        return text(snapshot, message, arguments(arguments));
    }

    public static Component text(ServerPlayer viewer, TextKey message, MessageArgs arguments) {
        return text(MinecraftLocalization.forPlayer(viewer).snapshot(viewer), message, arguments);
    }

    public static Component text(LocalizationSnapshot snapshot, TextKey message, MessageArgs arguments) {
        return nativeComponent(WormholesMessageRenderer.json(snapshot.resolve(message, arguments)));
    }

    public static void notice(ServerPlayer viewer, Component message) {
        viewer.sendSystemMessage(message, true);
    }

    public static void notifySuccess(ServerPlayer viewer, String legacy) {
        notice(viewer, nativeComponent(WormholesMessageRenderer.noticeJson(legacy, true)));
    }

    public static void notifyFailure(ServerPlayer viewer, String legacy) {
        notice(viewer, nativeComponent(WormholesMessageRenderer.noticeJson(legacy, false)));
    }

    public static ItemStack item(ServerPlayer viewer, Item material, LinesKey message, Map<String, ?> arguments) {
        List<Component> lines = lines(MinecraftLocalization.forPlayer(viewer).snapshot(viewer), message, arguments);
        ItemStack item = new ItemStack(material);
        item.setHoverName(lines.getFirst());
        ListTag lore = new ListTag();
        for (Component line : lines.subList(1, lines.size())) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        }
        item.getOrCreateTagElement("display").put("Lore", lore);
        return item;
    }

    public static List<Component> lines(LocalizationSnapshot snapshot, LinesKey message, Map<String, ?> arguments) {
        List<JsonElement> serialized = WormholesMessageRenderer.jsonLines(snapshot.resolve(message, arguments(arguments)));
        List<Component> lines = new ArrayList<>(serialized.size());
        for (JsonElement line : serialized) {
            lines.add(nativeComponent(line));
        }
        return List.copyOf(lines);
    }

    static Component format(String template, Map<String, ?> arguments) {
        return nativeComponent(WormholesMessageRenderer.json(new ResolvedText("menu.text", "en_US", template, arguments(arguments))));
    }

    private static Component nativeComponent(JsonElement component) {
        return Component.Serializer.fromJson(component).withStyle(style -> style.withItalic(false));
    }

    private static MessageArgs arguments(Map<String, ?> values) {
        MessageArgs.Builder arguments = MessageArgs.builder();
        values.forEach((name, value) -> arguments.add(MessageArgument.untrusted(name, value)));
        return arguments.build();
    }
}
