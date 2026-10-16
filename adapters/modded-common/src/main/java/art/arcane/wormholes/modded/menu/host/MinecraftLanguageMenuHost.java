package art.arcane.wormholes.modded.menu.host;

import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.language.LanguageMenuHost;
import art.arcane.wormholes.menu.language.LanguageMenuView;
import art.arcane.wormholes.modded.MinecraftLocalization;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftLanguageMenuHost implements LanguageMenuHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private final MinecraftLocalization localization;
    private final Map<String, LanguageMenuView> documents = new ConcurrentHashMap<>();

    public MinecraftLanguageMenuHost(MinecraftLocalization localization) {
        this.localization = Objects.requireNonNull(localization, "localization");
    }

    @Override
    public List<String> locales() {
        return localization.languages().availableLocales();
    }

    @Override
    public String defaultLocale() {
        return localization.languages().defaultLocale();
    }

    @Override
    public Optional<LanguageMenuView> document(String locale) {
        return Optional.ofNullable(documents.get(locale));
    }

    @Override
    public CompletableFuture<LanguageMenuView> load(String locale) {
        return localization.editor().load(locale).thenApply(this::cache);
    }

    @Override
    public CompletableFuture<Void> apply(PluginLanguageEditor.Edit edit) {
        return localization.editor().save(edit).thenAccept(this::cache);
    }

    @Override
    public void exit(MenuViewer viewer) {
        localization.showServerLanguages(MinecraftMenuViewer.player(viewer));
    }

    @Override
    public void failed(String message, Throwable cause) {
        LOGGER.error(message, cause);
    }

    private LanguageMenuView cache(PluginLanguageEditor.Document document) {
        LanguageMenuView view = LanguageMenuView.of(document);
        documents.put(view.locale(), view);
        return view;
    }
}
