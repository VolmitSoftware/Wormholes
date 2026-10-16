package art.arcane.wormholes.menu.portal;

import java.util.Locale;

import art.arcane.optics.math.Face;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.menu.MenuFeedbackStyle;
import art.arcane.wormholes.menu.MenuNotice;
import art.arcane.wormholes.menu.MenuNotices;
import art.arcane.wormholes.menu.MenuStrings;
import art.arcane.wormholes.menu.MenuText;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.NoticeChannel;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;

public final class PortalMenuText {
    private PortalMenuText() {
    }

    public static String plain(MenuStrings strings, TextKey key) {
        return MenuText.stripColors(strings.text(key));
    }

    public static String plain(MenuStrings strings, TextKey key, MessageArgs arguments) {
        return MenuText.stripColors(strings.text(key, arguments));
    }

    public static MenuNotice settingNotice(MenuViewer viewer, PortalMenuView portal, TextKey key, MessageArgs arguments, MenuFeedbackStyle style) {
        MenuStrings strings = viewer.strings();
        String text = strings.text(WormholesMessages.PORTAL_SETTING_NOTIFICATION, MessageArgs.builder()
            .untrusted("portal", portal.name())
            .untrusted("message", plain(strings, key, arguments))
            .build());
        return MenuNotices.text(text, style, NoticeChannel.SUCCESS_EFFECT);
    }

    public static String direction(MenuStrings strings, Face face) {
        TextKey key = switch (face) {
            case U -> WormholesMessages.PORTAL_LABEL_DIRECTION_UP;
            case D -> WormholesMessages.PORTAL_LABEL_DIRECTION_DOWN;
            case N -> WormholesMessages.PORTAL_LABEL_DIRECTION_NORTH;
            case S -> WormholesMessages.PORTAL_LABEL_DIRECTION_SOUTH;
            case E -> WormholesMessages.PORTAL_LABEL_DIRECTION_EAST;
            case W -> WormholesMessages.PORTAL_LABEL_DIRECTION_WEST;
        };
        return plain(strings, key);
    }

    public static String type(MenuStrings strings, PortalType type) {
        TextKey key = switch (type) {
            case PORTAL -> WormholesMessages.PORTAL_LABEL_PORTAL;
            case WORMHOLE -> WormholesMessages.PORTAL_LABEL_WORMHOLE;
            case GATEWAY -> WormholesMessages.PORTAL_LABEL_GATEWAY;
            case RTP -> WormholesMessages.PORTAL_LABEL_RTP;
        };
        return plain(strings, key);
    }

    public static String currentMode(MenuStrings strings, PortalMenuView portal) {
        return portal.mirror() ? plain(strings, WormholesMessages.PORTAL_LABEL_MIRROR) : type(strings, portal.type());
    }

    public static String modeDescription(MenuStrings strings, PortalType type) {
        TextKey key = switch (type) {
            case GATEWAY -> WormholesMessages.PORTAL_MODE_DESCRIPTION_GATEWAY;
            case WORMHOLE -> WormholesMessages.PORTAL_MODE_DESCRIPTION_WORMHOLE;
            case PORTAL -> WormholesMessages.PORTAL_MODE_DESCRIPTION_PORTAL;
            case RTP -> WormholesMessages.PORTAL_MODE_DESCRIPTION_RTP;
        };
        return plain(strings, key);
    }

    public static String onOff(MenuStrings strings, boolean on) {
        return plain(strings, on ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
    }

    public static String projection(MenuStrings strings, ProjectionMode mode) {
        return onOff(strings, mode == ProjectionMode.ON);
    }

    public static String permissionMode(MenuStrings strings, PortalPermissionMode mode) {
        return plain(strings, mode == PortalPermissionMode.WHITELIST ? WormholesMessages.PORTAL_LABEL_WHITELIST : WormholesMessages.PORTAL_LABEL_BLACKLIST);
    }

    public static String rtpAllocation(MenuStrings strings, RtpAllocationMode mode) {
        return plain(strings, mode == RtpAllocationMode.SHARED ? WormholesMessages.PORTAL_LABEL_SHARED : WormholesMessages.PORTAL_LABEL_PER_PLAYER);
    }

    public static String rtpRotation(MenuStrings strings, PortalMenuView.Rtp rtp) {
        if (rtp.allocation() == RtpAllocationMode.PER_PLAYER) {
            return plain(strings, WormholesMessages.PORTAL_RTP_ROTATION_PRIVATE, MessageArgs.builder()
                .untrusted("duration", duration(strings, rtp.cycleMillis()))
                .build());
        }
        TextKey key = switch (rtp.rotation()) {
            case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
            case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
            case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
        };
        return plain(strings, key);
    }

    public static String duration(MenuStrings strings, long durationMillis) {
        if (durationMillis % 3_600_000L == 0L) {
            return plain(strings, WormholesMessages.RTP_DURATION_HOURS, value(durationMillis / 3_600_000L));
        }
        if (durationMillis % 60_000L == 0L) {
            return plain(strings, WormholesMessages.RTP_DURATION_MINUTES, value(durationMillis / 60_000L));
        }
        if (durationMillis % 1_000L == 0L) {
            return plain(strings, WormholesMessages.RTP_DURATION_SECONDS, value(durationMillis / 1_000L));
        }
        return plain(strings, WormholesMessages.RTP_DURATION_DECIMAL_SECONDS,
            value(String.format(Locale.ROOT, "%.1f", Double.valueOf(durationMillis / 1_000.0D))));
    }

    public static String clip(String value, int maxLength) {
        if (MenuText.codePoints(value) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private static MessageArgs value(Object value) {
        return MessageArgs.builder().untrusted("value", value).build();
    }
}
