package art.arcane.wormholes.menu.language;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.wormholes.menu.MenuViewer;

public interface LanguageMenuHost {
    List<String> locales();

    String defaultLocale();

    Optional<LanguageMenuView> document(String locale);

    CompletableFuture<LanguageMenuView> load(String locale);

    CompletableFuture<Void> apply(PluginLanguageEditor.Edit edit);

    void exit(MenuViewer viewer);

    void failed(String message, Throwable cause);
}
