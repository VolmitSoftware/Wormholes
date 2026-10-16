package art.arcane.wormholes.clientgametest.menu;

import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.wormholes.clientgametest.QaReplies;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.MenuValue;
import art.arcane.wormholes.menu.language.LanguageMenuView;
import art.arcane.wormholes.menu.language.LanguageRoutes;
import art.arcane.wormholes.menu.rules.RulesRoutes;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.rules.Condition;
import art.arcane.wormholes.rules.Effect;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleAuthoring;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.RuleTemplates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public final class NativeMenuRulesLanguageScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String LOCALE = "en_US";
    private static final String TEMPLATE = "native-gate";
    private static final String DENIED_KEY = "portal.edit.denied";
    private static final String EDITED = "Edited from the native editor";
    private static final String ELSEWHERE = "Edited somewhere else";
    private static final String CONSOLE_LINE = "kind=COMMAND;line=say native;asConsole=true";
    private static final String PERMISSION_LINE = "kind=PERMISSION;node=group.vip";
    private static final String NO_PERMISSION = "You do not have permission to edit Wormholes languages.";
    private static final String CONFLICT = "Unable to edit: Language message changed while the editor was open";
    private static final int CATEGORY_PAGE = 16;
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;
    private static final long SAVE_SECONDS = 10L;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private UUID portalId;
    private MessageValue deniedOriginal;
    private String linesKey;

    private NativeMenuRulesLanguageScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuRulesLanguageScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        portalId = server.compute(NativeMenuRulesLanguageScenario::createPortal);
        deniedOriginal = server.compute((minecraftServer, player) -> deniedValue(minecraftServer));
        boolean operator = server.compute((minecraftServer, player) -> runtime(minecraftServer).access().administrator(player));
        try {
            operator(true);
            profile();
            ruleList();
            rulePage();
            templates();
            operator(true);
            languages();
            search();
            conflict();
            forms();
            denied();
        } finally {
            operator(true);
            restoreDenied();
            operator(operator);
            server.run((minecraftServer, player) -> cleanup(minecraftServer));
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_RULES_LANGUAGE_PASS screenshots={}", shots.captured().size());
    }

    private void profile() {
        open(RulesRoutes.profile(portalId), RulesRoutes.PROFILE);
        shots.capture(RulesRoutes.PROFILE);
        act(() -> driver.increment("rules-rules.menu.cooldown", false));
        awaitDocument("the cooldown increment", document -> document.profile().cooldownMillis() == 1_000L);
        act(() -> driver.typeAndApply("rules-group", "gates"));
        awaitDocument("the cooldown group", document -> document.profile().cooldownGroup().equals("gates"));
        act(() -> driver.toggle("rules-default"));
        awaitDocument("the default outcome toggle", document -> !document.defaultOutcome().allowed());
        act(() -> driver.toggle("rules-default"));
        awaitDocument("the default outcome restore", document -> document.defaultOutcome().allowed());
    }

    private void ruleList() {
        driver.click("rules-list");
        awaitScreen(RulesRoutes.LIST);
        act(() -> driver.typeAndApply("rules-add-rule", "toll"));
        awaitDocument("the added rule", document -> document.rules().size() == 1 && document.rules().getFirst().id().equals("toll"));
        check(driver.read(screen -> screen.rowFor("rules-rule-toll").isPresent()), "the rule list shows no toll row");
        shots.capture(RulesRoutes.LIST);
    }

    private void rulePage() {
        driver.click("rules-rule-toll");
        awaitScreen(RulesRoutes.RULE);
        act(() -> driver.typeAndApply("rules-add-line", PERMISSION_LINE));
        awaitDocument("the permission line", document -> document.rules().getFirst().conditions().stream()
            .anyMatch(condition -> condition instanceof Condition.Permission permission && permission.node().equals("group.vip")));
        act(() -> driver.typeAndApply("rules-add-line", CONSOLE_LINE));
        awaitDocument("the administrator console effect", document -> consoleEffects(document) == 1);
        check(Objects.equals(consoleAuthor(), playerId()), "the console effect was not authored by the administrator");
        shots.capture(RulesRoutes.RULE);
        operator(false);
        answer(() -> driver.typeAndApply("rules-add-line", CONSOLE_LINE));
        check(driver.feedback().filter(text -> text.contains(RuleAuthoring.CONSOLE_EFFECT_REFUSED)).isPresent(),
            "a deopped player adding a console effect saw " + driver.feedback());
        check(readDocument(document -> consoleEffects(document) == 1), "a deopped player stored a second console effect");
        driver.back();
        awaitScreen(RulesRoutes.LIST);
        driver.back();
        awaitScreen(RulesRoutes.PROFILE);
    }

    private void templates() {
        server.run((minecraftServer, player) -> saveTemplate(minecraftServer));
        driver.click("rules-templates");
        awaitScreen(RulesRoutes.TEMPLATES);
        check(driver.read(screen -> screen.rowFor("rules-template-" + TEMPLATE).isPresent()), "the templates screen lists no " + TEMPLATE);
        shots.capture(RulesRoutes.TEMPLATES);
        driver.click("rules-template-" + TEMPLATE);
        driver.awaitConfirmationArmed();
        act(driver::confirm);
        awaitDocument("the applied template", document -> document.rules().size() == 1 && document.rules().getFirst().id().equals("members"));
        driver.escape();
        awaitScreen(RulesRoutes.PROFILE);
        driver.escape();
        driver.awaitClosed();
    }

    private void languages() {
        open(LanguageRoutes.locales(), LanguageRoutes.LOCALES);
        check(driver.read(screen -> screen.rowFor("language-locale-" + LOCALE).isPresent()), "the locale list has no " + LOCALE);
        shots.capture(LanguageRoutes.LOCALES);
        driver.click("language-locale-" + LOCALE);
        awaitScreen(LanguageRoutes.GROUPS);
        List<String> groups = elementIds("language-group-");
        check(!groups.isEmpty() && groups.size() <= CATEGORY_PAGE, "the groups screen lists " + groups);
        shots.capture(LanguageRoutes.GROUPS);
    }

    private void search() {
        driver.typeAndApply("language-search", DENIED_KEY);
        awaitScreen(LanguageRoutes.KEYS);
        List<String> keys = elementIds("language-key-");
        check(keys.contains("language-key-" + DENIED_KEY), "the search found " + keys);
        check(keys.stream().allMatch(id -> id.contains(DENIED_KEY)), "the search kept unrelated keys " + keys);
        shots.capture(LanguageRoutes.KEYS);
        act(() -> driver.typeAndApply("language-key-" + DENIED_KEY, EDITED));
        awaitDenied("the edited message", EDITED);
        check(driver.feedback().filter(text -> text.contains("Saved " + LOCALE)).isPresent(), "the saved edit answered " + driver.feedback());
    }

    private void conflict() {
        save(new TextValue(EDITED), new TextValue(ELSEWHERE));
        answer(() -> driver.typeAndApply("language-key-" + DENIED_KEY, "Native conflict"));
        check(driver.feedback().filter(text -> text.contains(CONFLICT)).isPresent(), "the conflicting edit answered " + driver.feedback());
        awaitDenied("the external edit", ELSEWHERE);
    }

    private void forms() {
        linesKey = server.compute((minecraftServer, player) -> linesKey(minecraftServer));
        driver.back();
        awaitScreen(LanguageRoutes.GROUPS);
        driver.typeAndApply("language-search", linesKey);
        awaitScreen(LanguageRoutes.KEYS);
        driver.click("language-key-" + linesKey);
        awaitScreen(LanguageRoutes.FORMS);
        check(driver.read(screen -> screen.rowFor("language-form-0").isPresent()), "the forms screen has no first line");
        shots.capture(LanguageRoutes.FORMS);
    }

    private void denied() {
        MessageValue before = server.compute((minecraftServer, player) -> value(minecraftServer, linesKey));
        operator(false);
        QaReplies.clearRecent();
        boolean sent = driver.input().computeOnClient(minecraft -> ClientMenus.session().act("language-form-0", MenuInteraction.SET_TEXT,
            MenuValue.text("Denied edit")));
        driver.awaitClosed();
        check(server.compute((minecraftServer, player) -> value(minecraftServer, linesKey)).equals(before),
            "a deopped player changed a language message (sent=" + sent + ")");
        for (int tick = 0; tick < TIMEOUT_TICKS && !received(NO_PERMISSION); tick++) {
            driver.input().waitTicks(1);
        }
        check(received(NO_PERMISSION), "the deopped player saw no permission notice: " + QaReplies.recent());
        QaReplies.clearRecent();
        server.run((minecraftServer, player) -> runtime(minecraftServer).menus().open(player.getUUID(), LanguageRoutes.locales()));
        for (int tick = 0; tick < TIMEOUT_TICKS && !received(NO_PERMISSION); tick++) {
            driver.input().waitTicks(1);
        }
        check(received(NO_PERMISSION), "the deopped player opening the editor saw no permission notice: " + QaReplies.recent());
        check(!driver.open(), "the language editor opened for a deopped player");
    }

    private void operator(boolean operator) {
        server.run((minecraftServer, player) -> {
            if (operator) {
                minecraftServer.getPlayerList().op(player.nameAndId());
            } else {
                minecraftServer.getPlayerList().deop(player.nameAndId());
            }
            check(runtime(minecraftServer).access().administrator(player) == operator, "the operator change did not reach the permission check");
        });
    }

    private void restoreDenied() {
        MessageValue current = server.compute((minecraftServer, player) -> deniedValue(minecraftServer));
        if (!current.equals(deniedOriginal)) {
            save(current, deniedOriginal);
        }
    }

    private void save(MessageValue expected, MessageValue value) {
        CompletableFuture<PluginLanguageEditor.Document> saved = server.compute((minecraftServer, player) -> runtime(minecraftServer)
            .localization().editor().save(new PluginLanguageEditor.Edit(LOCALE, DENIED_KEY, expected, value)));
        try {
            saved.get(SAVE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the language save was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("the language save of " + DENIED_KEY + " failed", failure);
        }
    }

    private void open(MenuRoute route, String screenId) {
        server.run((minecraftServer, player) -> runtime(minecraftServer).menus().open(player.getUUID(), route));
        awaitScreen(screenId);
    }

    private void act(Runnable action) {
        long revision = revision();
        action.run();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().revision() > revision && !screen.feedbackLine().pending())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void answer(Runnable action) {
        action.run();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> !screen.feedbackLine().pending() && screen.feedbackLine().message().isPresent())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitScreen(String screenId) {
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().screenId().equals(screenId) && !screen.feedbackLine().pending())
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitDocument(String description, Predicate<RuleDocument> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (readDocument(condition)) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the portal rules");
    }

    private void awaitDenied(String description, String expected) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute((minecraftServer, player) -> renderedDenied(minecraftServer)).equals(expected)) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server language");
    }

    private boolean readDocument(Predicate<RuleDocument> condition) {
        return server.compute((minecraftServer, player) -> condition.test(document(minecraftServer)));
    }

    private UUID consoleAuthor() {
        return server.compute((minecraftServer, player) -> {
            for (Effect effect : document(minecraftServer).rules().getFirst().effects()) {
                if (effect instanceof Effect.Command command && command.asConsole()) {
                    return command.authorId();
                }
            }
            return null;
        });
    }

    private UUID playerId() {
        return server.compute((minecraftServer, player) -> player.getUUID());
    }

    private List<String> elementIds(String prefix) {
        return driver.read(screen -> screen.snapshot().elements().stream().map(element -> element.id()).filter(id -> id.startsWith(prefix)).toList());
    }

    private long revision() {
        return driver.read(screen -> screen.snapshot().revision());
    }

    private RuleDocument document(MinecraftServer minecraftServer) {
        MinecraftPortal portal = Objects.requireNonNull(runtime(minecraftServer).portals().get(portalId), "the rules test portal is gone");
        return runtime(minecraftServer).rules().document(portal);
    }

    private void saveTemplate(MinecraftServer minecraftServer) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        RuleDocument template = RuleDocument.EMPTY.withRules(List.of(new Rule("members", List.of(new Condition.Permission("group.members")),
            RuleOutcome.allow(), List.of(), List.of())));
        try {
            new RuleTemplates(runtime.stores().data(), runtime.configuration().settings().getRules()).save(TEMPLATE, template);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void cleanup(MinecraftServer minecraftServer) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        runtime.portals().remove(portalId);
        Path template = runtime.stores().data().resolve("rules").resolve("templates").resolve(TEMPLATE + ".toml");
        try {
            Files.deleteIfExists(template);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static boolean received(String text) {
        return QaReplies.recent().stream().anyMatch(reply -> reply.contains(text));
    }

    private static long consoleEffects(RuleDocument document) {
        return document.rules().getFirst().effects().stream()
            .filter(effect -> effect instanceof Effect.Command command && command.asConsole())
            .count();
    }

    private static MessageValue deniedValue(MinecraftServer minecraftServer) {
        return value(minecraftServer, DENIED_KEY);
    }

    private static MessageValue value(MinecraftServer minecraftServer, String key) {
        LocalizationSnapshot snapshot = runtime(minecraftServer).localization().snapshot(null);
        return snapshot.value(snapshot.catalog().require(key));
    }

    private static String renderedDenied(MinecraftServer minecraftServer) {
        return runtime(minecraftServer).localization().text(null, WormholesMessages.PORTAL_EDIT_DENIED, Map.of()).getString();
    }

    private static String linesKey(MinecraftServer minecraftServer) {
        LanguageMenuView view = LanguageMenuView.of(new PluginLanguageEditor.Document(LOCALE, runtime(minecraftServer).localization().snapshot(null)));
        for (LanguageMenuView.Message message : view.messages()) {
            if (message.value() instanceof LinesValue && view.matching(null, message.key()).size() == 1) {
                return message.key();
            }
        }
        throw new AssertionError("the catalog has no uniquely searchable lines message");
    }

    private static UUID createPortal(MinecraftServer minecraftServer, ServerPlayer player) {
        ServerLevel level = minecraftServer.overworld();
        BlockPos base = player.blockPosition().offset(-3, 0, 4);
        for (int x = -1; x <= PORTAL_WIDTH; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= PORTAL_HEIGHT; y++) {
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        List<BlockPos> cells = new ArrayList<>(PORTAL_WIDTH * PORTAL_HEIGHT);
        for (int x = 0; x < PORTAL_WIDTH; x++) {
            for (int y = 0; y < PORTAL_HEIGHT; y++) {
                cells.add(base.offset(x, y, 0));
            }
        }
        MinecraftPortal portal = runtime(minecraftServer).portals().create(player.getUUID(), level, cells, PortalType.PORTAL, new Vec3(0, 0, -1));
        check(portal != null, "the rules test portal was refused");
        return portal.getId();
    }

    private static WormholesModRuntime runtime(MinecraftServer minecraftServer) {
        return Objects.requireNonNull(WormholesModRuntime.forServer(minecraftServer), "Wormholes runtime is not running on this server");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
