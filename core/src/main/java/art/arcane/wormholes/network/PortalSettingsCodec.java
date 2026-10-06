package art.arcane.wormholes.network;

import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.RemotePortal;

import java.util.Map;
import java.util.LinkedHashMap;

public final class PortalSettingsCodec {
    public static final String KEY_PROJECTION_MODE = "projectionMode";
    public static final String KEY_PROJECTION_ENABLED = "projectionEnabled";
    public static final String KEY_MIRROR_MODE = "mirrorMode";
    public static final String KEY_MIRROR_ROTATION = "mirrorRotationDegrees";
    public static final String KEY_PERMISSION_MODE = "permissionMode";
    public static final String KEY_OUTGOING_TRAVERSALS = "outgoingTraversalsEnabled";
    public static final String KEY_INCOMING_TRAVERSALS = "incomingTraversalsEnabled";
    public static final String KEY_VIEW_DEPTH = "networkViewDepth";
    public static final String KEY_VIEW_LATERAL_PAD = "networkViewLateralPad";
    public static final String KEY_VIEW_HEARTBEAT = "networkViewHeartbeatTicks";
    public static final String KEY_VIEW_ENTITY_INTERVAL = "networkViewEntityIntervalTicks";
    public static final String KEY_VIEW_UNSUBSCRIBE_GRACE = "networkViewUnsubscribeGraceSeconds";
    public static final String KEY_VIEW_FALLBACK_BLOCK = "networkViewFallbackBlock";
    public static final String KEY_BLACKOUT_BACKGROUND = "blackoutBackground";
    public static final String KEY_BLACKOUT_COLOR = "blackoutColor";
    public static final String KEY_ACTIVATION_RANGE = "activationRange";
    public static final String KEY_RENDER_MODE = "renderMode";
    public static final String KEY_AMBIENT_STYLE = "ambientStyle";
    public static final String KEY_AMBIENT_COLOR = "ambientColor";
    public static final String KEY_SURFACE_SKIN = "surfaceSkin";
    public static final String KEY_SETTINGS_SYNC = "settingsSyncEnabled";
    public static final String KEY_REMOTE_CACHE_ONLY = "remoteCacheOnly";

    private PortalSettingsCodec() {
    }

