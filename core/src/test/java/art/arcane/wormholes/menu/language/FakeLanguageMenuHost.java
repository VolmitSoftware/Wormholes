package art.arcane.wormholes.menu.language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.wormholes.menu.MenuViewer;

final class FakeLanguageMenuHost implements LanguageMenuHost {
    final List<String> locales = new ArrayList<>();
    final Map<String, LanguageMenuView> documents = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    final List<PluginLanguageEditor.Edit> edits = new ArrayList<>();
    final List<Throwable> failures = new ArrayList<>();
    String defaultLocale = "en_US";
    CompletableFuture<LanguageMenuView> loading = new CompletableFuture<>();
    CompletableFuture<Void> applying = new CompletableFuture<>();

    @Override
    public List<String> locales() {
        return List.copyOf(locales);
    }

    @Override
    public String defaultLocale() {
        return defaultLocale;
    }

    @Override
    public Optional<LanguageMenuView> document(String locale) {
        return Optional.ofNullable(documents.get(locale));
    }

    @Override
    public CompletableFuture<LanguageMenuView> load(String locale) {
        calls.add("load:" + locale);
        return loading.thenApply(document -> {
            documents.put(document.locale(), document);
            return document;
        });
    }

    @Override
    public CompletableFuture<Void> apply(PluginLanguageEditor.Edit edit) {
        calls.add("apply:" + edit.key());
        edits.add(edit);
        return applying.thenRun(() -> documents.put(edit.locale(), replaced(documents.get(edit.locale()), edit)));
    }

    @Override
    public void exit(MenuViewer viewer) {
        calls.add("exit:" + viewer.name());
    }

    @Override
    public void failed(String message, Throwable cause) {
        failures.add(cause);
    }

    private static LanguageMenuView replaced(LanguageMenuView document, PluginLanguageEditor.Edit edit) {
        List<LanguageMenuView.Message> messages = new ArrayList<>(document.messages().size());
        for (LanguageMenuView.Message message : document.messages()) {
            messages.add(message.key().equals(edit.key()) ? new LanguageMenuView.Message(message.key(), edit.value(), message.placeholders()) : message);
        }
        return new LanguageMenuView(document.locale(), messages);
    }
}
