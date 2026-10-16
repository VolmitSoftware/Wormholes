package art.arcane.wormholes.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import art.arcane.wormholes.menu.protocol.MenuClientMessage;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuRateLimiter;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.service.WormholesTelemetry;

public final class MenuService implements MenuServiceApi {
    private static final Logger LOGGER = Logger.getLogger(MenuService.class.getName());
    private static final String REFRESH_SCHEDULE_REJECTED = "PORTAL_MENU_REFRESH_SCHEDULE_REJECTED";

    private final MenuHost host;
    private final MenuRegistry registry;
    private final LongSupplier clock;
    private final MenuRenderer nativeRenderer;
    private final MenuCapabilities capabilities = new MenuCapabilities();
    private final Map<UUID, MenuSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, MenuRateLimiter> limiters = new ConcurrentHashMap<>();
    private final Set<UUID> nativeWarnings = ConcurrentHashMap.newKeySet();
    private final Set<UUID> resetViewers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> ignoredBeforeHello = ConcurrentHashMap.newKeySet();
    private final Map<UUID, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final AtomicLong ticks = new AtomicLong();

    public MenuService(MenuHost host, MenuRegistry registry) {
        this(host, registry, System::currentTimeMillis);
    }

    public MenuService(MenuHost host, MenuRegistry registry, LongSupplier clock) {
        this.host = Objects.requireNonNull(host, "host");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nativeRenderer = new NativeMenuRenderer(host.transport());
    }

    @Override
    public void open(UUID viewerId, MenuRoute route) {
        Objects.requireNonNull(route, "route");
        registry.require(route.controllerId());
        host.runForViewer(viewerId, () -> openNow(viewerId, route));
    }

    @Override
    public void receive(UUID viewerId, byte[] frame) {
        if (resetViewers.contains(viewerId)) {
            return;
        }
        MenuRateLimiter limiter = limiters.get(viewerId);
        if (limiter == null) {
            receiveBeforeHello(viewerId, frame);
            return;
        }
        MenuRateLimiter.Verdict admission = limiter.admit(clock.getAsLong(), frame.length);
        if (admission == MenuRateLimiter.Verdict.RESET) {
            reset(viewerId, limiter);
            return;
        }
        if (admission == MenuRateLimiter.Verdict.DROP) {
            return;
        }
        MenuClientMessage message;
        try {
            message = MenuCodec.decodeClient(frame);
        } catch (MenuProtocolException exception) {
            violation(viewerId, exception.getMessage());
            return;
        }
        if (message instanceof MenuClientMessage.Hello hello) {
            hello(viewerId, hello.version());
            return;
        }
        if (capabilities.capability(viewerId).isEmpty()) {
            return;
        }
        host.runForViewer(viewerId, () -> dispatch(viewerId, message));
    }

    @Override
    public void chestAction(UUID viewerId, MenuChestAction action) {
        MenuSession session = sessions.get(viewerId);
        Optional<MenuViewer> viewer = host.viewer(viewerId);
        if (session == null || viewer.isEmpty()) {
            return;
        }
        MenuAction menuAction = new MenuAction(session.revision(), action.elementId(), action.interaction(), action.value());
        session.act(viewer.get(), menuAction, MenuActionOrigin.CHEST, action.preconfirmed(), action.capturedItem(), ticks.get());
        prune(viewerId, session);
    }

    @Override
    public boolean chestPrecheck(UUID viewerId, String elementId, MenuInteraction interaction) {
        MenuSession session = sessions.get(viewerId);
        Optional<MenuViewer> viewer = host.viewer(viewerId);
        if (session == null || viewer.isEmpty()) {
            return false;
        }
        boolean allowed = session.precheck(viewer.get(), elementId, interaction, ticks.get());
        prune(viewerId, session);
        return allowed;
    }

    @Override
    public void chestEscape(UUID viewerId) {
        MenuSession session = sessions.get(viewerId);
        Optional<MenuViewer> viewer = host.viewer(viewerId);
        if (session == null || viewer.isEmpty()) {
            return;
        }
        session.escape(viewer.get(), ticks.get());
        prune(viewerId, session);
    }

    @Override
    public void chestEnd(UUID viewerId) {
        MenuSession session = sessions.get(viewerId);
        if (session == null) {
            return;
        }
        session.discard();
        prune(viewerId, session);
    }

    @Override
    public void resume(UUID viewerId) {
        host.runForViewer(viewerId, () -> {
            MenuSession session = sessions.get(viewerId);
            Optional<MenuViewer> viewer = host.viewer(viewerId);
            if (session == null || viewer.isEmpty()) {
                return;
            }
            session.resume(viewer.get(), ticks.get());
            prune(viewerId, session);
        });
    }

