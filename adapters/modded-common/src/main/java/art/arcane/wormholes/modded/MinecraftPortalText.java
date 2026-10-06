package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalTravelMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.optics.math.Face;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class MinecraftPortalText {
    private static final String BLACK = "§0";
    private static final String GRAY = "§7";
    private static final String YELLOW = "§e";
    private static final String GOLD = "§6";
    private static final String BOLD = "§l";
    private static final double NOTIFICATION_RADIUS_SQUARED = 576.0D;
    private static final String REMOTE_TUNNEL = "UNIVERSAL";

    private MinecraftPortalText() {
    }

    public static String router(WormholesModRuntime runtime, MinecraftPortal portal, boolean dark) {
        return router(runtime, portal, dark, null);
    }

    public static String router(WormholesModRuntime runtime, MinecraftPortal portal, boolean dark, MinecraftPortal source) {
        StringBuilder router = new StringBuilder();
        if (source != null) {
            router.append(dark ? GRAY : YELLOW).append(BOLD).append(source.getName());
            router.append(GRAY).append(" -> ");
        }
        router.append(dark ? BLACK : GOLD).append(BOLD).append(portal.getName());
        String destination = linkedDestinationName(runtime, portal);
        if (destination != null) {
            router.append(GRAY).append(" -> ");
            router.append(GRAY).append(BOLD).append(destination);
        }
        return router.toString();
    }

    public static String linkedDestinationName(WormholesModRuntime runtime, MinecraftPortal portal) {
        UUID destinationId = portal.getDestinationId();
        if (destinationId == null) {
            return null;
        }
        if (REMOTE_TUNNEL.equals(portal.getTunnelType())) {
            String server = portal.getDestinationServer();
            RemotePortal remote = server == null ? null : runtime.network().remotePortals().get(server, destinationId);
            return remote == null ? null : remote.getName();
        }
        MinecraftPortal destination = runtime.portals().get(destinationId);
        return destination == null ? null : destination.getName();
    }

    public static MessageArgs arguments(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Localization arguments require name-value pairs");
        }
        MessageArgs.Builder arguments = MessageArgs.builder();
        for (int index = 0; index < nameValuePairs.length; index += 2) {
            String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
            Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
            arguments.add(MessageArgument.untrusted(name, value));
        }
        return arguments.build();
    }

    public static MinecraftElement localizedElement(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }

    public static String localized(ServerPlayer viewer, TextKey key) {
        return plain(viewer, key, MessageArgs.empty());
    }

    public static String plain(ServerPlayer viewer, TextKey key, MessageArgs arguments) {
        return MinecraftMenuText.text(viewer, key, arguments).getString();
    }

    public static boolean isCancelInput(ServerPlayer viewer, String text) {
        return text.equalsIgnoreCase(localized(viewer, WormholesMessages.PORTAL_INPUT_CANCEL));
    }

    public static void notifySetting(ServerPlayer viewer, MinecraftPortal portal, TextKey message) {
        notifySetting(viewer, portal, message, MessageArgs.empty());
    }

    public static void notifySetting(ServerPlayer viewer, MinecraftPortal portal, TextKey message, MessageArgs messageArguments) {
        if (viewer == null) {
            return;
        }
        MinecraftMenuText.notifySuccess(viewer, MinecraftLegacyText.text(viewer, WormholesMessages.PORTAL_SETTING_NOTIFICATION,
            arguments("portal", portal.getName(), "message", plain(viewer, message, messageArguments))));
    }

    public static void notifyNearby(WormholesModRuntime runtime, MinecraftPortal portal, TextKey message, MessageArgs messageArguments, boolean success) {
        ServerLevel level = runtime.portals().resolveLevel(portal);
        if (level == null) {
            return;
        }
        Vec3d center = portal.getGeometry().getApertureCenter();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center.x(), center.y(), center.z()) > NOTIFICATION_RADIUS_SQUARED) {
                continue;
            }
            String legacy = MinecraftLegacyText.text(player, message, messageArguments);
            if (success) {
                MinecraftMenuText.notifySuccess(player, legacy);
            } else {
                MinecraftMenuText.notifyFailure(player, legacy);
            }
        }
    }

    public static String currentModeLabel(ServerPlayer viewer, MinecraftPortal portal) {
        return portal.isMirrorMode() ? localized(viewer, WormholesMessages.PORTAL_LABEL_MIRROR) : portalTypeLabel(viewer, portal.getType());
    }

    public static String rtpRotationSummary(ServerPlayer viewer, RtpSettings settings) {
        RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
        if (requiredSettings.getAllocationMode() == RtpAllocationMode.PER_PLAYER) {
            return plain(viewer, WormholesMessages.PORTAL_RTP_ROTATION_PRIVATE,
                arguments("duration", formatRtpDuration(viewer, requiredSettings.getCycleDurationMillis())));
        }
        return rtpRotationLabel(viewer, requiredSettings.getRotationMode());
    }

    public static String directionLabel(ServerPlayer viewer, Face direction) {
        TextKey key = switch (direction) {
            case U -> WormholesMessages.PORTAL_LABEL_DIRECTION_UP;
            case D -> WormholesMessages.PORTAL_LABEL_DIRECTION_DOWN;
            case N -> WormholesMessages.PORTAL_LABEL_DIRECTION_NORTH;
            case S -> WormholesMessages.PORTAL_LABEL_DIRECTION_SOUTH;
            case E -> WormholesMessages.PORTAL_LABEL_DIRECTION_EAST;
            case W -> WormholesMessages.PORTAL_LABEL_DIRECTION_WEST;
        };
        return localized(viewer, key);
    }

    public static String travelModeLabel(ServerPlayer viewer, PortalTravelMode mode) {
        TextKey key = switch (mode) {
            case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
            case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
            case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
            case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
        };
        return localized(viewer, key);
    }

    public static String networkViewQualityLabel(ServerPlayer viewer, NetworkViewQuality quality) {
        return localized(viewer, quality.labelKey());
    }

    public static String portalTypeLabel(ServerPlayer viewer, PortalType type) {
        TextKey key = switch (type) {
            case PORTAL -> WormholesMessages.PORTAL_LABEL_PORTAL;
            case WORMHOLE -> WormholesMessages.PORTAL_LABEL_WORMHOLE;
            case GATEWAY -> WormholesMessages.PORTAL_LABEL_GATEWAY;
            case RTP -> WormholesMessages.PORTAL_LABEL_RTP;
        };
        return localized(viewer, key);
    }

    public static String projectionModeLabel(ServerPlayer viewer, ProjectionMode mode) {
        return localized(viewer, mode == ProjectionMode.ON ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
    }

    public static String onOffLabel(ServerPlayer viewer, boolean enabled) {
        return localized(viewer, enabled ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
    }

    public static String permissionModeLabel(ServerPlayer viewer, PortalPermissionMode mode) {
        return localized(viewer, mode == PortalPermissionMode.WHITELIST
            ? WormholesMessages.PORTAL_LABEL_WHITELIST : WormholesMessages.PORTAL_LABEL_BLACKLIST);
    }

    public static String permissionModeDescription(ServerPlayer viewer, PortalPermissionMode mode) {
        return localized(viewer, mode == PortalPermissionMode.WHITELIST
            ? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST
            : WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST);
    }

    public static Item modeIcon(PortalType type) {
        return switch (type) {
            case GATEWAY -> Items.END_CRYSTAL;
            case WORMHOLE -> Items.ENDER_PEARL;
            case PORTAL -> Items.ENDER_EYE;
            case RTP -> Items.COMPASS;
        };
    }

    public static String modeDescription(ServerPlayer viewer, PortalType type) {
        TextKey key = switch (type) {
            case GATEWAY -> WormholesMessages.PORTAL_MODE_DESCRIPTION_GATEWAY;
            case WORMHOLE -> WormholesMessages.PORTAL_MODE_DESCRIPTION_WORMHOLE;
            case PORTAL -> WormholesMessages.PORTAL_MODE_DESCRIPTION_PORTAL;
            case RTP -> WormholesMessages.PORTAL_MODE_DESCRIPTION_RTP;
        };
        return localized(viewer, key);
    }

    public static String rtpAllocationLabel(ServerPlayer viewer, RtpAllocationMode mode) {
        return localized(viewer, mode == RtpAllocationMode.SHARED
            ? WormholesMessages.PORTAL_LABEL_SHARED : WormholesMessages.PORTAL_LABEL_PER_PLAYER);
    }

    public static String rtpRotationLabel(ServerPlayer viewer, RtpRotationMode mode) {
        TextKey key = switch (mode) {
            case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
            case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
            case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
        };
        return localized(viewer, key);
    }

    public static String formatRtpDuration(ServerPlayer viewer, long durationMillis) {
        if (durationMillis % 3_600_000L == 0L) {
            return plain(viewer, WormholesMessages.RTP_DURATION_HOURS, arguments("value", durationMillis / 3_600_000L));
        }
        if (durationMillis % 60_000L == 0L) {
            return plain(viewer, WormholesMessages.RTP_DURATION_MINUTES, arguments("value", durationMillis / 60_000L));
        }
        if (durationMillis % 1_000L == 0L) {
            return plain(viewer, WormholesMessages.RTP_DURATION_SECONDS, arguments("value", durationMillis / 1_000L));
        }
        return plain(viewer, WormholesMessages.RTP_DURATION_DECIMAL_SECONDS,
            arguments("value", String.format(Locale.ROOT, "%.1f", Double.valueOf(durationMillis / 1_000.0D))));
    }
}
