package art.arcane.wormholes.clientgametest.menu;

import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.volume.LodProfile;
import art.arcane.wormholes.clientgametest.QaReplies;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuRoute;
import art.arcane.wormholes.menu.fidelity.FidelityRoutes;
import art.arcane.wormholes.menu.rtp.RtpRoutes;
import art.arcane.wormholes.menu.transit.TransitRoutes;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.ScaleRuleSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NativeMenuRtpTransitFidelityScenario {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final String DENIED = "Only the portal owner or an administrator can edit this portal.";
    private static final int NEGOTIATION_TICKS = 200;
    private static final int TIMEOUT_TICKS = 200;
    private static final int PORTAL_WIDTH = 2;
    private static final int PORTAL_HEIGHT = 3;

    private final MenuScreenDriver driver;
    private final NativeMenuShots shots;
    private final NativeMenuServer server;
    private UUID rtpId;
    private UUID portalId;

    private NativeMenuRtpTransitFidelityScenario(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        this.driver = driver;
        this.shots = shots;
        this.server = server;
    }

    public static void run(MenuScreenDriver driver, NativeMenuShots shots, NativeMenuServer server) {
        new NativeMenuRtpTransitFidelityScenario(driver, shots, server).run();
    }

    private void run() {
        driver.input().waitFor(minecraft -> ClientMenus.session().version() >= 1, NEGOTIATION_TICKS);
        rtpId = server.compute((minecraftServer, player) -> createPortal(minecraftServer, player, 3, PortalType.RTP));
        portalId = server.compute((minecraftServer, player) -> createPortal(minecraftServer, player, -4, PortalType.PORTAL));
        try {
            rtpOverview();
            rtpDestination();
            rtpLanding();
            rtpRouting();
            rtpEffects();
            rtpReset();
            transit();
            fidelity();
            revoked();
        } finally {
            server.run((minecraftServer, player) -> cleanup(minecraftServer));
        }
        LOGGER.info("WORMHOLES_NATIVE_MENU_RTP_TRANSIT_FIDELITY_PASS screenshots={}", shots.captured().size());
    }

    private void rtpOverview() {
        open(RtpRoutes.overview(rtpId), RtpRoutes.OVERVIEW);
        check(driver.read(screen -> screen.rowFor("rtp-status").isPresent()), "the RTP overview has no status row");
        check(driver.read(screen -> screen.rowFor("rtp-back").isEmpty()), "the chest-only back button reached the native overview");
        shots.capture(RtpRoutes.OVERVIEW);
    }

    private void rtpDestination() {
        driver.click("rtp-open-destination");
        awaitScreen(RtpRoutes.DESTINATION);
        shots.capture(RtpRoutes.DESTINATION);
        int radius = settings(RtpSettings::getMinimumRadius);
        act(() -> driver.increment("rtp-minimum-radius-number", false));
        awaitRtp("the minimum radius increment", settings -> settings.getMinimumRadius() == radius + 128);
        act(() -> driver.decrement("rtp-minimum-radius-number", true));
        awaitRtp("the minimum radius large decrement", settings -> settings.getMinimumRadius() == Math.max(0, radius + 128 - 1024));
        driver.click("rtp-target-biome");
        awaitScreen(RtpRoutes.BIOME);
        shots.capture(RtpRoutes.BIOME);
        act(() -> driver.select("rtp-biome-0"));
        awaitRtp("the biome pick", settings -> settings.getTargetBiomeKey() != null);
        act(() -> driver.select("rtp-biome-any"));
        awaitRtp("the any biome pick", settings -> settings.getTargetBiomeKey() == null);
        driver.back();
        awaitScreen(RtpRoutes.DESTINATION);
        driver.back();
        awaitScreen(RtpRoutes.OVERVIEW);
    }

    private void rtpLanding() {
        driver.click("rtp-open-landing");
        awaitScreen(RtpRoutes.LANDING);
        shots.capture(RtpRoutes.LANDING);
        act(() -> driver.toggle("rtp-surface-policy"));
        awaitRtp("the safe landing toggle", settings -> settings.getSafetyMode() == RtpSafetyMode.UNSAFE);
        int lower = settings(RtpSettings::getLowerY);
        act(() -> driver.increment("rtp-lower-y-number", false));
        awaitRtp("the lower Y increment", settings -> settings.getLowerY() == lower + 1);
        driver.back();
        awaitScreen(RtpRoutes.OVERVIEW);
    }

    private void rtpRouting() {
        driver.click("rtp-open-routing");
        awaitScreen(RtpRoutes.ROUTING);
        shots.capture(RtpRoutes.ROUTING);
        long lease = settings(RtpSettings::getLeaseIdleMillis);
        act(() -> driver.increment("rtp-lease-idle-number", false));
        awaitRtp("the lease increment", settings -> settings.getLeaseIdleMillis() == lease + 5_000L);
        driver.click("rtp-manual-reroll");
        driver.input().waitFor(minecraft -> NativeMenuListener.current().flatMap(screen -> screen.confirmation()).isPresent(), TIMEOUT_TICKS);
        driver.awaitConfirmationArmed();
        driver.confirm();
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().screenId().equals(RtpRoutes.ROUTING) && screen.confirmation().isEmpty()
                && !screen.feedbackLine().pending() && screen.feedbackLine().message().isPresent())
            .orElse(false), TIMEOUT_TICKS);
        check(driver.feedback().isPresent(), "the manual reroll answered nothing");
        driver.back();
        awaitScreen(RtpRoutes.OVERVIEW);
    }

    private void rtpEffects() {
        driver.click("rtp-open-effects");
        awaitScreen(RtpRoutes.EFFECTS);
        shots.capture(RtpRoutes.EFFECTS);
        act(() -> driver.select("rtp-rim-off"));
        awaitRtp("the rim off pick", settings -> !settings.isRimEnabled());
        act(() -> driver.select("rtp-sound-off"));
        awaitRtp("the sound off pick", settings -> !settings.isSoundEnabled());
        driver.back();
        awaitScreen(RtpRoutes.OVERVIEW);
    }

    private void rtpReset() {
        driver.click("rtp-reset-defaults");
        driver.input().waitFor(minecraft -> NativeMenuListener.current().flatMap(screen -> screen.confirmation()).isPresent(), TIMEOUT_TICKS);
        driver.awaitConfirmationArmed();
        driver.confirm();
        awaitRtp("the reset to defaults", settings -> settings.isRimEnabled() && settings.isSoundEnabled()
            && settings.getSafetyMode() == RtpSafetyMode.SAFE);
        awaitScreen(RtpRoutes.OVERVIEW);
        driver.escape();
        driver.awaitClosed();
    }

    private void transit() {
        open(TransitRoutes.main(portalId), TransitRoutes.MAIN);
        check(driver.read(screen -> screen.rowFor("transit-gravity").isPresent()), "the modded transit screen has no directional gravity row");
        check(driver.read(screen -> screen.rowFor("transit-profile").isEmpty()), "the chest-only profile summary reached the native screen");
        shots.capture(TransitRoutes.MAIN);
        act(() -> driver.toggle("transit-gravity"));
        awaitPortal("the directional gravity toggle", MinecraftPortal::isGravityRotationEnabled);
        MomentumPolicy.Mode momentum = server.compute((minecraftServer, player) -> effectiveMomentum(minecraftServer));
        act(() -> driver.click("transit-momentum"));
        awaitPortal("the momentum cycle", portal -> portal.getMomentum() != null && portal.getMomentum().mode() == momentum.next());
        ScaleRule.Mode scale = read(portal -> portal.getScaleRule().mode());
        act(() -> driver.click("transit-scale"));
        awaitPortal("the scale mode cycle", portal -> portal.getScaleRule().mode() == ScaleRuleSettings.next(scale));
        act(() -> driver.typeAndApply("transit-scale-min", "0.5"));
        awaitPortal("the scale minimum", portal -> portal.getScaleRule().min() == 0.5D);
        act(() -> driver.typeAndSubmit("transit-scale-max", "2"));
        awaitPortal("the scale maximum", portal -> portal.getScaleRule().max() == 2.0D);
        act(() -> driver.toggle("transit-membrane"));
        awaitPortal("the membrane toggle", MinecraftPortal::isMembrane);
        driver.escape();
        driver.awaitClosed();
    }

    private void fidelity() {
        open(FidelityRoutes.main(portalId), FidelityRoutes.MAIN);
        shots.capture(FidelityRoutes.MAIN);
        act(() -> driver.click("fidelity-atmosphere"));
        awaitPortal("the atmosphere cycle", portal -> portal.getAtmosphereMode() == FidelitySettings.atmosphereModeDefault.next());
        act(() -> driver.click("fidelity-lod"));
        awaitPortal("the detail cycle", portal -> portal.getLodProfile() == LodProfile.BALANCED.next());
        act(() -> driver.click("fidelity-block-entities"));
        awaitPortal("the block entity cycle", portal -> Boolean.valueOf(!FidelitySettings.blockEntities).equals(portal.getBlockEntities()));
        act(() -> driver.clickSide("fidelity-block-entities", MenuInteraction.TERTIARY));
        awaitPortal("the block entity default", portal -> portal.getBlockEntities() == null);
        check(read(MinecraftPortal::getAtmosphereMode) != null, "the atmosphere override did not persist");
    }

    private void revoked() {
        LodProfile lod = read(MinecraftPortal::getLodProfile);
        QaReplies.clearRecent();
        UUID stranger = UUID.randomUUID();
        server.run((minecraftServer, player) -> check(runtime(minecraftServer).portals().update(player, portalId, portal -> portal.setOwner(stranger)),
            "the owner transfer was refused"));
        boolean sent = driver.input().computeOnClient(minecraft -> ClientMenus.session().act("fidelity-lod", MenuInteraction.PRIMARY, null));
        driver.awaitClosed();
        check(read(MinecraftPortal::getLodProfile) == lod, "a player who lost manage rights changed the detail profile (sent=" + sent + ")");
        for (int tick = 0; tick < TIMEOUT_TICKS && !QaReplies.recent().contains(DENIED); tick++) {
            driver.input().waitTicks(1);
        }
        check(QaReplies.recent().contains(DENIED), "the revoked player saw no denial notice: " + QaReplies.recent());
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

    private void awaitScreen(String screenId) {
        driver.input().waitFor(minecraft -> NativeMenuListener.current()
            .map(screen -> screen.snapshot().screenId().equals(screenId))
            .orElse(false), TIMEOUT_TICKS);
        driver.input().waitTicks(1);
    }

    private void awaitRtp(String description, Predicate<RtpSettings> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute((minecraftServer, player) -> condition.test(rtpSettings(minecraftServer)))) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server RTP settings");
    }

    private void awaitPortal(String description, Predicate<MinecraftPortal> condition) {
        for (int tick = 0; tick < TIMEOUT_TICKS; tick++) {
            if (server.compute((minecraftServer, player) -> condition.test(portal(minecraftServer)))) {
                return;
            }
            driver.input().waitTicks(1);
        }
        throw new AssertionError(description + " never reached the server portal");
    }

    private <T> T settings(Function<RtpSettings, T> reader) {
        return server.compute((minecraftServer, player) -> reader.apply(rtpSettings(minecraftServer)));
    }

    private <T> T read(Function<MinecraftPortal, T> reader) {
        return server.compute((minecraftServer, player) -> reader.apply(portal(minecraftServer)));
    }

    private long revision() {
        return driver.read(screen -> screen.snapshot().revision());
    }

    private RtpSettings rtpSettings(MinecraftServer minecraftServer) {
        WormholesModRuntime runtime = runtime(minecraftServer);
        return runtime.rtp().settings(Objects.requireNonNull(runtime.portals().get(rtpId), "the RTP test portal is gone"));
    }

    private MomentumPolicy.Mode effectiveMomentum(MinecraftServer minecraftServer) {
        MomentumPolicy explicit = portal(minecraftServer).getMomentum();
        return explicit != null ? explicit.mode()
            : MomentumPolicy.Mode.parse(runtime(minecraftServer).configuration().settings().getTransit().momentumDefault, MomentumPolicy.Mode.PRESERVE);
    }

    private MinecraftPortal portal(MinecraftServer minecraftServer) {
        return Objects.requireNonNull(runtime(minecraftServer).portals().get(portalId), "the transit test portal is gone");
    }

    private void cleanup(MinecraftServer minecraftServer) {
        runtime(minecraftServer).portals().remove(rtpId);
        runtime(minecraftServer).portals().remove(portalId);
    }

    private static UUID createPortal(MinecraftServer minecraftServer, ServerPlayer player, int offset, PortalType type) {
        ServerLevel level = minecraftServer.overworld();
        BlockPos base = player.blockPosition().offset(offset, 0, 4);
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
        MinecraftPortal portal = runtime(minecraftServer).portals().create(player.getUUID(), level, cells, type, new Vec3(0, 0, -1));
        check(portal != null, "the " + type + " test portal was refused");
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