    @Override
    public void invalidate(MenuScope scope) {
        for (Map.Entry<UUID, MenuSession> entry : sessions.entrySet()) {
            MenuRoute route = entry.getValue().currentRoute();
            if (route != null && touches(route, scope)) {
                scheduleRefresh(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void tick() {
        long tick = ticks.incrementAndGet();
        boolean revocation = tick % MenuLimits.REVOCATION_INTERVAL_TICKS == 0L;
        for (Map.Entry<UUID, MenuSession> entry : sessions.entrySet()) {
            MenuSession session = entry.getValue();
            if (revocation) {
                scheduleRevocation(entry.getKey(), session);
            }
            if (session.refreshDueTick() <= tick) {
                scheduleRefresh(entry.getKey(), session);
            }
        }
    }

    @Override
    public void quit(UUID viewerId) {
        capabilities.clear(viewerId);
        limiters.remove(viewerId);
        nativeWarnings.remove(viewerId);
        resetViewers.remove(viewerId);
        ignoredBeforeHello.remove(viewerId);
        revisions.remove(viewerId);
        MenuSession session = sessions.remove(viewerId);
        if (session != null) {
            host.runForViewer(viewerId, session::discard);
        }
    }

    @Override
    public void shutdown() {
        for (Map.Entry<UUID, MenuSession> entry : sessions.entrySet()) {
            UUID viewerId = entry.getKey();
            MenuSession session = entry.getValue();
            host.runForViewer(viewerId, () -> host.viewer(viewerId).ifPresent(session::close));
        }
        sessions.clear();
        limiters.clear();
    }

    @Override
    public MenuCapabilities capabilities() {
        return capabilities;
    }

    MenuHost host() {
        return host;
    }

    MenuRegistry registry() {
        return registry;
    }

    Logger logger() {
        return LOGGER;
    }

    void violation(UUID viewerId, String reason) {
        MenuRateLimiter limiter = limiters.get(viewerId);
        if (limiter != null && limiter.violation(clock.getAsLong(), reason) == MenuRateLimiter.Verdict.RESET) {
            reset(viewerId, limiter);
        }
    }

    void nativeFailed(MenuViewer viewer, MenuProtocolException exception) {
        if (nativeWarnings.add(viewer.id())) {
            host.warn("Native menu for " + viewer.name() + " fell back to the chest menu: " + exception.getMessage());
        }
    }

    long nextRevision(UUID viewerId) {
        return revisions.computeIfAbsent(viewerId, ignored -> new AtomicLong()).incrementAndGet();
    }

    void completePending(UUID viewerId, MenuSession session, CompletableFuture<MenuResult> awaited, MenuResult result, Throwable error) {
        host.runForViewer(viewerId, () -> {
            Optional<MenuViewer> viewer = host.viewer(viewerId);
            if (viewer.isEmpty()) {
                return;
            }
            session.completePending(viewer.get(), awaited, result, error, ticks.get());
            prune(viewerId, session);
        });
    }

    private void openNow(UUID viewerId, MenuRoute route) {
        Optional<MenuViewer> found = host.viewer(viewerId);
        if (found.isEmpty()) {
            return;
        }
        MenuViewer viewer = found.get();
        MenuRendererChoice choice = MenuRendererChoice.choose(capabilities.capability(viewerId), host.nativeScreensEnabled());
        boolean nativeScreen = choice == MenuRendererChoice.NATIVE;
        MenuSession session = new MenuSession(viewerId, this, nativeScreen ? nativeRenderer : host.chestRenderer(), nativeScreen);
        MenuDecision decision = session.admit(viewer, route);
        if (decision instanceof MenuDecision.Deny deny) {
            host.chestRenderer().notice(viewer, deny.notice());
            return;
        }
        MenuSession previous = sessions.put(viewerId, session);
        if (previous != null && !previous.ended()) {
            if (previous.nativeScreen() != nativeScreen) {
                previous.close(viewer);
            } else {
                previous.discard();
            }
        }
        session.start(viewer, route, decision, ticks.get());
        prune(viewerId, session);
    }

    private void dispatch(UUID viewerId, MenuClientMessage message) {
        MenuSession session = sessions.get(viewerId);
        Optional<MenuViewer> found = host.viewer(viewerId);
        if (session == null || found.isEmpty() || !session.nativeScreen()) {
            return;
        }
        MenuViewer viewer = found.get();
        long tick = ticks.get();
        switch (message) {
            case MenuClientMessage.Hello _ -> {
            }
            case MenuClientMessage.Action action -> session.act(viewer, action.action(), MenuActionOrigin.NATIVE, false, null, tick);
            case MenuClientMessage.Back back -> session.back(viewer, back.revision(), tick);
            case MenuClientMessage.Page page -> session.page(viewer, page.revision(), page.page(), tick);
            case MenuClientMessage.Confirm confirm -> session.confirm(viewer, confirm.revision(), confirm.accepted(), tick);
            case MenuClientMessage.Closed _ -> session.clientClosed();
        }
        prune(viewerId, session);
    }

    private void receiveBeforeHello(UUID viewerId, byte[] frame) {
        if (frame.length > MenuLimits.MAX_C2S_BYTES || frame.length == 0 || Byte.toUnsignedInt(frame[0]) != MenuCodec.C2S_HELLO) {
            ignoreBeforeHello(viewerId, frame.length);
            return;
        }
        MenuClientMessage message;
        try {
            message = MenuCodec.decodeClient(frame);
        } catch (MenuProtocolException exception) {
            ignoreBeforeHello(viewerId, frame.length);
            return;
        }
        if (message instanceof MenuClientMessage.Hello hello) {
            hello(viewerId, hello.version());
        }
    }

    private void ignoreBeforeHello(UUID viewerId, int frameBytes) {
        if (ignoredBeforeHello.add(viewerId)) {
            LOGGER.log(Level.FINE, () -> "Ignored menu frame of " + frameBytes + " bytes from " + viewerId + " before hello");
        }
    }

    private void hello(UUID viewerId, int version) {
        if (version < 1) {
            withdraw(viewerId);
            return;
        }
        MenuCapability capability = capabilities.accept(viewerId, version).orElseThrow();
        if (resetViewers.contains(viewerId)) {
            capabilities.clear(viewerId);
            return;
        }
        limiters.computeIfAbsent(viewerId, ignored -> new MenuRateLimiter());
        try {
            host.transport().send(viewerId, MenuCodec.encodeServer(new MenuServerMessage.Accept(capability.version())));
        } catch (MenuProtocolException exception) {
            throw new IllegalStateException("menu accept could not be encoded", exception);
        }
    }

    private void withdraw(UUID viewerId) {
        if (limiters.remove(viewerId) == null) {
            return;
        }
        host.warn("Native menus for " + viewerId + " were withdrawn by the client after a protocol error; chest menus are used until the player reconnects.");
        resetViewers.add(viewerId);
        capabilities.clear(viewerId);
        host.runForViewer(viewerId, () -> reopenAsChest(viewerId));
    }

    private void reopenAsChest(UUID viewerId) {
        MenuSession session = sessions.get(viewerId);
        if (session == null || session.ended() || !session.nativeScreen() || session.currentRoute() == null) {
            return;
        }
        MenuRoute route = session.currentRoute();
        session.discard();
        sessions.remove(viewerId, session);
        openNow(viewerId, route);
    }

    private void reset(UUID viewerId, MenuRateLimiter limiter) {
        host.warn("Menu session for " + viewerId + " was reset: " + limiter.summary());
        resetViewers.add(viewerId);
        capabilities.clear(viewerId);
        limiters.remove(viewerId);
        MenuSession session = sessions.remove(viewerId);
        if (session == null) {
            return;
        }
        host.runForViewer(viewerId, () -> {
            Optional<MenuViewer> viewer = host.viewer(viewerId);
            if (viewer.isPresent() && session.nativeScreen()) {
                session.close(viewer.get());
            } else {
                session.discard();
            }
        });
    }

    private boolean touches(MenuRoute route, MenuScope scope) {
        try {
            return registry.require(route.controllerId()).touches(route, scope);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Menu invalidation check for " + route.screen() + " failed", exception);
            return false;
        }
    }

    private void scheduleRefresh(UUID viewerId, MenuSession session) {
        if (!session.queueRefresh()) {
            return;
        }
        boolean scheduled = host.runForViewer(viewerId, () -> {
            Optional<MenuViewer> viewer = host.viewer(viewerId);
            if (viewer.isEmpty()) {
                session.refreshRejected();
                return;
            }
            session.refresh(viewer.get(), ticks.get());
            prune(viewerId, session);
        });
        if (!scheduled) {
            session.refreshRejected();
            WormholesTelemetry.countFailure(REFRESH_SCHEDULE_REJECTED);
            host.warn("Menu refresh for " + viewerId + " was dropped because the viewer scheduler rejected the work.");
        }
    }

    private void scheduleRevocation(UUID viewerId, MenuSession session) {
        host.runForViewer(viewerId, () -> {
            Optional<MenuViewer> viewer = host.viewer(viewerId);
            if (viewer.isEmpty()) {
                return;
            }
            session.revoke(viewer.get(), ticks.get());
            prune(viewerId, session);
        });
    }

    private void prune(UUID viewerId, MenuSession session) {
        if (session.ended()) {
            sessions.remove(viewerId, session);
        }
    }
}
