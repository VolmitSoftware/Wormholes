package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.wormholes.localization.WormholesMessages;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

final class MinecraftLanguageEditor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Gson JSON = new Gson();
    private final WormholesModRuntime runtime;
    private final Map<String, OpenMessage> opened = new HashMap<>();
    private PluginLanguageEditor editor;
    private long generation;

    MinecraftLanguageEditor(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    void start(PluginLanguageEditor editor) {
        this.editor = editor;
        generation++;
    }

    LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("edit").requires(this::allowed)
            .executes(context -> list(context.getSource(), runtime.configuration().settings().getLanguage(), ""))
            .then(Commands.argument("locale", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(VolmitLocales.all(), builder))
                .executes(context -> list(context.getSource(), StringArgumentType.getString(context, "locale"), ""))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), StringArgumentType.getString(context, "locale"), ""))
                    .then(Commands.argument("filter", StringArgumentType.greedyString()).executes(context ->
                        list(context.getSource(), StringArgumentType.getString(context, "locale"), StringArgumentType.getString(context, "filter")))))
                .then(Commands.argument("key", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(WormholesMessages.catalog().byId().keySet(), builder))
                    .executes(context -> inspect(context.getSource(), StringArgumentType.getString(context, "locale"), StringArgumentType.getString(context, "key")))
                    .then(Commands.literal("reset").executes(context -> save(context.getSource(), new RequestedEdit(
                        StringArgumentType.getString(context, "locale"), StringArgumentType.getString(context, "key"), null))))
                    .then(Commands.literal("set").then(Commands.argument("json", StringArgumentType.greedyString()).executes(context ->
                        save(context.getSource(), new RequestedEdit(StringArgumentType.getString(context, "locale"),
                            StringArgumentType.getString(context, "key"), StringArgumentType.getString(context, "json"))))))));
    }

    void disconnected(ServerPlayer player) {
        opened.remove(player.getUUID().toString());
    }

    private int list(CommandSourceStack source, String locale, String filter) {
        return complete(source, editor.load(locale), document -> {
            String needle = filter.toLowerCase(Locale.ROOT);
            List<String> matching = document.snapshot().catalog().byId().keySet().stream()
                .filter(key -> key.toLowerCase(Locale.ROOT).contains(needle)).sorted().toList();
            source.sendSuccess(() -> Component.literal(document.locale() + ": " + matching.size() + " messages; showing up to 30. Use list <filter> to narrow."), false);
            for (String key : matching.subList(0, Math.min(30, matching.size()))) {
                source.sendSuccess(() -> Component.literal("/wormholes language server edit " + document.locale() + " " + key), false);
            }
        });
    }

    private int inspect(CommandSourceStack source, String locale, String key) {
        return complete(source, editor.load(locale), document -> {
            MessageKey definition = document.snapshot().catalog().require(key);
            opened.put(actor(source), new OpenMessage(document, key));
            source.sendSuccess(() -> Component.literal(key + " = " + json(document.snapshot().value(definition))), false);
            source.sendSuccess(() -> Component.literal("English = " + json(definition.englishValue()) + "; required variables = " + definition.placeholders()), false);
            source.sendSuccess(() -> Component.literal("Append set <JSON value> to save, or reset to restore English."), false);
        });
    }

    private int save(CommandSourceStack source, RequestedEdit requested) {
        OpenMessage current = opened.get(actor(source));
        if (current == null || !current.key().equals(requested.key())
            || !current.document().locale().equalsIgnoreCase(requested.locale().replace('-', '_'))) {
            source.sendFailure(Component.literal("Open this message before saving: /wormholes language server edit " + requested.locale() + " " + requested.key()));
            return 0;
        }
        MessageKey key = current.document().snapshot().catalog().require(current.key());
        MessageValue value;
        try {
            value = requested.json() == null ? key.englishValue() : parseValue(requested.json());
        } catch (IllegalArgumentException failure) {
            source.sendFailure(Component.literal(failure.getMessage()));
            return 0;
        }
        return complete(source, editor.save(new PluginLanguageEditor.Edit(current.document().locale(), current.key(),
            current.document().snapshot().value(key), value)), document -> {
                opened.put(actor(source), new OpenMessage(document, current.key()));
                source.sendSuccess(() -> Component.literal("Saved " + document.locale() + " " + current.key() + " = " + json(value)), false);
            });
    }

    private int complete(CommandSourceStack source, CompletableFuture<PluginLanguageEditor.Document> operation,
                         Consumer<PluginLanguageEditor.Document> success) {
        long expected = generation;
        operation.whenCompleteAsync((document, failure) -> {
            if (generation != expected || editor == null || !allowed(source)) {
                return;
            }
            if (failure != null) {
                LOGGER.error("Could not edit Wormholes language message", failure);
                source.sendFailure(Component.literal("Language edit failed: " + failure.getMessage()));
                return;
            }
            try {
                success.accept(document);
            } catch (IllegalArgumentException invalid) {
                source.sendFailure(Component.literal(invalid.getMessage()));
            }
        }, runtime.server());
        return 1;
    }

    private boolean allowed(CommandSourceStack source) {
        return runtime.access().permission(source, "wormholes.admin") || runtime.access().permission(source, "volmit.language.admin");
    }

    private static String actor(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? "console:" + source.getTextName() : player.getUUID().toString();
    }

    static MessageValue parseValue(String input) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(input);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Use a JSON string, string array, or plural-category object.", invalid);
        }
        if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
            return new TextValue(parsed.getAsString());
        }
        if (parsed.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement line : parsed.getAsJsonArray()) {
                lines.add(string(line));
            }
            return new LinesValue(lines);
        }
        if (parsed.isJsonObject()) {
            Map<String, String> forms = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                forms.put(entry.getKey(), string(entry.getValue()));
            }
            return new PluralValue(forms);
        }
        throw new IllegalArgumentException("Use a JSON string, string array, or plural-category object.");
    }

    private static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Every translation value must be a JSON string.");
        }
        return value.getAsString();
    }

    private static String json(MessageValue value) {
        return switch (value) {
            case TextValue text -> JSON.toJson(text.template());
            case LinesValue lines -> JSON.toJson(lines.lines());
            case PluralValue plural -> JSON.toJson(plural.forms());
        };
    }

    @Override
    public void close() {
        generation++;
        opened.clear();
        if (editor != null) {
            editor.close();
            editor = null;
        }
    }

    private record OpenMessage(PluginLanguageEditor.Document document, String key) { }
    private record RequestedEdit(String locale, String key, String json) { }
}
