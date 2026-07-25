package art.arcane.wormholes.portal;

import java.util.Locale;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import net.md_5.bungee.api.ChatColor;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.volmlib.util.data.MaterialBlock;
import art.arcane.volmlib.util.inventorygui.UIElement;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.util.Direction;

final class LocalPortalText
{
	private final LocalPortal portal;

	LocalPortalText(LocalPortal portal)
	{
		this.portal = portal;
	}

	void notifySetting(Player viewer, TextKey message)
	{
		notifySetting(viewer, message, MessageArgs.empty());
	}

	void notifySetting(Player viewer, TextKey message, MessageArgs messageArguments)
	{
		if(viewer == null)
		{
			return;
		}
		Wormholes.effectManager.playNotificationSuccess(Wormholes.text().legacy(
				WormholesMessages.PORTAL_SETTING_NOTIFICATION,
				arguments(
						"portal", portal.getName(),
						"message", Wormholes.text().plain(message, messageArguments))), viewer);
	}

	String router(boolean dark, IPortal source)
	{
		String str = "";

		if(source != null)
		{
			str += (dark ? ChatColor.GRAY : ChatColor.YELLOW) + "" + ChatColor.BOLD + source.getName();
			str += ChatColor.GRAY + " -> ";
		}

		str += (dark ? ChatColor.BLACK : ChatColor.GOLD) + "" + ChatColor.BOLD + portal.getName();

		ITunnel activeTunnel = portal.getTunnel();
		IPortal linkedDestination = activeTunnel == null ? null : activeTunnel.getDestination();

		if(linkedDestination != null)
		{
			str += ChatColor.GRAY + " -> ";
			str += (dark ? ChatColor.GRAY : ChatColor.GRAY) + "" + ChatColor.BOLD + linkedDestination.getName();
		}

		return str;
	}

	String currentModeLabel()
	{
		return portal.isMirrorMode() ? localized(WormholesMessages.PORTAL_LABEL_MIRROR) : portalTypeLabel(portal.getType());
	}

	String rtpRotationSummary(RtpSettings settings)
	{
		RtpSettings requiredSettings = Objects.requireNonNull(settings, "settings");
		if(requiredSettings.getAllocationMode() == RtpAllocationMode.PER_PLAYER)
		{
			return Wormholes.text().plain(WormholesMessages.PORTAL_RTP_ROTATION_PRIVATE,
					arguments("duration", formatRtpDuration(requiredSettings.getCycleDurationMillis())));
		}
		return rtpRotationLabel(requiredSettings.getRotationMode());
	}

	static UIElement localizedElement(String id, LinesKey key, MessageArgs arguments, Material material)
	{
		UIElement element = new UIElement(id);
		element.setMaterial(new MaterialBlock(material));
		Wormholes.text().apply(element, key, arguments);
		return element;
	}

	static MessageArgs arguments(Object... nameValuePairs)
	{
		if(nameValuePairs.length % 2 != 0)
		{
			throw new IllegalArgumentException("Localization arguments require name-value pairs");
		}
		MessageArgument[] arguments = new MessageArgument[nameValuePairs.length / 2];
		for(int index = 0; index < nameValuePairs.length; index += 2)
		{
			String name = Objects.requireNonNull((String) nameValuePairs[index], "Localization argument name");
			Object value = Objects.requireNonNull(nameValuePairs[index + 1], "Localization argument value");
			arguments[index / 2] = MessageArgument.untrusted(name, value);
		}
		return WormholesLocalization.args(arguments);
	}

	static String localized(TextKey key)
	{
		return Wormholes.text().plain(key);
	}

	static boolean isCancelInput(String text)
	{
		return text.equalsIgnoreCase(localized(WormholesMessages.PORTAL_INPUT_CANCEL));
	}

	static String directionLabel(Direction direction)
	{
		TextKey key = switch(direction)
		{
			case U -> WormholesMessages.PORTAL_LABEL_DIRECTION_UP;
			case D -> WormholesMessages.PORTAL_LABEL_DIRECTION_DOWN;
			case N -> WormholesMessages.PORTAL_LABEL_DIRECTION_NORTH;
			case S -> WormholesMessages.PORTAL_LABEL_DIRECTION_SOUTH;
			case E -> WormholesMessages.PORTAL_LABEL_DIRECTION_EAST;
			case W -> WormholesMessages.PORTAL_LABEL_DIRECTION_WEST;
		};
		return localized(key);
	}

