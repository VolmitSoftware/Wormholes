package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.localization.WormholesLocaleLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import java.util.List;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MinecraftLocalization implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftLocalization> SERVICES = new ConcurrentHashMap<>();
    private final WormholesModRuntime runtime;
    private final MinecraftLanguageEditor editor;
    private final Map<UUID, Picker> pickers = new HashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong generation = new AtomicLong();
    private volatile DefaultLanguage current;
    private volatile String fallbacks;
    private volatile boolean closed = true;
    private Path directory;
    private MinecraftServer server;
    private ExecutorService reader;
    private PluginLanguageService languages;
    private CompletableFuture<Void> defaultSelection = CompletableFuture.completedFuture(null);

    public MinecraftLocalization(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
        editor = new MinecraftLanguageEditor(runtime);
    }

    public static MinecraftLocalization forPlayer(ServerPlayer player) {
        return Objects.requireNonNull(SERVICES.get(player.level().getServer()), "Wormholes localization is not running");
    }

    public void start(Path directory, WormholesSettings settings) throws IOException {
        runtime.requireServerThread();
        this.directory = Objects.requireNonNull(directory);
        server = runtime.server();
        fallbacks = settings.getLanguageFallbacks();
        current = new DefaultLanguage(settings.getLanguage(), load(settings.getLanguage(), fallbacks));
        reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("Wormholes-languages").factory());
        closed = false;
        generation.incrementAndGet();
        languages = new PluginLanguageService(new PluginLanguageService.Options(
            directory.resolve("languages/language-preferences.properties"), VolmitLocales::all,
            () -> current.locale(), () -> current.snapshot(), locale -> load(locale, fallbacks),
            (locale, snapshot) -> current = new DefaultLanguage(locale, snapshot), LOGGER));
        editor.start(new PluginLanguageEditor(languages, new PluginLanguageEditor.Options(
            locale -> load(locale, locale.equals(VolmitLocales.ENGLISH) ? "" : fallbacks), edit -> {
                LocalizationSnapshot updated = WormholesLocaleLoader.edit(this.directory, edit,
                    edit.locale().equals(VolmitLocales.ENGLISH) ? "" : fallbacks);
                if (current.locale().equals(edit.locale())) {
                    current = new DefaultLanguage(edit.locale(), updated);
                }
                return updated;
            })));
        SERVICES.put(server, this);
    }

    public CompletableFuture<Void> reload(WormholesSettings settings) {
        runtime.requireServerThread();
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Wormholes localization is closed"));
        }
        long expected = generation.incrementAndGet();
        CompletableFuture<Void> result = track(new CompletableFuture<>());
        reader.execute(() -> prepareReload(settings, expected, result));
        return result;
    }

    @Override
    public void close() {
        runtime.requireServerThread();
        closed = true;
        editor.close();
        for (Picker picker : pickers.values()) {
            if (picker.viewer.containerMenu == picker.menu) {
                picker.viewer.closeContainer();
            }
        }
        pickers.clear();
        generation.incrementAndGet();
        if (server != null) {
            SERVICES.remove(server, this);
        }
        if (reader != null) {
            reader.shutdownNow();
        }
        if (languages != null) {
            languages.close();
        }
        for (CompletableFuture<?> result : pending) {
            result.completeExceptionally(new CancellationException("Wormholes localization stopped"));
        }
        pending.clear();
    }

    public LocalizationSnapshot snapshot(ServerPlayer player) {
        return languages.snapshot(player == null ? null : player.getUUID());
    }

    public Component text(ServerPlayer player, TextKey key, Map<String, ?> arguments) {
        return MinecraftMenuText.text(snapshot(player), key, arguments);
    }

    public CompletableFuture<Void> selectPlayer(ServerPlayer player, String locale) {
        runtime.requireServerThread();
        return track(locale.equalsIgnoreCase("default") ? languages.clearPlayer(player.getUUID())
            : languages.selectPlayer(player.getUUID(), locale));
    }

    public CompletableFuture<Void> selectServer(String locale) {
        runtime.requireServerThread();
        CompletableFuture<Void> result = track(new CompletableFuture<>());
        long expected = generation.get();
        defaultSelection.whenComplete((previous, failure) -> server.execute(() -> beginServerSelection(locale, expected, result)));
        defaultSelection = result;
        return result;
    }

    private void beginServerSelection(String locale, long expected, CompletableFuture<Void> result) {
        if (!active(expected, result)) {
            return;
        }
        DefaultLanguage before = current;
        languages.selectDefault(locale).whenComplete((ignored, failure) -> server.execute(() -> {
            if (!active(expected, result)) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            DefaultLanguage selected = current;
            runtime.configuration().setLanguage(selected.locale()).whenComplete((saved, error) -> {
                if (error != null) {
                    if (current == selected) {
                        current = before;
                    }
                    result.completeExceptionally(error);
                } else {
                    result.complete(null);
                }
            });
        }));
    }

    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wormholes").then(Commands.literal("language")
            .executes(context -> openPicker(context.getSource(), false))
            .then(Commands.argument("locale", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(VolmitLocales.all(), builder))
                .executes(context -> choose(context.getSource(), StringArgumentType.getString(context, "locale"), false)))
            .then(Commands.literal("self").requires(source -> canChoose(source, false))
                .executes(context -> openPicker(context.getSource(), false))
                .then(Commands.literal("reset").executes(context -> choose(context.getSource(), "default", false)))
                .then(Commands.argument("locale", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(VolmitLocales.all(), builder))
                    .executes(context -> choose(context.getSource(), StringArgumentType.getString(context, "locale"), false))))
            .then(Commands.literal("server").requires(source -> runtime.access().permission(source, "wormholes.admin")
                || runtime.access().permission(source, "volmit.language.admin"))
                .then(editor.commands())
                .executes(context -> openPicker(context.getSource(), true))
                .then(Commands.argument("locale", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(VolmitLocales.all(), builder))
                    .executes(context -> choose(context.getSource(), StringArgumentType.getString(context, "locale"), true))))));
    }

    public void disconnected(ServerPlayer player) {
        pickers.remove(player.getUUID());
        editor.disconnected(player);
    }

    private int openPicker(CommandSourceStack source, boolean serverDefault) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return show(source);
        }
        if (!canChoose(source, serverDefault)) {
            source.sendFailure(text(player, BukkitLanguageMessages.NO_CONTROLS, Map.of()));
            return 0;
        }
        Picker picker = new Picker(player, serverDefault);
        pickers.put(player.getUUID(), picker);
        MinecraftInventoryMenu.open(player, text(player, serverDefault ? BukkitLanguageMessages.SERVER_DEFAULT
            : BukkitLanguageMessages.YOUR_LANGUAGE, Map.of()), new MinecraftInventoryMenu.Actions(picker::valid, picker::render, picker::click));
        return 1;
    }

    private final class Picker {
        private final ServerPlayer viewer;
        private final boolean serverDefault;
        private MinecraftInventoryMenu menu;

        private Picker(ServerPlayer viewer, boolean serverDefault) {
            this.viewer = viewer;
            this.serverDefault = serverDefault;
        }

        private boolean valid() {
            return !closed && !viewer.hasDisconnected() && pickers.get(viewer.getUUID()) == this
                && canChoose(viewer.createCommandSourceStack(), serverDefault);
        }

        private void render(MinecraftInventoryMenu menu) {
            this.menu = menu;
            List<String> locales = VolmitLocales.all();
            String selected = serverDefault ? current.locale() : languages.effectiveLocale(viewer.getUUID());
            for (int index = 0; index < locales.size(); index++) {
                String locale = locales.get(index);
                ItemStack item = new ItemStack(Items.PAPER);
                item.set(DataComponents.CUSTOM_NAME, Component.literal(VolmitLocales.displayName(locale).orElse(locale)));
                item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(locale), text(viewer, BukkitLanguageMessages.SELECT_DESCRIPTION, Map.of()))));
                item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected.equals(locale));
                menu.set(index, item);
            }
            if (!serverDefault) {
                control(47, Items.BARRIER, BukkitLanguageMessages.USE_SERVER_DEFAULT);
            }
            control(49, Items.ARROW, BukkitLanguageMessages.EDITOR_CLOSE);
            if (canChoose(viewer.createCommandSourceStack(), true)) {
                control(51, Items.COMPARATOR, serverDefault ? BukkitLanguageMessages.YOUR_LANGUAGE : BukkitLanguageMessages.SERVER_DEFAULT);
            }
        }

        private void control(int slot, Item material, TextKey key) {
            ItemStack item = new ItemStack(material);
            item.set(DataComponents.CUSTOM_NAME, text(viewer, key, Map.of()));
            menu.set(slot, item);
        }

        private void click(MinecraftInventoryMenu.Click click) {
            if (!valid() || viewer.containerMenu != menu || click.menu() != menu || click.right() || click.shift() || click.middle()) {
                return;
            }
            List<String> locales = VolmitLocales.all();
            if (click.slot() < locales.size()) {
                choose(viewer.createCommandSourceStack(), locales.get(click.slot()), serverDefault);
                viewer.closeContainer();
                pickers.remove(viewer.getUUID(), this);
            } else if (click.slot() == 47 && !serverDefault) {
                choose(viewer.createCommandSourceStack(), "default", false);
                viewer.closeContainer();
                pickers.remove(viewer.getUUID(), this);
            } else if (click.slot() == 49) {
                viewer.closeContainer();
                pickers.remove(viewer.getUUID(), this);
            } else if (click.slot() == 51 && canChoose(viewer.createCommandSourceStack(), true)) {
                openPicker(viewer.createCommandSourceStack(), !serverDefault);
            }
        }
    }

    private int show(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        source.sendSuccess(() -> text(player, BukkitLanguageMessages.CURRENT,
            Map.of("locale", languages.effectiveLocale(player == null ? null : player.getUUID()))), false);
        return 1;
    }

    private boolean canChoose(CommandSourceStack source, boolean serverDefault) {
        return serverDefault ? runtime.access().permission(source, "wormholes.admin") || runtime.access().permission(source, "volmit.language.admin")
            : runtime.access().permission(source, "wormholes.language.self") && runtime.access().permission(source, "volmit.language.self");
    }

    private int choose(CommandSourceStack source, String locale, boolean serverDefault) {
        ServerPlayer player = source.getPlayer();
        if (!serverDefault && player == null) {
            source.sendFailure(text(null, BukkitLanguageMessages.PERSONAL_PLAYER_ONLY, Map.of()));
            return 0;
        }
        if (!canChoose(source, serverDefault)) {
            source.sendFailure(text(player, serverDefault ? BukkitLanguageMessages.PLUGIN_SERVER_PERMISSION
                : BukkitLanguageMessages.PLUGIN_PERSONAL_PERMISSION, Map.of("plugin", "Wormholes")));
            return 0;
        }
        CompletableFuture<Void> selection = serverDefault ? selectServer(locale) : selectPlayer(player, locale);
        selection.whenComplete((ignored, error) -> server.execute(() -> {
            if (closed || player != null && player.hasDisconnected()) {
                return;
            }
            if (error != null) {
                LOGGER.log(Level.WARNING, "Could not select Wormholes language " + locale, error);
                source.sendFailure(text(player, BukkitLanguageMessages.SAVE_FAILED, Map.of("plugin", "Wormholes")));
                return;
            }
            TextKey message = serverDefault ? BukkitLanguageMessages.SERVER_SELECTED
                : locale.equalsIgnoreCase("default") ? BukkitLanguageMessages.SERVER_DEFAULT_SELECTED : BukkitLanguageMessages.PERSONAL_SELECTED;
            source.sendSuccess(() -> text(player, message,
                message == BukkitLanguageMessages.SERVER_DEFAULT_SELECTED ? Map.of("plugin", "Wormholes")
                    : Map.of("plugin", "Wormholes", "locale", languages.effectiveLocale(player == null ? null : player.getUUID()))), false);
        }));
        return 1;
    }

    private void prepareReload(WormholesSettings settings, long expected, CompletableFuture<Void> result) {
        try {
            LocalizationSnapshot loaded = load(settings.getLanguage(), settings.getLanguageFallbacks());
            server.execute(() -> {
                if (!active(expected, result)) {
                    return;
                }
                fallbacks = settings.getLanguageFallbacks();
                current = new DefaultLanguage(settings.getLanguage(), loaded);
                languages.invalidate();
                result.complete(null);
            });
        } catch (IOException | RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private LocalizationSnapshot load(String locale, String fallbacks) throws IOException {
        return LocalizationSnapshot.create(WormholesLocaleLoader.load(directory, locale,
            VolmitLocales.ENGLISH.equals(locale) ? "" : fallbacks));
    }

    private boolean active(long expected, CompletableFuture<?> result) {
        if (closed || expected != generation.get()) {
            result.completeExceptionally(new CancellationException("Wormholes language selection was superseded"));
            return false;
        }
        return !result.isDone();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> result) {
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        return result;
    }

    private record DefaultLanguage(String locale, LocalizationSnapshot snapshot) {
    }
}
