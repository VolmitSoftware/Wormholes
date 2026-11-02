package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.GitHubLanguageSource;
import art.arcane.volmlib.util.localization.LanguageCatalogMerge;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamStore;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WormholesLanguages implements AutoCloseable {
    public static final String MANIFEST_RESOURCE = "wormholes-language-source.properties";
    private static final int MAXIMUM_BASE_DEPTH = 4;

    private final LanguageDirectory directory;
    private final Logger logger;
    private final LanguageSourceManifest manifest;
    private final LanguageUpstreamSync sync;
    private final Throwable sourceFailure;
    private volatile List<String> availableLocales = List.of(WormholesMessages.ENGLISH_LOCALE);
    private volatile Map<String, LanguageMeta> metas = Map.of();
    private volatile BiConsumer<Path, String> selfWriteListener;
    private volatile Consumer<String> updateListener;
    private volatile PluginLanguageService selections;

    public WormholesLanguages(Path dataFolder, Logger logger) {
        this(dataFolder, logger, GitHubLanguageSource.API_ROOT, GitHubLanguageSource.RAW_ROOT,
                LanguageUpstreamSync.MINIMUM_CHECK_INTERVAL);
    }

    WormholesLanguages(Path dataFolder, Logger logger, URI apiRoot, URI rawRoot, Duration minimumCheckInterval) {
        directory = new LanguageDirectory(dataFolder.resolve("languages"));
        this.logger = Objects.requireNonNull(logger, "logger");
        LanguageSourceManifest loadedManifest = null;
        LanguageUpstreamSync loadedSync = null;
        Throwable failure = null;
        try {
            loadedManifest = LanguageSourceManifest.load(WormholesLanguages.class.getClassLoader(), MANIFEST_RESOURCE);
            GitHubLanguageSource source = new GitHubLanguageSource(loadedManifest, "Wormholes", apiRoot, rawRoot);
            LanguageUpstreamStore store = new LanguageUpstreamStore(directory, loadedManifest);
            loadedSync = new LanguageUpstreamSync(new LanguageUpstreamSync.Options(
                    "Wormholes",
                    WormholesMessages.catalog(),
                    directory,
                    loadedManifest,
                    source,
                    store,
                    WormholesLanguages::englishReference,
                    this::validateContent,
                    this::commitUpstream,
                    this::noteUpstreamWrite,
                    this::applyUpstream,
                    logger
            ), minimumCheckInterval);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        manifest = loadedManifest;
        sync = loadedSync;
        sourceFailure = failure;
    }

    public LocalizationCandidate load(String locale, String fallbackLocales) throws IOException {
        return load(locale, fallbackLocales, false);
    }

    public LocalizationCandidate loadInstalling(String locale, String fallbackLocales) throws IOException {
        return load(locale, fallbackLocales, true);
    }

    public LocalizationSnapshot edit(PluginLanguageEditor.Edit edit, String fallbacks) throws IOException {
        LocalizationCandidate current = load(edit.locale(), fallbacks);
        Path path = directory.languageFile(WormholesLocales.require(edit.locale()));
        if (hasBlankTranslation(current.catalog().require(edit.key()), edit.value())) {
            throw new IllegalArgumentException("Language message cannot be blank: " + edit.key());
        }
        List<LocaleOverlay> base = new ArrayList<>(current.overlays().size());
        for (LocaleOverlay overlay : current.overlays()) {
            if (!overlay.source().equals(path.toString())) {
                base.add(overlay);
            }
        }
        LocalizationSnapshot.create(new LocalizationCandidate(WormholesMessages.catalog(),
                List.of(LocaleOverlay.builder("editor", edit.locale()).put(edit.key(), edit.value()).build()),
                PluralSelector.oneOther()));
        LocalizationSnapshot edited = LanguageFileEditor.update(path, raw -> {
            LocalizationSnapshot before = editedSnapshot(raw, path, edit.locale(), base);
            MessageKey key = WormholesMessages.catalog().key(edit.key());
            if (key == null || !before.value(key).equals(edit.expected())) {
                throw new IOException("Language message changed while it was being edited: " + edit.key());
            }
            TomlLanguageEditor.EditResult updated = TomlLanguageEditor.upsert(raw, edit.key(), edit.value());
            return new LanguageFileEditor.Prepared<>(updated.content(),
                    editedSnapshot(updated.content(), path, edit.locale(), base));
        });
        refreshAvailableLocales();
        return edited;
    }

    public void attach(PluginLanguageService languageService) {
        selections = languageService;
    }

    public void setSelfWriteListener(BiConsumer<Path, String> listener) {
        selfWriteListener = listener;
    }

    public void setUpdateListener(Consumer<String> listener) {
        updateListener = listener;
    }

    public CompletableFuture<LanguageUpstreamSync.SyncReport> checkAsync() {
        if (sync == null) {
            return CompletableFuture.completedFuture(LanguageUpstreamSync.SyncReport.failed(
                    sourceDescription(), System.currentTimeMillis()));
        }
        return sync.checkAsync().thenApply(this::logUpstreamReport);
    }

    public CompletableFuture<String> installAsync(String locale) {
        String requiredLocale = WormholesLocales.require(locale);
        if (sync == null || !isRepositoryLocale(requiredLocale)) {
            return CompletableFuture.failedFuture(new IOException("Language " + requiredLocale
                    + " is not available from " + sourceDescription()));
        }
        return sync.installAsync(requiredLocale).thenApply(content -> {
            logger.info("Installed Wormholes language " + requiredLocale + " from " + sourceDescription() + ".");
            refreshAvailableLocalesQuietly();
            return content;
        });
    }

    public boolean isInstalled(String locale) {
        return directory.exists(WormholesLocales.normalize(locale));
    }

    public boolean isRepositoryLocale(String locale) {
        return manifest != null && locale != null && manifest.contains(locale.trim());
    }

    public List<String> availableLocales() {
        return availableLocales;
    }

    public Optional<String> metaName(String locale) {
        return metas.getOrDefault(locale == null ? "" : locale.trim(), LanguageMeta.empty()).name();
    }

    public String displayName(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        return metaName(normalized).or(() -> VolmitLocales.displayName(normalized)).orElse(normalized);
    }

    public Optional<Throwable> sourceFailure() {
        return Optional.ofNullable(sourceFailure);
    }

    public String sourceDescription() {
        if (manifest == null) {
            return "unavailable";
        }
        return manifest.repository() + "@" + manifest.branch();
    }

    public Optional<LanguageUpstreamSync.Status> status() {
        return sync == null ? Optional.empty() : Optional.of(sync.status());
    }

    public String diagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("== Wormholes languages ==\n");
        report.append("Language source: ").append(sourceDescription()).append('\n');
        report.append("Language source state: ").append(sourceState()).append('\n');
        report.append("Available locales: ").append(String.join(", ", availableLocales())).append('\n');
        return report.toString();
    }

    private String sourceState() {
        if (sourceFailure != null) {
            return "unavailable (" + sourceFailure.getClass().getSimpleName() + ")";
        }
        Optional<LanguageUpstreamSync.Status> status = status();
        if (status.isEmpty()) {
            return "unavailable";
        }
        LanguageUpstreamSync.Status current = status.get();
        StringBuilder state = new StringBuilder();
        state.append(current.revision().map(revision -> "revision " + revision.substring(0, Math.min(7, revision.length())))
                .orElse("not checked"));
        if (current.checkedMillis() > 0L) {
            state.append(", checked ").append(Instant.ofEpochMilli(current.checkedMillis()));
        }
        if (current.checking()) {
            state.append(", checking");
        }
        current.lastError().ifPresent(error -> state.append(", last error: ").append(error));
        return state.toString();
    }

    public Path directory() {
        return directory.root();
    }

    public boolean isLanguageFile(Path path) {
        return directory.isLanguageFile(path);
    }

    @Override
    public void close() {
        selfWriteListener = null;
        updateListener = null;
        selections = null;
        if (sync != null) {
            sync.close();
        }
    }

    private LocalizationCandidate load(String locale, String fallbackLocales, boolean installing) throws IOException {
        directory.prepare();
        ensureEnglish();
        List<String> requestedLocales = requestedLocales(locale, fallbackLocales);
        List<LocaleOverlay> overlays = new ArrayList<>(requestedLocales.size());
        Set<String> visited = new HashSet<>();
        for (String requestedLocale : requestedLocales) {
            appendChain(requestedLocale, overlays, visited, installing);
        }
        refreshAvailableLocales();
        return new LocalizationCandidate(WormholesMessages.catalog(), overlays, PluralSelector.oneOther());
    }

    private void appendChain(String locale, List<LocaleOverlay> overlays, Set<String> visited, boolean installing) {
        String current = locale;
        int depth = 0;
        while (visited.add(comparable(current))) {
            Path file = directory.languageFile(current);
            String content;
            try {
                if (!directory.exists(current) && isRepositoryLocale(current)
                        && !LanguageDirectory.sameLocale(current, WormholesMessages.ENGLISH_LOCALE)) {
                    if (!installing || sync == null) {
                        return;
                    }
                    logger.info("Installing Wormholes language " + current + " from " + sourceDescription() + "...");
                    content = sync.install(current);
                    logger.info("Installed Wormholes language " + current + " to " + file + ".");
                } else {
                    content = LanguageDirectory.readContent(file);
                }
                overlays.add(loadOverlay(content, file.toString(), current));
            } catch (IOException | RuntimeException exception) {
                logger.log(Level.WARNING, "Using English fallback for unreadable language file " + file, exception);
                return;
            }
            Optional<String> base = metaFromContent(current, content).base();
            if (base.isEmpty() || depth >= MAXIMUM_BASE_DEPTH
                    || LanguageDirectory.sameLocale(base.get(), WormholesMessages.ENGLISH_LOCALE)) {
                return;
            }
            try {
                current = WormholesLocales.require(base.get());
            } catch (IllegalArgumentException exception) {
                logger.log(Level.WARNING, "Ignoring invalid base language " + base.get() + " in " + file, exception);
                return;
            }
            depth++;
        }
    }

    private void ensureEnglish() throws IOException {
        if (sync != null) {
            try {
                sync.mergeEnglish().ifPresent(result -> {
                    if (result.changed()) {
                        logger.info("Updated Wormholes language " + WormholesMessages.ENGLISH_LOCALE
                                + " from the built-in catalog: " + result.summary() + ".");
                    }
                });
                return;
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Could not refresh the Wormholes English language file; "
                        + "built-in English stays active for invalid entries.", exception);
                return;
            }
        }
        if (directory.exists(WormholesMessages.ENGLISH_LOCALE)) {
            return;
        }
        String content = englishReference();
        LanguageFileEditor.update(directory.languageFile(WormholesMessages.ENGLISH_LOCALE),
                existing -> new LanguageFileEditor.Prepared<>(content, content));
        noteUpstreamWrite(directory.languageFile(WormholesMessages.ENGLISH_LOCALE), content);
    }

    private <T> T commitUpstream(LanguageUpstreamSync.CommitAction<T> action) throws IOException {
        PluginLanguageService activeSelections = selections;
        if (activeSelections == null) {
            return action.apply();
        }
        return activeSelections.commitUpdate(action::apply);
    }

    private void noteUpstreamWrite(Path file, String content) {
        BiConsumer<Path, String> listener = selfWriteListener;
        if (listener != null) {
            listener.accept(file, content);
        }
    }

    private void applyUpstream(String locale, String content, LanguageCatalogMerge.MergeResult result) throws IOException {
        validateContent(locale, content);
        refreshAvailableLocales();
        Consumer<String> listener = updateListener;
        if (listener != null) {
            listener.accept(locale);
        }
    }

    private void validateContent(String locale, String content) throws IOException {
        LocaleOverlay overlay = loadOverlay(content, "upstream:" + locale, locale);
        try {
            LocalizationSnapshot.create(new LocalizationCandidate(
                    WormholesMessages.catalog(), List.of(overlay), PluralSelector.oneOther()));
        } catch (RuntimeException exception) {
            throw new IOException("Language content is invalid: " + locale, exception);
        }
    }

    private LanguageUpstreamSync.SyncReport logUpstreamReport(LanguageUpstreamSync.SyncReport report) {
        String revision = report.revision().map(sha -> sha.substring(0, Math.min(7, sha.length()))).orElse("unknown");
        for (String locale : report.changedLocales()) {
            LanguageCatalogMerge.MergeResult result = report.merged().get(locale);
            String source = LanguageDirectory.sameLocale(locale, WormholesMessages.ENGLISH_LOCALE)
                    ? "the built-in catalog" : sourceDescription() + " (" + revision + ")";
            logger.info("Updated Wormholes language " + locale + " from " + source + ": " + result.summary() + ".");
        }
        if (!report.coolingDown() && report.failure().isEmpty() && report.changedLocales().isEmpty()
                && report.failures().isEmpty() && report.revision().isPresent()) {
            logger.info("Wormholes languages match " + sourceDescription() + " (" + revision + ").");
        }
        return report;
    }

    private void refreshAvailableLocales() throws IOException {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(WormholesMessages.ENGLISH_LOCALE);
        if (manifest != null) {
            choices.addAll(manifest.locales());
        }
        List<String> installed = directory.scan();
        choices.addAll(installed);
        LinkedHashMap<String, LanguageMeta> discovered = new LinkedHashMap<>();
        for (String locale : installed) {
            try {
                LanguageMeta meta = directory.meta(locale);
                if (!meta.isEmpty()) {
                    discovered.put(locale, meta);
                }
            } catch (IOException exception) {
                logger.log(Level.FINE, "Could not read language metadata for " + locale, exception);
            }
        }
        List<String> sorted = new ArrayList<>(choices);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        metas = Map.copyOf(discovered);
        availableLocales = List.copyOf(sorted);
    }

    private void refreshAvailableLocalesQuietly() {
        try {
            refreshAvailableLocales();
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not list Wormholes language files", exception);
        }
    }

    private List<String> requestedLocales(String locale, String fallbackLocales) {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        String selected = WormholesLocales.normalize(locale);
        locales.add(selected);
        if (!WormholesMessages.ENGLISH_LOCALE.equals(selected)
                && fallbackLocales != null && !fallbackLocales.isBlank()) {
            for (String fallback : fallbackLocales.split(",")) {
                if (!fallback.isBlank()) {
                    try {
                        locales.add(WormholesLocales.require(fallback));
                    } catch (IllegalArgumentException exception) {
                        logger.log(Level.WARNING, "Ignoring invalid fallback language locale: " + fallback, exception);
                    }
                }
            }
        }
        locales.add(WormholesMessages.ENGLISH_LOCALE);
        return List.copyOf(locales);
    }

    private LanguageMeta metaFromContent(String locale, String content) {
        try {
            return LanguageMeta.parse(content).withoutSelfBase(locale);
        } catch (IOException exception) {
            return LanguageMeta.empty();
        }
    }

    private static String comparable(String locale) {
        return locale.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String englishReference() {
        return LanguageReferenceRenderer.render(WormholesMessages.catalog(), WormholesLanguageHeader.english());
    }

    private static LocalizationSnapshot editedSnapshot(String content, Path path, String locale,
                                                       List<LocaleOverlay> base) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>(base.size() + 1);
        overlays.add(loadOverlay(content, path.toString(), locale));
        overlays.addAll(base);
        return LocalizationSnapshot.create(new LocalizationCandidate(
                WormholesMessages.catalog(), overlays, PluralSelector.oneOther()));
    }

    private static LocaleOverlay loadOverlay(String content, String source, String locale) throws IOException {
        MessageCatalog catalog = WormholesMessages.catalog();
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        for (Map.Entry<String, MessageValue> entry : TomlLanguageParser.parseValidValues(
                content, catalog).entrySet()) {
            if (!hasBlankTranslation(catalog.require(entry.getKey()), entry.getValue())) {
                overlay.put(entry.getKey(), entry.getValue());
            }
        }
        return overlay.build();
    }

    private static boolean hasBlankTranslation(MessageKey key, MessageValue value) {
        if (key instanceof TextKey textKey && value instanceof TextValue textValue) {
            return !textKey.english().isBlank() && textValue.template().isBlank();
        }
        if (key instanceof LinesKey linesKey && value instanceof LinesValue linesValue) {
            for (int index = 0; index < Math.min(linesKey.english().size(), linesValue.lines().size()); index++) {
                if (!linesKey.english().get(index).isBlank() && linesValue.lines().get(index).isBlank()) {
                    return true;
                }
            }
        }
        if (key instanceof PluralKey pluralKey && value instanceof PluralValue pluralValue) {
            for (Map.Entry<String, String> form : pluralKey.english().entrySet()) {
                String translated = pluralValue.forms().get(form.getKey());
                if (!form.getValue().isBlank() && translated != null && translated.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }
}
