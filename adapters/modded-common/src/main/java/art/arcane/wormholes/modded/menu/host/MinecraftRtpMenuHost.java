package art.arcane.wormholes.modded.menu.host;

import art.arcane.optics.math.Vec3d;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rtp.RtpMenuHost;
import art.arcane.wormholes.menu.rtp.RtpOutcome;
import art.arcane.wormholes.modded.MinecraftMenuText;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpBiomeMatcher;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusContext;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
import java.util.concurrent.CompletableFuture;

public final class MinecraftRtpMenuHost implements RtpMenuHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String POCKET_DIMENSION = "wormholes:pockets";

    private final WormholesModRuntime runtime;
    private final Map<UUID, Revision> revisions = new HashMap<>();

    public MinecraftRtpMenuHost(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public Optional<EditorSnapshot> snapshot(UUID portalId) {
        return rtp(portalId).map(this::snapshot);
    }

    @Override
    public List<BiomeOption> biomeOptions(UUID portalId) {
        Optional<MinecraftPortal> found = rtp(portalId);
        if (found.isEmpty()) {
            return List.of();
        }
        ServerLevel target = level(runtime.rtp().settings(found.get()).getTargetWorldKey());
        if (target == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (Identifier id : target.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
            keys.add(id.toString());
        }
        return biomeOptions(keys);
    }

    @Override
    public CompletableFuture<RtpOutcome> mutate(MenuViewer viewer, UUID portalId, long expectedRevision, Mutation mutation) {
        return CompletableFuture.completedFuture(change(viewer, portalId, expectedRevision, mutation));
    }

    @Override
    public CompletableFuture<RtpOutcome> reset(MenuViewer viewer, UUID portalId, long expectedRevision) {
        return CompletableFuture.completedFuture(change(viewer, portalId, expectedRevision, null));
    }

    @Override
    public CompletableFuture<RtpOutcome> manual(MenuViewer viewer, UUID portalId, long expectedRevision, ManualAction action) {
        Optional<MinecraftPortal> found = rtp(portalId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(RtpOutcome.notRtp());
        }
        if (revision(found.get()) != expectedRevision) {
            return CompletableFuture.completedFuture(RtpOutcome.stale());
        }
        if (action == ManualAction.REROLL) {
            return runtime.rtp().reroll(portalId).handle((accepted, failure) -> {
                if (failure != null) {
                    LOGGER.error("Could not reroll the random destination of portal {}", portalId, failure);
                    return rejected(WormholesMessages.PORTAL_RTP_REROLL_FAILED);
                }
                return Boolean.TRUE.equals(accepted)
                    ? RtpOutcome.applied(WormholesMessages.PORTAL_RTP_REROLL_PREPARING)
                    : rejected(WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE);
            });
        }
        return runtime.rtp().rebuild(portalId).handle((removed, failure) -> {
            if (failure != null) {
                LOGGER.error("Could not rebuild the random destination pool of portal {}", portalId, failure);
                return rejected(WormholesMessages.PORTAL_RTP_POOL_FAILED);
            }
            return RtpOutcome.applied(WormholesMessages.PORTAL_RTP_POOL_REBUILDING);
        });
    }

    static List<BiomeOption> biomeOptions(List<String> registeredKeys) {
        Map<String, BiomeOption> options = new LinkedHashMap<>();
        for (String registeredKey : registeredKeys) {
            String key = RtpBiomeMatcher.normalize(registeredKey);
            if (key != null && !key.startsWith("iris:")) {
                options.putIfAbsent(key, new BiomeOption(key, prettyPath(key), false));
            }
        }
        List<BiomeOption> sorted = new ArrayList<>(options.values());
        sorted.sort(Comparator.comparing(BiomeOption::displayName, String.CASE_INSENSITIVE_ORDER).thenComparing(BiomeOption::key));
        return List.copyOf(sorted);
    }

    private RtpOutcome change(MenuViewer viewer, UUID portalId, long expectedRevision, Mutation mutation) {
        Optional<MinecraftPortal> found = rtp(portalId);
        if (found.isEmpty()) {
            return RtpOutcome.notRtp();
        }
        MinecraftPortal portal = found.get();
        if (revision(portal) != expectedRevision) {
            return RtpOutcome.stale();
        }
        RtpWorld sourceWorld = world(level(portal.getWorldKey()));
        if (sourceWorld == null) {
            return rejected(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
        }
        RtpSettings changed;
        try {
            changed = mutation == null
                ? RtpSettings.defaults(sourceWorld)
                : RtpPortalEditorModel.applyMutation(runtime.rtp().settings(portal), mutation, sourceWorld, key -> world(level(key)));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
                MinecraftPortalText.arguments("reason", Objects.toString(rejected.getMessage(), "")));
        }
        if (!runtime.portals().update(MinecraftMenuViewer.player(viewer), portalId, target -> target.setRtpSettings(changed))) {
            return RtpOutcome.denied();
        }
        revision(portal);
        runtime.menus().invalidate(new MenuScope.Portal(portalId));
        return RtpOutcome.applied(mutation == null ? WormholesMessages.PORTAL_RTP_RESET_DEFAULTS : WormholesMessages.PORTAL_RTP_APPLIED);
    }

    private EditorSnapshot snapshot(MinecraftPortal portal) {
        RtpSettings settings = runtime.rtp().settings(portal);
        List<WorldOption> worlds = new ArrayList<>();
        for (ServerLevel level : runtime.server().getAllLevels()) {
            if (!level.dimension().identifier().toString().equals(POCKET_DIMENSION)) {
                worlds.add(WorldOption.from(world(level)));
            }
        }
        boolean targetWorldAvailable = level(settings.getTargetWorldKey()) != null;
        Optional<RtpService.Snapshot> state = runtime.rtp().snapshot(portal.getId());
        StatusSnapshot status = state.isEmpty() ? idleStatus(targetWorldAvailable)
            : StatusSnapshot.from(state.get().runtime(), new StatusContext(level(state.get().settings().getTargetWorldKey()) != null,
                state.get().integrationAvailable(), System.currentTimeMillis(), state.get().nextSearchAllowedAtMillis()));
        Vec3d center = portal.getGeometry().getApertureCenter();
        String title = MinecraftMenuText.text(runtime.localization().snapshot(null), WormholesMessages.PORTAL_RTP_EDITOR_TITLE,
            MinecraftPortalText.arguments("portal", portal.getName())).getString();
        return new EditorSnapshot(revision(portal), title, RtpPortalEditorModel.SettingsSnapshot.from(settings), status, worlds, center.x(), center.z());
    }

    private Optional<MinecraftPortal> rtp(UUID portalId) {
        MinecraftPortal portal = runtime.portals().get(portalId);
        if (portal != null && portal.getType() == PortalType.RTP) {
            return Optional.of(portal);
        }
        revisions.remove(portalId);
        return Optional.empty();
    }

    private long revision(MinecraftPortal portal) {
        return revisions.computeIfAbsent(portal.getId(), ignored -> new Revision()).of(runtime.rtp().settings(portal));
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
        return new RtpWorld(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), key, level.getMinY(), level.getMaxY() + 1,
            level.getSeaLevel());
    }

    private static String prettyPath(String key) {
        int separator = key.lastIndexOf(':');
        String path = separator < 0 ? key : key.substring(separator + 1);
        StringBuilder pretty = new StringBuilder(path.length());
        for (String word : path.split("_")) {
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

    private static RtpOutcome rejected(TextKey notice) {
        return RtpOutcome.rejected(notice, MessageArgs.empty());
    }

    private static StatusSnapshot idleStatus(boolean targetWorldAvailable) {
        return new StatusSnapshot(targetWorldAvailable ? StatusState.IDLE : StatusState.TARGET_WORLD_UNAVAILABLE, targetWorldAvailable, true, false,
            false, 0L, 0L, 0, 0, 0, 0);
    }

    private static final class Revision {
        private RtpSettings known;
        private long value;

        private long of(RtpSettings settings) {
            if (!settings.equals(known)) {
                known = settings;
                value++;
            }
            return value;
        }
    }
}
