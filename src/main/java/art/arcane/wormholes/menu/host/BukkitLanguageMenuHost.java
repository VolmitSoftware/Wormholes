package art.arcane.wormholes.menu.host;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.language.LanguageMenuHost;
import art.arcane.wormholes.menu.language.LanguageMenuView;

public final class BukkitLanguageMenuHost implements LanguageMenuHost {
    private final Options options;
    private final Map<String, LanguageMenuView> documents = new ConcurrentHashMap<>();

    public BukkitLanguageMenuHost(Options options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public List<String> locales() {
        return options.languages().availableLocales();
    }

    @Override
    public String defaultLocale() {
        return options.languages().defaultLocale();
    }

    @Override
    public Optional<LanguageMenuView> document(String locale) {
        return Optional.ofNullable(documents.get(locale));
    }

    @Override
    public CompletableFuture<LanguageMenuView> load(String locale) {
        return options.editor().load(locale).thenApply(this::cache);
    }

    @Override
    public CompletableFuture<Void> apply(PluginLanguageEditor.Edit edit) {
        return options.editor().save(edit).thenAccept(this::cache);
    }

    @Override
    public void exit(MenuViewer viewer) {
        options.exit().accept(BukkitMenuViewer.player(viewer));
    }

    @Override
    public void failed(String message, Throwable cause) {
        options.logger().log(Level.SEVERE, message, cause);
    }

    private LanguageMenuView cache(PluginLanguageEditor.Document document) {
        LanguageMenuView view = LanguageMenuView.of(document);
        documents.put(view.locale(), view);
        return view;
    }

    public record Options(PluginLanguageService languages, PluginLanguageEditor editor, Consumer<Player> exit, Logger logger) {
        public Options {
            Objects.requireNonNull(languages, "languages");
            Objects.requireNonNull(editor, "editor");
            Objects.requireNonNull(exit, "exit");
            Objects.requireNonNull(logger, "logger");
        }
    }
}