    public static void applyToRemote(RemotePortal remote, Map<String, String> settings) {
        applyProjectionState(remote, settings);
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || isProjectionStateKey(key)) {
                continue;
            }
            switch (key) {
                case KEY_MIRROR_ROTATION -> remote.setMirroredProjectionRotation(
                    QuarterTurn.fromDegrees(parseIntOr(value, remote.getMirroredProjectionRotation().getDegrees())));
                case KEY_PERMISSION_MODE -> {
                    PortalPermissionMode mode = parsePermissionMode(value);
                    if (mode != null) {
                        remote.setMirroredPermissionMode(mode);
                    }
                }
                case KEY_OUTGOING_TRAVERSALS -> remote.setMirroredOutgoingTraversalsEnabled(Boolean.parseBoolean(value));
                case KEY_INCOMING_TRAVERSALS -> remote.setMirroredIncomingTraversalsEnabled(Boolean.parseBoolean(value));
                case KEY_VIEW_DEPTH -> remote.setMirroredNetworkViewDepth(parseIntOr(value, remote.getMirroredNetworkViewDepth()));
                case KEY_VIEW_LATERAL_PAD -> remote.setMirroredNetworkViewLateralPad(parseIntOr(value, remote.getMirroredNetworkViewLateralPad()));
                case KEY_VIEW_HEARTBEAT -> remote.setMirroredNetworkViewHeartbeatTicks(parseIntOr(value, remote.getMirroredNetworkViewHeartbeatTicks()));
                case KEY_VIEW_ENTITY_INTERVAL -> remote.setMirroredNetworkViewEntityIntervalTicks(parseIntOr(value, remote.getMirroredNetworkViewEntityIntervalTicks()));
                case KEY_VIEW_UNSUBSCRIBE_GRACE -> remote.setMirroredNetworkViewUnsubscribeGraceSeconds(parseIntOr(value, remote.getMirroredNetworkViewUnsubscribeGraceSeconds()));
                case KEY_VIEW_FALLBACK_BLOCK -> remote.setMirroredNetworkViewFallbackBlock(value);
                case KEY_BLACKOUT_BACKGROUND -> remote.setMirroredBlackoutBackground(Boolean.parseBoolean(value));
                case KEY_BLACKOUT_COLOR -> remote.setMirroredBlackoutColor(BlackoutColor.fromName(value, remote.getMirroredBlackoutColor()));
                case KEY_ACTIVATION_RANGE -> remote.setMirroredActivationRange(parseIntOr(value, remote.getMirroredActivationRange()));
                case KEY_RENDER_MODE -> remote.setMirroredRenderMode(ProjectionRenderMode.fromName(value, remote.getMirroredRenderMode()));
                case KEY_AMBIENT_STYLE -> remote.setMirroredAmbientStyle(AmbientParticleStyle.fromName(value, remote.getMirroredAmbientStyle()));
                case KEY_AMBIENT_COLOR -> remote.setMirroredAmbientColor(parseIntOr(value, remote.getMirroredAmbientColor()));
                case KEY_SURFACE_SKIN -> remote.setMirroredSurfaceSkin(value);
                default -> remote.putMirroredExtensionSetting(key, value);
            }
        }
    }

    private static void applyProjectionState(RemotePortal remote, Map<String, String> settings) {
        ProjectionState state = projectionState(settings);
        if (state.projection() != null) {
            remote.setMirroredProjectionMode(state.projection());
        }
        if (state.mirror() != null) {
            remote.setMirroredMirrorMode(state.mirror().booleanValue());
        }
    }

    public static boolean isProjectionStateKey(String key) {
        return KEY_PROJECTION_MODE.equals(key)
            || KEY_PROJECTION_ENABLED.equals(key)
            || KEY_MIRROR_MODE.equals(key);
    }

    public static ProjectionMode parseProjectionMode(String value) {
        try {
            return ProjectionMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Boolean parseBoolean(String value) {
        if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if (Boolean.FALSE.toString().equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static PortalPermissionMode parsePermissionMode(String value) {
        try {
            return PortalPermissionMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
    public static Map<String, String> collectSettings(PortalSettingsTarget portal) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(KEY_PROJECTION_MODE, portal.isMirrorMode() ? "MIRROR" : portal.getProjectionMode().name());
        settings.put(KEY_PROJECTION_ENABLED, Boolean.toString(portal.getProjectionMode() == ProjectionMode.ON));
        settings.put(KEY_MIRROR_MODE, Boolean.toString(portal.isMirrorMode()));
        settings.put(KEY_MIRROR_ROTATION, Integer.toString(portal.getMirrorRotation().getDegrees()));
        settings.put(KEY_PERMISSION_MODE, portal.getPermissionMode().name());
        settings.put(KEY_OUTGOING_TRAVERSALS, Boolean.toString(portal.isOutgoingTraversalsEnabled()));
        settings.put(KEY_INCOMING_TRAVERSALS, Boolean.toString(portal.isIncomingTraversalsEnabled()));
        settings.put(KEY_VIEW_DEPTH, Integer.toString(portal.getNetworkViewDepth()));
        settings.put(KEY_VIEW_LATERAL_PAD, Integer.toString(portal.getNetworkViewLateralPad()));
        settings.put(KEY_VIEW_HEARTBEAT, Integer.toString(portal.getNetworkViewHeartbeatTicks()));
        settings.put(KEY_VIEW_ENTITY_INTERVAL, Integer.toString(portal.getNetworkViewEntityIntervalTicks()));
        settings.put(KEY_VIEW_UNSUBSCRIBE_GRACE, Integer.toString(portal.getNetworkViewUnsubscribeGraceSeconds()));
        settings.put(KEY_VIEW_FALLBACK_BLOCK, portal.getNetworkViewFallbackBlock());
        settings.put(KEY_BLACKOUT_BACKGROUND, Boolean.toString(portal.isBlackoutBackground()));
        settings.put(KEY_BLACKOUT_COLOR, portal.getBlackoutColor().name());
        settings.put(KEY_ACTIVATION_RANGE, Integer.toString(portal.getActivationRange()));
        settings.put(KEY_RENDER_MODE, portal.getRenderMode().name());
        settings.put(KEY_AMBIENT_STYLE, portal.getAmbientStyle().name());
        settings.put(KEY_AMBIENT_COLOR, Integer.toString(portal.getAmbientColor()));
        settings.put(KEY_SURFACE_SKIN, portal.getSurfaceSkin());
        settings.put(KEY_SETTINGS_SYNC, Boolean.toString(portal.isSettingsSyncEnabled()));
        return settings;
    }

    public static void applyToLocal(PortalSettingsTarget portal, Map<String, String> settings) {
        PortalSyncService.applyRemote(() -> {
            applyProjectionState(portal, settings);
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (isProjectionStateKey(entry.getKey())) {
                    continue;
                }
                applyLocalKey(portal, entry.getKey(), entry.getValue());
            }
        });
    }

    private static void applyLocalKey(PortalSettingsTarget portal, String key, String value) {
        if (value == null) {
            return;
        }
        switch (key) {
            case KEY_MIRROR_ROTATION -> portal.setMirrorRotation(
                QuarterTurn.fromDegrees(parseIntOr(value, portal.getMirrorRotation().getDegrees())));
            case KEY_PERMISSION_MODE -> {
                PortalPermissionMode mode = parsePermissionMode(value);
                if (mode != null) {
                    portal.setPermissionMode(mode);
                }
            }
            case KEY_OUTGOING_TRAVERSALS -> portal.setOutgoingTraversalsEnabled(Boolean.parseBoolean(value));
            case KEY_INCOMING_TRAVERSALS -> portal.setIncomingTraversalsEnabled(Boolean.parseBoolean(value));
            case KEY_VIEW_DEPTH -> portal.setNetworkViewDepth(parseIntOr(value, portal.getNetworkViewDepth()));
            case KEY_VIEW_LATERAL_PAD -> portal.setNetworkViewLateralPad(parseIntOr(value, portal.getNetworkViewLateralPad()));
            case KEY_VIEW_HEARTBEAT -> portal.setNetworkViewHeartbeatTicks(parseIntOr(value, portal.getNetworkViewHeartbeatTicks()));
            case KEY_VIEW_ENTITY_INTERVAL -> portal.setNetworkViewEntityIntervalTicks(parseIntOr(value, portal.getNetworkViewEntityIntervalTicks()));
            case KEY_VIEW_UNSUBSCRIBE_GRACE -> portal.setNetworkViewUnsubscribeGraceSeconds(parseIntOr(value, portal.getNetworkViewUnsubscribeGraceSeconds()));
            case KEY_VIEW_FALLBACK_BLOCK -> portal.setNetworkViewFallbackBlock(value);
            case KEY_BLACKOUT_BACKGROUND -> portal.setBlackoutBackground(Boolean.parseBoolean(value));
            case KEY_BLACKOUT_COLOR -> portal.setBlackoutColor(BlackoutColor.fromName(value, portal.getBlackoutColor()));
            case KEY_ACTIVATION_RANGE -> portal.setActivationRange(parseIntOr(value, portal.getActivationRange()));
            case KEY_RENDER_MODE -> portal.setRenderMode(ProjectionRenderMode.fromName(value, portal.getRenderMode()));
            case KEY_AMBIENT_STYLE -> portal.setAmbientStyle(AmbientParticleStyle.fromName(value, portal.getAmbientStyle()));
            case KEY_AMBIENT_COLOR -> portal.setAmbientColor(parseIntOr(value, portal.getAmbientColor()));
            case KEY_SURFACE_SKIN -> portal.setSurfaceSkin(value);
            case KEY_SETTINGS_SYNC -> portal.setSettingsSyncEnabled(Boolean.parseBoolean(value));
            default -> {
            }
        }
    }

    private static void applyProjectionState(PortalSettingsTarget portal, Map<String, String> settings) {
        ProjectionState state = projectionState(settings);
        if (state.projection() != null) {
            portal.setProjectionMode(state.projection());
        }
        if (state.mirror() != null) {
            portal.setMirrorMode(state.mirror().booleanValue());
        }
    }

    private static ProjectionState projectionState(Map<String, String> settings) {
        String legacyValue = settings.get(KEY_PROJECTION_MODE);
        ProjectionMode projection = null;
        Boolean mirror = null;
        if ("MIRROR".equals(legacyValue)) {
            projection = ProjectionMode.ON;
            mirror = Boolean.TRUE;
        } else if (legacyValue != null) {
            projection = parseProjectionMode(legacyValue);
            if (projection != null && !settings.containsKey(KEY_MIRROR_MODE)) {
                mirror = Boolean.FALSE;
            }
        }
        Boolean projectionEnabled = parseBoolean(settings.get(KEY_PROJECTION_ENABLED));
        if (projectionEnabled != null) {
            projection = projectionEnabled.booleanValue() ? ProjectionMode.ON : ProjectionMode.OFF;
        }
        Boolean explicitMirror = parseBoolean(settings.get(KEY_MIRROR_MODE));
        if (explicitMirror != null) {
            mirror = explicitMirror;
        }
        return new ProjectionState(projection, mirror);
    }

    private record ProjectionState(ProjectionMode projection, Boolean mirror) {
    }
}
