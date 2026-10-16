package art.arcane.wormholes.menu.host;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rtp.RtpMenuHost;
import art.arcane.wormholes.menu.rtp.RtpOutcome;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.BukkitRtpRuntime;
import art.arcane.wormholes.portal.rtp.RtpBiomeDirectory;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;

public final class BukkitRtpMenuHost implements RtpMenuHost {
    private final PortalManager portals;
    private final Logger logger;

    public BukkitRtpMenuHost(PortalManager portals, Logger logger) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Optional<EditorSnapshot> snapshot(UUID portalId) {
        return rtp(portalId).map(this::snapshot);
    }

    @Override
    public List<BiomeOption> biomeOptions(UUID portalId) {
        return rtp(portalId)
            .map(portal -> RtpBiomeDirectory.optionsFor(portal.resolveRtpWorld(portal.getRtpSettings().getTargetWorldKey())))
            .orElse(List.of());
    }

    @Override
    public CompletableFuture<RtpOutcome> mutate(MenuViewer viewer, UUID portalId, long expectedRevision, Mutation mutation) {
        return onSourceRegion(portalId, expectedRevision, portal -> apply(portal, mutation));
    }

    @Override
    public CompletableFuture<RtpOutcome> reset(MenuViewer viewer, UUID portalId, long expectedRevision) {
        return onSourceRegion(portalId, expectedRevision, this::reset);
    }

