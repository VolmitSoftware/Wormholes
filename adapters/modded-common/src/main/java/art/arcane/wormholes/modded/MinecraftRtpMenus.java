package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpBiomeMatcher;
import art.arcane.wormholes.portal.rtp.RtpPortalEditor;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MinecraftRtpMenus implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String POCKET_DIMENSION = "wormholes:pockets";

    private final WormholesModRuntime runtime;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public MinecraftRtpMenus(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public void open(ServerPlayer viewer, UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal == null || !runtime.menus().ensureCanManage(viewer, portal)) {
            return;
        }
        if (portal.getType() != PortalType.RTP) {
            MinecraftPortalText.notifySetting(viewer, portal, WormholesMessages.PORTAL_NOT_RTP, MessageArgs.empty());
            runtime.menus().open(viewer, portal.getId());
            return;
        }
        Session replacement = new Session(viewer, portal);
        Session previous = sessions.put(viewer.getUUID(), replacement);
        if (previous != null) {
            previous.close();
        }
        replacement.open();
    }

    public void tick() {
        sessions.values().removeIf(session -> !session.window.isVisible());
    }

    public void disconnected(ServerPlayer viewer) {
        sessions.remove(viewer.getUUID());
    }

    @Override
    public void close() {
        for (Session session : List.copyOf(sessions.values())) {
            session.close();
        }
        sessions.clear();
    }

    static List<RtpPortalEditorModel.BiomeOption> biomeOptions(List<String> registeredKeys) {
        Map<String, RtpPortalEditorModel.BiomeOption> options = new LinkedHashMap<>();
        for (String registeredKey : registeredKeys) {
            String key = RtpBiomeMatcher.normalize(registeredKey);
            if (key != null && !key.startsWith("iris:")) {
                options.putIfAbsent(key, new RtpPortalEditorModel.BiomeOption(key, prettyPath(key), false));
            }
        }
        List<RtpPortalEditorModel.BiomeOption> sorted = new ArrayList<>(options.values());
        sorted.sort(Comparator
            .comparing(RtpPortalEditorModel.BiomeOption::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RtpPortalEditorModel.BiomeOption::key));
        return List.copyOf(sorted);
    }

    private ServerLevel level(String key) {
        for (ServerLevel level : runtime.server().getAllLevels()) {
            if (level.dimension().identifier().toString().equals(key)) {
                return level;
            }
        }
        return null;
    }

    private static RtpWorld world(ServerLevel level) {
        if (level == null) {
            return null;
        }
        String key = level.dimension().identifier().toString();
        return new RtpWorld(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key,
            level.getMinY(), level.getMaxY() + 1, level.getSeaLevel());
    }

    private static String prettyPath(String key) {
        int separator = key.lastIndexOf(':');
        String path = separator < 0 ? key : key.substring(separator + 1);
        String[] words = path.split("_");
        StringBuilder pretty = new StringBuilder(path.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!pretty.isEmpty()) {
                pretty.append(' ');
            }
            pretty.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return pretty.isEmpty() ? key : pretty.toString();
    }

    private final class Session implements RtpPortalEditor.Host {
        private final ServerPlayer viewer;
        private final UUID viewerId;
        private final MinecraftPortal portal;
        private final MinecraftWindow window;
        private final RtpPortalEditor editor;
        private final MinecraftRtpMenuView view;
        private RtpSettings knownSettings;
        private long revision;
        private long baseRevision;

        private Session(ServerPlayer viewer, MinecraftPortal portal) {
            this.viewer = viewer;
            this.portal = portal;
            viewerId = viewer.getUUID();
            window = new MinecraftWindow(runtime, viewer);
            window.onClosed(closed -> sessions.remove(viewerId, this));
            view = new MinecraftRtpMenuView(window);
            editor = new RtpPortalEditor(this);
            baseRevision = revision();
        }

        private void open() {
            editor.populate(view, viewerId);
            window.setVisible(true);
        }

        private void close() {
            if (window.isVisible()) {
                window.close();
            }
            sessions.remove(viewerId, this);
        }

        @Override
        public String text(TextKey key, MessageArgs arguments) {
            return MinecraftMenuText.text(viewer, key, arguments).getString();
        }

        @Override
        public RtpPortalEditorModel.EditorSnapshot snapshot(UUID requestedViewerId) {
            if (!viewerId.equals(requestedViewerId) || !active()) {
                throw new IllegalStateException("RTP editor session is stale");
            }
            baseRevision = revision();
            RtpSettings settings = runtime.rtp().settings(portal);
            List<RtpPortalEditorModel.WorldOption> worlds = new ArrayList<>();
            for (ServerLevel level : runtime.server().getAllLevels()) {
                if (!level.dimension().identifier().toString().equals(POCKET_DIMENSION)) {
                    worlds.add(RtpPortalEditorModel.WorldOption.from(world(level)));
                }
            }
            boolean targetWorldAvailable = level(settings.getTargetWorldKey()) != null;
            Optional<RtpService.Snapshot> state = runtime.rtp().snapshot(portal.getId());
            RtpPortalEditorModel.StatusSnapshot status = state.isEmpty() ? idleStatus(targetWorldAvailable)
                : RtpPortalEditorModel.StatusSnapshot.from(state.get().runtime(), new RtpPortalEditorModel.StatusContext(
                    level(state.get().settings().getTargetWorldKey()) != null, state.get().integrationAvailable(),
                    System.currentTimeMillis(), state.get().nextSearchAllowedAtMillis()));
            GeometryVector center = portal.getGeometry().getApertureCenter();
            return new RtpPortalEditorModel.EditorSnapshot(
                baseRevision,
                text(WormholesMessages.PORTAL_RTP_EDITOR_TITLE, MinecraftPortalText.arguments("portal", portal.getName())),
                RtpPortalEditorModel.SettingsSnapshot.from(settings),
                status,
                worlds,
                center.x(),
                center.z());
        }

        @Override
        public List<RtpPortalEditorModel.BiomeOption> biomeOptions(UUID requestedViewerId) {
            if (!viewerId.equals(requestedViewerId) || !active()) {
                return List.of();
            }
            ServerLevel target = level(runtime.rtp().settings(portal).getTargetWorldKey());
            if (target == null) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (Identifier id : target.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
                keys.add(id.toString());
            }
            return MinecraftRtpMenus.biomeOptions(keys);
        }

        @Override
        public void mutate(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.Mutation mutation) {
            if (viewer.hasDisconnected() || !viewerId.equals(requestedViewerId)) {
                return;
            }
            runtime.schedule(() -> mutateForViewer(expectedRevision, mutation), 1L);
        }

        @Override
        public void reset(UUID requestedViewerId, long expectedRevision) {
            if (viewer.hasDisconnected() || !viewerId.equals(requestedViewerId)) {
                return;
            }
            runtime.schedule(() -> resetForViewer(expectedRevision), 1L);
        }

        @Override
        public void manual(UUID requestedViewerId, long expectedRevision, RtpPortalEditorModel.ManualAction action) {
            if (viewer.hasDisconnected() || !viewerId.equals(requestedViewerId)) {
                return;
            }
            runtime.schedule(() -> manualForViewer(expectedRevision, action), 1L);
        }

        @Override
        public void back(UUID requestedViewerId) {
            if (viewer.hasDisconnected() || !viewerId.equals(requestedViewerId)) {
                return;
            }
            runtime.schedule(() -> {
                close();
                runtime.menus().open(viewer, portal.getId());
            }, 1L);
        }

        private void mutateForViewer(long expectedRevision, RtpPortalEditorModel.Mutation mutation) {
            if (!runtime.menus().ensureCanManage(viewer, portal)) {
                close();
                return;
            }
            if (!active()) {
                refresh(WormholesMessages.PORTAL_NOT_RTP, MessageArgs.empty());
                return;
            }
            if (baseRevision != expectedRevision || revision() != baseRevision) {
                baseRevision = revision();
                refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED, MessageArgs.empty());
                return;
            }
            RtpWorld sourceWorld = world(level(portal.getWorldKey()));
            if (sourceWorld == null) {
                refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE, MessageArgs.empty());
                return;
            }
            try {
                RtpSettings changed = RtpPortalEditorModel.applyMutation(runtime.rtp().settings(portal), mutation, sourceWorld,
                    key -> world(level(key)));
                runtime.portals().update(viewer, portal.getId(), target -> target.setRtpSettings(changed));
                baseRevision = revision();
                refresh(WormholesMessages.PORTAL_RTP_APPLIED, MessageArgs.empty());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                refresh(WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
                    MinecraftPortalText.arguments("reason", Objects.toString(exception.getMessage(), "")));
            }
        }

        private void resetForViewer(long expectedRevision) {
            if (!runtime.menus().ensureCanManage(viewer, portal)) {
                close();
                return;
            }
            if (!active()) {
                refresh(WormholesMessages.PORTAL_NOT_RTP, MessageArgs.empty());
                return;
            }
            if (baseRevision != expectedRevision || revision() != baseRevision) {
                baseRevision = revision();
                refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED, MessageArgs.empty());
                return;
            }
            RtpWorld sourceWorld = world(level(portal.getWorldKey()));
            if (sourceWorld == null) {
                refresh(WormholesMessages.PORTAL_REGION_UNAVAILABLE, MessageArgs.empty());
                return;
            }
            RtpSettings defaults = RtpSettings.defaults(sourceWorld);
            runtime.portals().update(viewer, portal.getId(), target -> target.setRtpSettings(defaults));
            baseRevision = revision();
            refresh(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS, MessageArgs.empty());
        }

        private void manualForViewer(long expectedRevision, RtpPortalEditorModel.ManualAction action) {
            if (!runtime.menus().ensureCanManage(viewer, portal)) {
                close();
                return;
            }
            if (!active() || baseRevision != expectedRevision || revision() != baseRevision) {
                refresh(WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED, MessageArgs.empty());
                return;
            }
            if (action == RtpPortalEditorModel.ManualAction.REROLL) {
                runtime.rtp().reroll(portal.getId()).whenComplete((accepted, failure) -> {
                    if (failure != null) {
                        LOGGER.error("Could not reroll the random destination of portal {}", portal.getId(), failure);
                    }
                    refresh(failure != null
                        ? WormholesMessages.PORTAL_RTP_REROLL_FAILED
                        : Boolean.TRUE.equals(accepted)
                            ? WormholesMessages.PORTAL_RTP_REROLL_PREPARING
                            : WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE, MessageArgs.empty());
                });
                return;
            }
            runtime.rtp().rebuild(portal.getId()).whenComplete((removed, failure) -> {
                if (failure != null) {
                    LOGGER.error("Could not rebuild the random destination pool of portal {}", portal.getId(), failure);
                }
                refresh(failure == null
                    ? WormholesMessages.PORTAL_RTP_POOL_REBUILDING
                    : WormholesMessages.PORTAL_RTP_POOL_FAILED, MessageArgs.empty());
            });
        }

        private void refresh(TextKey message, MessageArgs arguments) {
            runtime.schedule(() -> {
                if (viewer.hasDisconnected()) {
                    sessions.remove(viewerId, this);
                    return;
                }
                MinecraftPortalText.notifySetting(viewer, portal, message, arguments);
                if (!active()) {
                    close();
                    if (runtime.portals().get(portal.getId()) == portal) {
                        runtime.menus().open(viewer, portal.getId());
                    }
                    return;
                }
                if (window.isVisible()) {
                    editor.populate(view, viewerId);
                    window.updateInventory();
                }
            }, 1L);
        }

        private boolean active() {
            return runtime.portals().get(portal.getId()) == portal && portal.getType() == PortalType.RTP;
        }

        private long revision() {
            RtpSettings settings = runtime.rtp().settings(portal);
            if (!settings.equals(knownSettings)) {
                knownSettings = settings;
                revision++;
            }
            return revision;
        }

        private RtpPortalEditorModel.StatusSnapshot idleStatus(boolean targetWorldAvailable) {
            return new RtpPortalEditorModel.StatusSnapshot(
                targetWorldAvailable ? RtpPortalEditorModel.StatusState.IDLE : RtpPortalEditorModel.StatusState.TARGET_WORLD_UNAVAILABLE,
                targetWorldAvailable, true, false, false, 0L, 0L, 0, 0, 0, 0);
        }
    }
}
