package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessageRenderer;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

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
        return nativeComponent(WormholesMessageRenderer.json(snapshot.resolve(message, arguments(arguments))));
    }

    public static Component text(ServerPlayer viewer, TextKey message, MessageArgs arguments) {
        return nativeComponent(WormholesMessageRenderer.json(MinecraftLocalization.forPlayer(viewer).snapshot(viewer).resolve(message, arguments)));
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
        item.set(DataComponents.CUSTOM_NAME, lines.getFirst());
        item.set(DataComponents.LORE, new ItemLore(lines.subList(1, lines.size())));
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
        return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, component).getOrThrow().copy().withStyle(style -> style.withItalic(false));
    }

    private static MessageArgs arguments(Map<String, ?> values) {
        MessageArgs.Builder arguments = MessageArgs.builder();
        values.forEach((name, value) -> arguments.add(MessageArgument.untrusted(name, value)));
        return arguments.build();
    }
}