    @Override
    public CompletableFuture<RtpOutcome> manual(MenuViewer viewer, UUID portalId, long expectedRevision, ManualAction action) {
        Optional<LocalPortal> found = rtp(portalId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(RtpOutcome.notRtp());
        }
        LocalPortal portal = found.get();
        if (portal.getRtpRevision() != expectedRevision) {
            return CompletableFuture.completedFuture(RtpOutcome.stale());
        }
        BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
        if (runtime == null) {
            return CompletableFuture.completedFuture(RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_RUNTIME_UNAVAILABLE, MessageArgs.empty()));
        }
        if (action == ManualAction.REROLL) {
            return runtime.requestManualReroll(portalId).handle((accepted, failure) -> {
                if (failure != null) {
                    logger.log(Level.WARNING, "Could not reroll the random destination of portal " + portalId, failure);
                    return rejected(WormholesMessages.PORTAL_RTP_REROLL_FAILED);
                }
                return Boolean.TRUE.equals(accepted)
                    ? RtpOutcome.applied(WormholesMessages.PORTAL_RTP_REROLL_PREPARING)
                    : rejected(WormholesMessages.PORTAL_RTP_REROLL_UNAVAILABLE);
            });
        }
        return runtime.requestPoolRebuild(portalId).handle((removed, failure) -> {
            if (failure != null) {
                logger.log(Level.WARNING, "Could not rebuild the random destination pool of portal " + portalId, failure);
                return rejected(WormholesMessages.PORTAL_RTP_POOL_FAILED);
            }
            return RtpOutcome.applied(WormholesMessages.PORTAL_RTP_POOL_REBUILDING);
        });
    }

    private CompletableFuture<RtpOutcome> onSourceRegion(UUID portalId, long expectedRevision, SourceChange change) {
        Optional<LocalPortal> found = rtp(portalId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(RtpOutcome.notRtp());
        }
        LocalPortal portal = found.get();
        CompletableFuture<RtpOutcome> outcome = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                if (!rtpMode(portal)) {
                    outcome.complete(RtpOutcome.notRtp());
                } else if (portal.getRtpRevision() != expectedRevision) {
                    outcome.complete(RtpOutcome.stale());
                } else {
                    outcome.complete(change.apply(portal));
                }
            } catch (RuntimeException failure) {
                outcome.completeExceptionally(failure);
            }
        };
        if (!portal.runRtpSourceTask(task)) {
            outcome.complete(rejected(WormholesMessages.PORTAL_REGION_UNAVAILABLE));
        }
        return outcome;
    }

    private RtpOutcome apply(LocalPortal portal, Mutation mutation) {
        try {
            World sourceWorld = Objects.requireNonNull(portal.getStructure().getWorld(), "portal source world");
            portal.applyRtpSettings(RtpPortalEditorModel.applyMutation(portal.getRtpSettings(), mutation, BukkitRtpRuntime.worldSettings(sourceWorld),
                key -> {
                    World target = portal.resolveRtpWorld(key);
                    return target == null ? null : BukkitRtpRuntime.worldSettings(target);
                }));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            return RtpOutcome.rejected(WormholesMessages.PORTAL_RTP_SETTING_REJECTED,
                MessageArgs.builder().untrusted("reason", Objects.toString(rejected.getMessage(), "")).build());
        }
        portal.invalidateMenus();
        Wormholes.v("QA_EVT {\"event\":\"rtp_editor_apply\",\"status\":\"pass\",\"details\":\"" + mutation.getClass().getSimpleName()
            + "\",\"context\":{\"portal\":\"" + portal.getId() + "\",\"revision\":" + portal.getRtpRevision() + "}}");
        return RtpOutcome.applied(WormholesMessages.PORTAL_RTP_APPLIED);
    }

    private RtpOutcome reset(LocalPortal portal) {
        RtpSettings defaults = portal.defaultRtpSettings();
        if (defaults == null) {
            return rejected(WormholesMessages.PORTAL_REGION_UNAVAILABLE);
        }
        portal.applyRtpSettings(defaults);
        portal.invalidateMenus();
        Wormholes.v("QA_EVT {\"event\":\"rtp_editor_apply\",\"status\":\"pass\",\"details\":\"reset_defaults\",\"context\":{\"portal\":\""
            + portal.getId() + "\",\"revision\":" + portal.getRtpRevision() + "}}");
        return RtpOutcome.applied(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS);
    }

    private EditorSnapshot snapshot(LocalPortal portal) {
        RtpSettings settings = portal.getRtpSettings();
        List<WorldOption> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (!PocketWorldService.isPocketWorld(world)) {
                worlds.add(new WorldOption(world.getKey().toString(), world.getName(), world.getMinHeight() + 1, world.getMaxHeight() - 2));
            }
        }
        boolean targetWorldAvailable = portal.resolveRtpWorld(settings.getTargetWorldKey()) != null;
        BukkitRtpRuntime runtime = Wormholes.rtpRuntime;
        StatusSnapshot status = runtime == null
            ? idleStatus(targetWorldAvailable)
            : runtime.editorStatus(portal.getId()).orElseGet(() -> idleStatus(targetWorldAvailable));
        Location center = Objects.requireNonNull(portal.getStructure().getCenter(), "portal center");
        return new EditorSnapshot(portal.getRtpRevision(),
            Wormholes.text().plain(WormholesMessages.PORTAL_RTP_EDITOR_TITLE, MessageArgs.builder().untrusted("portal", portal.getName()).build()),
            RtpPortalEditorModel.SettingsSnapshot.from(settings), status, worlds, center.getX(), center.getZ());
    }

    private Optional<LocalPortal> rtp(UUID portalId) {
        return BukkitLocalPortals.local(portals, portalId).filter(BukkitRtpMenuHost::rtpMode);
    }

    private static boolean rtpMode(LocalPortal portal) {
        return portal.getType() == PortalType.RTP && portal.getRtpSettings() != null;
    }

    private static RtpOutcome rejected(TextKey notice) {
        return RtpOutcome.rejected(notice, MessageArgs.empty());
    }

    private static StatusSnapshot idleStatus(boolean targetWorldAvailable) {
        return new StatusSnapshot(targetWorldAvailable ? StatusState.IDLE : StatusState.TARGET_WORLD_UNAVAILABLE, targetWorldAvailable, true, false,
            false, 0L, 0L, 0, 0, 0, 0);
    }

    @FunctionalInterface
    private interface SourceChange {
        RtpOutcome apply(LocalPortal portal);
    }
}
