package art.arcane.wormholes.menu.rtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.FakePortalAccess;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpCenterMode;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.BiomeOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ManualAction;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.Mutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;

final class FakeRtpMenuHost implements RtpMenuHost {
    static final WorldOption OVERWORLD = new WorldOption("minecraft:overworld", "world", -63, 317);
    static final WorldOption NETHER = new WorldOption("minecraft:the_nether", "world_nether", 1, 125);
    static final WorldOption END = new WorldOption("minecraft:the_end", "world_the_end", 1, 253);

    final List<String> calls = new ArrayList<>();
    final List<Mutation> mutations = new ArrayList<>();
    long revision = 7L;
    boolean rtp = true;
    boolean driftBeforeApply;
    SettingsSnapshot settings = settings().build();
    StatusSnapshot status = new StatusSnapshot(StatusState.READY, true, true, true, true, 0L, 0L, 0, 0, 0, 0);
    List<WorldOption> worlds = List.of(OVERWORLD, NETHER, END);
    List<BiomeOption> biomes = List.of(new BiomeOption("minecraft:plains", "Plains", false),
        new BiomeOption("iris:crystal_caves", "Crystal Caves", true));
    RtpOutcome nextOutcome;
    CompletableFuture<RtpOutcome> deferred;

    static Settings settings() {
        return new Settings();
    }

    @Override
    public Optional<EditorSnapshot> snapshot(UUID portalId) {
        if (!rtp || !portalId.equals(FakePortalAccess.PORTAL)) {
            return Optional.empty();
        }
        return Optional.of(new EditorSnapshot(revision, "RTP: Alpha", settings, status, worlds, 12.5D, -40.5D));
    }

    @Override
    public List<BiomeOption> biomeOptions(UUID portalId) {
        return biomes;
    }

    @Override
    public CompletableFuture<RtpOutcome> mutate(MenuViewer viewer, UUID portalId, long expectedRevision, Mutation mutation) {
        calls.add("mutate");
        mutations.add(mutation);
        if (deferred != null) {
            return deferred;
        }
        return CompletableFuture.completedFuture(apply(expectedRevision, RtpOutcome.applied(WormholesMessages.PORTAL_RTP_APPLIED)));
    }

    @Override
    public CompletableFuture<RtpOutcome> reset(MenuViewer viewer, UUID portalId, long expectedRevision) {
        calls.add("reset");
        return CompletableFuture.completedFuture(apply(expectedRevision, RtpOutcome.applied(WormholesMessages.PORTAL_RTP_RESET_DEFAULTS)));
    }

    @Override
    public CompletableFuture<RtpOutcome> manual(MenuViewer viewer, UUID portalId, long expectedRevision, ManualAction action) {
        calls.add("manual:" + action);
        RtpOutcome done = RtpOutcome.applied(action == ManualAction.REROLL
            ? WormholesMessages.PORTAL_RTP_REROLL_PREPARING
            : WormholesMessages.PORTAL_RTP_POOL_REBUILDING);
        return CompletableFuture.completedFuture(apply(expectedRevision, done));
    }

    private RtpOutcome apply(long expectedRevision, RtpOutcome done) {
        if (driftBeforeApply) {
            revision++;
        }
        if (!rtp) {
            return RtpOutcome.notRtp();
        }
        if (expectedRevision != revision) {
            return RtpOutcome.stale();
        }
        if (nextOutcome != null) {
            RtpOutcome outcome = nextOutcome;
            nextOutcome = null;
            return outcome;
        }
        revision++;
        return done;
    }

    static final class Settings {
        private RtpCenterMode centerMode = RtpCenterMode.PORTAL_RELATIVE;
        private Double customX;
        private Double customZ;
        private RtpVerticalMode verticalMode = RtpVerticalMode.SURFACE;
        private RtpSafetyMode safetyMode = RtpSafetyMode.SAFE;
        private RtpAllocationMode allocationMode = RtpAllocationMode.SHARED;
        private RtpRotationMode rotationMode = RtpRotationMode.STATIC;
        private String targetWorld = OVERWORLD.key();
        private String biome;
        private boolean rim = true;
        private boolean sound = true;

        Settings custom(double x, double z) {
            centerMode = RtpCenterMode.CUSTOM;
            customX = Double.valueOf(x);
            customZ = Double.valueOf(z);
            return this;
        }

        Settings vertical(RtpVerticalMode mode) {
            verticalMode = mode;
            return this;
        }

        Settings safety(RtpSafetyMode mode) {
            safetyMode = mode;
            return this;
        }

        Settings allocation(RtpAllocationMode mode) {
            allocationMode = mode;
            return this;
        }

        Settings rotation(RtpRotationMode mode) {
            rotationMode = mode;
            return this;
        }

        Settings target(String worldKey) {
            targetWorld = worldKey;
            return this;
        }

        Settings biome(String key) {
            biome = key;
            return this;
        }

        Settings effects(boolean rimEnabled, boolean soundEnabled) {
            rim = rimEnabled;
            sound = soundEnabled;
            return this;
        }

        SettingsSnapshot build() {
            return new SettingsSnapshot(OVERWORLD.key(), targetWorld, centerMode, customX, customZ, 512, 4096, verticalMode, safetyMode,
                60, 120, 64, allocationMode, rotationMode, 300_000L, 30_000L, 15_000L, rim, sound, biome);
        }
    }
}