	static String travelModeLabel(PortalTravelMode mode)
	{
		TextKey key = switch(mode)
		{
			case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
			case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
			case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
			case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
		};
		return localized(key);
	}

	static String networkViewQualityLabel(NetworkViewQuality quality)
	{
		TextKey key = switch(quality)
		{
			case STANDARD -> WormholesMessages.PORTAL_LABEL_STANDARD;
			case PERFORMANCE -> WormholesMessages.PORTAL_LABEL_PERFORMANCE;
			case BALANCED -> WormholesMessages.PORTAL_LABEL_BALANCED;
			case CINEMATIC -> WormholesMessages.PORTAL_LABEL_CINEMATIC;
			case CUSTOM -> WormholesMessages.PORTAL_LABEL_CUSTOM;
		};
		return localized(key);
	}

	static String portalTypeLabel(PortalType type)
	{
		TextKey key = switch(type)
		{
			case PORTAL -> WormholesMessages.PORTAL_LABEL_PORTAL;
			case WORMHOLE -> WormholesMessages.PORTAL_LABEL_WORMHOLE;
			case GATEWAY -> WormholesMessages.PORTAL_LABEL_GATEWAY;
			case RTP -> WormholesMessages.PORTAL_LABEL_RTP;
		};
		return localized(key);
	}

	static String projectionModeLabel(ProjectionMode mode)
	{
		return localized(mode == ProjectionMode.ON ? WormholesMessages.LABEL_ON : WormholesMessages.LABEL_OFF);
	}

	static String permissionModeLabel(PortalPermissionMode mode)
	{
		return localized(mode == PortalPermissionMode.WHITELIST
				? WormholesMessages.PORTAL_LABEL_WHITELIST : WormholesMessages.PORTAL_LABEL_BLACKLIST);
	}

	static String permissionModeDescription(PortalPermissionMode mode)
	{
		return localized(mode == PortalPermissionMode.WHITELIST
				? WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_WHITELIST
				: WormholesMessages.PORTAL_PERMISSION_DESCRIPTION_BLACKLIST);
	}

	static Material modeIcon(PortalType type)
	{
		return switch(type)
		{
			case GATEWAY -> Material.END_CRYSTAL;
			case WORMHOLE -> Material.ENDER_PEARL;
			case PORTAL -> Material.ENDER_EYE;
			case RTP -> Material.COMPASS;
		};
	}

	static String modeDescription(PortalType type)
	{
		TextKey key = switch(type)
		{
			case GATEWAY -> WormholesMessages.PORTAL_MODE_DESCRIPTION_GATEWAY;
			case WORMHOLE -> WormholesMessages.PORTAL_MODE_DESCRIPTION_WORMHOLE;
			case PORTAL -> WormholesMessages.PORTAL_MODE_DESCRIPTION_PORTAL;
			case RTP -> WormholesMessages.PORTAL_MODE_DESCRIPTION_RTP;
		};
		return localized(key);
	}

	static String rtpAllocationLabel(RtpAllocationMode mode)
	{
		return localized(mode == RtpAllocationMode.SHARED
				? WormholesMessages.PORTAL_LABEL_SHARED : WormholesMessages.PORTAL_LABEL_PER_PLAYER);
	}

	static String rtpRotationLabel(RtpRotationMode mode)
	{
		TextKey key = switch(mode)
		{
			case STATIC -> WormholesMessages.RTP_ROTATION_STATIC;
			case TIMED -> WormholesMessages.RTP_ROTATION_TIMED;
			case ON_TRAVERSAL -> WormholesMessages.RTP_ROTATION_TRIP;
		};
		return localized(key);
	}

	static String formatRtpDuration(long durationMillis)
	{
		if(durationMillis % 3_600_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_HOURS,
					arguments("value", durationMillis / 3_600_000L));
		}
		if(durationMillis % 60_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_MINUTES,
					arguments("value", durationMillis / 60_000L));
		}
		if(durationMillis % 1_000L == 0L)
		{
			return Wormholes.text().plain(WormholesMessages.RTP_DURATION_SECONDS,
					arguments("value", durationMillis / 1_000L));
		}
		return Wormholes.text().plain(WormholesMessages.RTP_DURATION_DECIMAL_SECONDS,
				arguments("value", String.format(Locale.ROOT, "%.1f", Double.valueOf(durationMillis / 1_000.0D))));
	}
}
