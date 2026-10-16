package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CenterModeMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CustomCenterMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.CycleDurationMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.EditorSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.LeaseIdleMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.RadiiMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.ReservationTimeoutMutation;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.SettingsSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusSnapshot;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.StatusState;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.WorldOption;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YField;
import art.arcane.wormholes.portal.rtp.RtpPortalEditorModel.YMutation;

final class RtpPortalEditorModelStepsTest {
    private static final WorldOption OVERWORLD = new WorldOption("minecraft:overworld", "world", -63, 317);

    @Test
    void radiusStepsKeepTheAnnulusOpen() {
        SettingsSnapshot settings = settings(RtpCenterMode.PORTAL_RELATIVE, null, null, 512, 640, 60, 70, 80);

        assertEquals(new RadiiMutation(640, 641), RtpPortalEditorModel.minimumRadius(settings, 640));
        assertEquals(new RadiiMutation(0, 640), RtpPortalEditorModel.minimumRadius(settings, -400));
        assertEquals(new RadiiMutation(RtpPortalEditorModel.MAXIMUM_RADIUS - 1, RtpPortalEditorModel.MAXIMUM_RADIUS),
            RtpPortalEditorModel.minimumRadius(settings, Integer.MAX_VALUE));
        assertEquals(new RadiiMutation(511, 512), RtpPortalEditorModel.maximumRadius(settings, 512));
        assertEquals(new RadiiMutation(0, 1), RtpPortalEditorModel.maximumRadius(settings, -5));
        assertEquals(new RadiiMutation(512, 1664), RtpPortalEditorModel.maximumRadius(settings, 640 + RtpPortalEditorModel.RADIUS_BIG_STEP));
    }

    @Test
    void customCenterStepsClampToTheWorldBorderRange() {
        SettingsSnapshot settings = settings(RtpCenterMode.CUSTOM, 100.0D, -50.0D, 512, 4096, 60, 70, 80);

        assertEquals(new CustomCenterMutation(116.0D, -50.0D), RtpPortalEditorModel.customCenter(settings, true, 116.0D));
        assertEquals(new CustomCenterMutation(100.0D, RtpPortalEditorModel.MINIMUM_COORDINATE),
            RtpPortalEditorModel.customCenter(settings, false, -1.0E9D));
    }

    @Test
    void yStepsKeepThePreferredHeightInsideTheBounds() {
        SettingsSnapshot settings = settings(RtpCenterMode.PORTAL_RELATIVE, null, null, 512, 4096, 60, 70, 80);

        assertEquals(new YMutation(75, 80, 75), RtpPortalEditorModel.yBound(settings, OVERWORLD, YField.LOWER, 75));
        assertEquals(new YMutation(80, 80, 80), RtpPortalEditorModel.yBound(settings, OVERWORLD, YField.LOWER, 200));
        assertEquals(new YMutation(60, 65, 65), RtpPortalEditorModel.yBound(settings, OVERWORLD, YField.UPPER, 65));
        assertEquals(new YMutation(60, 317, 70), RtpPortalEditorModel.yBound(settings, OVERWORLD, YField.UPPER, 999));
        assertEquals(new YMutation(60, 80, 60), RtpPortalEditorModel.yBound(settings, OVERWORLD, YField.PREFERRED, 10));
    }

    @Test
    void durationStepsClampToTheEditorBounds() {
        assertEquals(new CycleDurationMutation(15_000L), RtpPortalEditorModel.cycleDuration(1_000L));
        assertEquals(new CycleDurationMutation(86_400_000L), RtpPortalEditorModel.cycleDuration(Long.MAX_VALUE));
        assertEquals(new LeaseIdleMutation(5_000L), RtpPortalEditorModel.leaseIdle(0L));
        assertEquals(new LeaseIdleMutation(600_000L), RtpPortalEditorModel.leaseIdle(700_000L));
        assertEquals(new ReservationTimeoutMutation(35_000L), RtpPortalEditorModel.reservationTimeout(35_000L));
        assertEquals(new ReservationTimeoutMutation(300_000L), RtpPortalEditorModel.reservationTimeout(301_000L));
    }

    @Test
    void customCenterModeStartsAtThePortalWhenNoCenterIsStored() {
        EditorSnapshot fresh = snapshot(settings(RtpCenterMode.PORTAL_RELATIVE, null, null, 512, 4096, 60, 70, 80));
        EditorSnapshot stored = snapshot(settings(RtpCenterMode.PORTAL_RELATIVE, 5.0D, 6.0D, 512, 4096, 60, 70, 80));

        assertEquals(new CenterModeMutation(RtpCenterMode.CUSTOM, 12.5D, -40.5D), RtpPortalEditorModel.centerMode(fresh, RtpCenterMode.CUSTOM));
        assertEquals(new CenterModeMutation(RtpCenterMode.CUSTOM, 5.0D, 6.0D), RtpPortalEditorModel.centerMode(stored, RtpCenterMode.CUSTOM));
        assertEquals(new CenterModeMutation(RtpCenterMode.PORTAL_RELATIVE, null, null),
            RtpPortalEditorModel.centerMode(fresh, RtpCenterMode.PORTAL_RELATIVE));
    }

    private static EditorSnapshot snapshot(SettingsSnapshot settings) {
        return new EditorSnapshot(3L, "RTP: Alpha", settings, new StatusSnapshot(StatusState.IDLE, true, true, false, false, 0L, 0L, 0, 0, 0, 0),
            List.of(OVERWORLD), 12.5D, -40.5D);
    }

    private static SettingsSnapshot settings(RtpCenterMode centerMode, Double customX, Double customZ, int minimumRadius, int maximumRadius,
                                             int lowerY, int preferredY, int upperY) {
        return new SettingsSnapshot("minecraft:overworld", "minecraft:overworld", centerMode, customX, customZ, minimumRadius, maximumRadius,
            RtpVerticalMode.SURFACE, RtpSafetyMode.SAFE, lowerY, upperY, preferredY, RtpAllocationMode.SHARED, RtpRotationMode.STATIC,
            300_000L, 30_000L, 15_000L, true, true, null);
    }
}
