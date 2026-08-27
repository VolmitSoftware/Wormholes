package art.arcane.wormholes.portal;

import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.volmlib.util.json.JSONObject;

final class LocalPortalSettings
{
	private static final int DEFAULT_NETWORK_VIEW_DEPTH = 64;
	private static final int DEFAULT_NETWORK_VIEW_LATERAL_PAD = 48;
	private static final int DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS = 60;
	private static final int DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS = 10;
	private static final int DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS = 30;
	static final String DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK = "minecraft:air";
	private static final boolean DEFAULT_BLACKOUT_BACKGROUND = false;
	private static final BlackoutColor DEFAULT_BLACKOUT_COLOR = BlackoutColor.BLACK;
	private static final int DEFAULT_ACTIVATION_RANGE = 0;
	private static final ProjectionRenderMode DEFAULT_RENDER_MODE = ProjectionRenderMode.VENTICULAR;
	private static final AmbientParticleStyle DEFAULT_AMBIENT_STYLE = AmbientParticleStyle.SPARKS;
	private static final int DEFAULT_AMBIENT_COLOR = 0xB969FF;
	private static final String DEFAULT_SURFACE_SKIN = "";

	private final LocalPortal portal;
	private ProjectionMode projectionMode;
	private boolean mirrorMode;
	private MirrorRotation mirrorRotation;
	private PortalPermissionMode permissionMode;
	private boolean outgoingTraversalsEnabled;
	private boolean incomingTraversalsEnabled;
	private int networkViewDepth;
	private volatile boolean networkViewCustomSelected;
	private int networkViewLateralPad;
	private int networkViewHeartbeatTicks;
	private int networkViewEntityIntervalTicks;
	private int networkViewUnsubscribeGraceSeconds;
	private String networkViewFallbackBlock;
	private boolean blackoutBackground;
	private BlackoutColor blackoutColor;
	private int activationRange;
	private ProjectionRenderMode renderMode;
	private AmbientParticleStyle ambientStyle;
	private int ambientColor;
	private String surfaceSkin;
	private PortalTravelCost travelCost;
	private boolean settingsSyncEnabled;

	LocalPortalSettings(LocalPortal portal)
	{
		this.portal = portal;
		projectionMode = ProjectionMode.ON;
		mirrorMode = false;
		mirrorRotation = MirrorRotation.DEGREES_0;
		permissionMode = PortalPermissionMode.BLACKLIST;
		outgoingTraversalsEnabled = true;
		incomingTraversalsEnabled = true;
		networkViewDepth = DEFAULT_NETWORK_VIEW_DEPTH;
		networkViewLateralPad = DEFAULT_NETWORK_VIEW_LATERAL_PAD;
		networkViewHeartbeatTicks = DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS;
		networkViewEntityIntervalTicks = DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS;
		networkViewUnsubscribeGraceSeconds = DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS;
		networkViewFallbackBlock = DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK;
		blackoutBackground = DEFAULT_BLACKOUT_BACKGROUND;
		blackoutColor = DEFAULT_BLACKOUT_COLOR;
		activationRange = DEFAULT_ACTIVATION_RANGE;
		renderMode = DEFAULT_RENDER_MODE;
		ambientStyle = DEFAULT_AMBIENT_STYLE;
		ambientColor = DEFAULT_AMBIENT_COLOR;
		surfaceSkin = DEFAULT_SURFACE_SKIN;
		travelCost = null;
		settingsSyncEnabled = true;
	}

	void save(JSONObject j)
	{
		j.put("permissionMode", permissionMode.name());
		j.put("outgoingTraversalsEnabled", outgoingTraversalsEnabled);
		j.put("incomingTraversalsEnabled", incomingTraversalsEnabled);
		j.put("networkViewDepth", networkViewDepth);
		j.put("networkViewLateralPad", networkViewLateralPad);
		j.put("networkViewHeartbeatTicks", networkViewHeartbeatTicks);
		j.put("networkViewEntityIntervalTicks", networkViewEntityIntervalTicks);
		j.put("networkViewUnsubscribeGraceSeconds", networkViewUnsubscribeGraceSeconds);
		j.put("networkViewFallbackBlock", networkViewFallbackBlock);
		j.put("blackoutBackground", blackoutBackground);
		j.put("blackoutColor", blackoutColor.name());
		j.put("activationRange", activationRange);
		j.put("renderMode", renderMode.name());
		j.put("ambientStyle", ambientStyle.name());
		j.put("ambientColor", ambientColor);
		j.put("surfaceSkin", surfaceSkin);
		if(travelCost != null)
		{
			j.put("travelCost", travelCost.toJson());
		}
		j.put("settingsSyncEnabled", settingsSyncEnabled);
	}

	boolean load(JSONObject j)
	{
		String storedProjectionMode = j.optString("projectionMode", ProjectionMode.ON.name());
		projectionMode = resolveProjectionMode(j);
		mirrorMode = resolveMirrorMode(j);
		MirrorRotation storedMirrorRotation = resolveMirrorRotation(j);
		mirrorRotation = storedMirrorRotation.coherentFor(portal.getFrame());
		permissionMode = resolvePermissionMode(j);
		outgoingTraversalsEnabled = !j.has("outgoingTraversalsEnabled") || j.getBoolean("outgoingTraversalsEnabled");
		incomingTraversalsEnabled = !j.has("incomingTraversalsEnabled") || j.getBoolean("incomingTraversalsEnabled");
		networkViewDepth = readNetworkViewInt(j, "networkViewDepth", DEFAULT_NETWORK_VIEW_DEPTH, 1, 128);
		networkViewLateralPad = readNetworkViewInt(j, "networkViewLateralPad", DEFAULT_NETWORK_VIEW_LATERAL_PAD, 0, 64);
		networkViewHeartbeatTicks = readNetworkViewInt(j, "networkViewHeartbeatTicks", DEFAULT_NETWORK_VIEW_HEARTBEAT_TICKS, 2, 600);
		networkViewEntityIntervalTicks = readNetworkViewInt(j, "networkViewEntityIntervalTicks", DEFAULT_NETWORK_VIEW_ENTITY_INTERVAL_TICKS, 2, 600);
		networkViewUnsubscribeGraceSeconds = readNetworkViewInt(j, "networkViewUnsubscribeGraceSeconds", DEFAULT_NETWORK_VIEW_UNSUBSCRIBE_GRACE_SECONDS, 5, 600);
		networkViewFallbackBlock = normalizeNetworkViewFallbackBlock(j.optString("networkViewFallbackBlock", DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK));
		blackoutBackground = j.optBoolean("blackoutBackground", DEFAULT_BLACKOUT_BACKGROUND);
		blackoutColor = BlackoutColor.fromName(j.optString("blackoutColor", ""), DEFAULT_BLACKOUT_COLOR);
		activationRange = readActivationRange(j, "activationRange");
		renderMode = ProjectionRenderMode.fromName(j.optString("renderMode", ""), DEFAULT_RENDER_MODE);
		ambientStyle = AmbientParticleStyle.fromName(j.optString("ambientStyle", ""), DEFAULT_AMBIENT_STYLE);
		ambientColor = normalizeAmbientColor(j.optInt("ambientColor", DEFAULT_AMBIENT_COLOR));
		surfaceSkin = PortalSurfaceSkins.normalizeSkin(j.optString("surfaceSkin", DEFAULT_SURFACE_SKIN));
		boolean malformedTravelCost = j.has("travelCost")
				&& !j.isNull("travelCost")
				&& j.optJSONObject("travelCost") == null;
		boolean invalidTravelCost = malformedTravelCost;
		try
		{
			travelCost = malformedTravelCost
					? null : PortalTravelCost.fromJson(j.optJSONObject("travelCost"));
		}
		catch(IllegalArgumentException exception)
		{
			travelCost = null;
			invalidTravelCost = true;
			if(Wormholes.instance == null)
			{
				Wormholes.w("Portal " + portal.getId() + " has an invalid travel cost");
			}
			else
			{
				Wormholes.instance.getLogger().log(Level.WARNING,
						"Portal " + portal.getId() + " has an invalid travel cost; travel will be free", exception);
			}
		}
		if(malformedTravelCost)
		{
			Wormholes.w("Portal " + portal.getId() + " has a malformed travel cost; travel will be free");
		}
		settingsSyncEnabled = !j.has("settingsSyncEnabled") || j.getBoolean("settingsSyncEnabled");
		boolean projectionStateNormalized = !j.has("mirrorMode") || !storedProjectionMode.equals(projectionMode.name());
		return storedMirrorRotation != mirrorRotation || projectionStateNormalized || invalidTravelCost;
	}

	boolean isProjecting()
	{
		return projectionMode == ProjectionMode.ON;
	}

	ProjectionMode getProjectionMode()
	{
		return projectionMode;
	}

	void setProjectionMode(ProjectionMode mode)
	{
		ProjectionMode normalized = mode == null ? ProjectionMode.ON : mode;
		DimensionalPortalKind kind = portal.getDimensionalPortalKind();
		if(kind == DimensionalPortalKind.END_SOURCE)
		{
			normalized = ProjectionMode.ON;
		}
		else if(kind.isReceiverOnly())
		{
			normalized = ProjectionMode.OFF;
		}
		if(this.projectionMode == normalized)
		{
			return;
		}
		this.projectionMode = normalized;
		portal.gate().invalidateProjection();
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	void assignProjectionMode(ProjectionMode mode)
	{
		projectionMode = mode;
	}

	boolean isMirrorMode()
	{
		return mirrorMode;
	}

	void setMirrorMode(boolean mirrorMode)
	{
		if(mirrorMode && portal.getTunnel() != null && PortalSyncService.isApplyingRemote())
		{
			return;
		}
		if(mirrorMode && portal.getType() == PortalType.RTP)
		{
			portal.setType(PortalType.PORTAL);
		}
		boolean normalized = mirrorMode && !portal.getDimensionalPortalKind().isManagedPortal();
		if(this.mirrorMode == normalized)
		{
			return;
		}
		this.mirrorMode = normalized;
		if(normalized && portal.getTunnel() != null)
		{
			portal.linking().assignTunnel(null);
		}
		portal.gate().invalidateProjection();
		portal.save();
		if(Wormholes.portalSyncService != null)
		{
			Wormholes.portalSyncService.broadcastRemoteCache(portal);
		}
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	void assignMirrorMode(boolean mirrorMode)
	{
		this.mirrorMode = mirrorMode;
	}

	MirrorRotation getMirrorRotation()
	{
		return mirrorRotation;
	}

	void setMirrorRotation(MirrorRotation rotation)
	{
		MirrorRotation normalized = (rotation == null ? MirrorRotation.DEGREES_0 : rotation).coherentFor(portal.getFrame());
		if(mirrorRotation == normalized)
		{
			return;
		}
		mirrorRotation = normalized;
		portal.gate().invalidateProjection();
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	boolean normalizeMirrorRotationForFrame()
	{
		MirrorRotation normalized = (mirrorRotation == null ? MirrorRotation.DEGREES_0 : mirrorRotation).coherentFor(portal.getFrame());
		if(mirrorRotation == normalized)
		{
			return false;
		}
		mirrorRotation = normalized;
		return true;
	}

	static ProjectionMode resolveProjectionMode(JSONObject j)
	{
		return "OFF".equalsIgnoreCase(j.optString("projectionMode", ProjectionMode.ON.name()))
				? ProjectionMode.OFF : ProjectionMode.ON;
	}

	static boolean resolveMirrorMode(JSONObject j)
	{
		if(j.has("mirrorMode"))
		{
			return j.getBoolean("mirrorMode");
		}
		return "MIRROR".equalsIgnoreCase(j.optString("projectionMode", ""));
	}

	static MirrorRotation resolveMirrorRotation(JSONObject j)
	{
		return MirrorRotation.fromDegrees(j.optInt("mirrorRotationDegrees", 0));
	}

	private static PortalPermissionMode resolvePermissionMode(JSONObject j)
	{
		if(j.has("permissionMode"))
		{
			return PortalPermissionMode.fromName(j.getString("permissionMode"));
		}
		return PortalPermissionMode.BLACKLIST;
	}

	PortalPermissionMode getPermissionMode()
	{
		return permissionMode;
	}

	void setPermissionMode(PortalPermissionMode mode)
	{
		PortalPermissionMode normalized = mode == null ? PortalPermissionMode.BLACKLIST : mode;
		if(permissionMode == normalized)
		{
			return;
		}
		permissionMode = normalized;
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	String getPermissionNode()
	{
		return "wormholes.portal." + sanitizePermissionName(portal.getName());
	}

	boolean allowsPortalPermission(Entity entity)
	{
		if(!(entity instanceof Player player))
		{
			return true;
		}
		if(player.isOp())
		{
			return true;
		}
		return permissionMode.allows(player, getPermissionNode());
	}

	boolean isOutgoingTraversalsEnabled()
	{
		return outgoingTraversalsEnabled;
	}

	void setOutgoingTraversalsEnabled(boolean enabled)
	{
		DimensionalPortalKind kind = portal.getDimensionalPortalKind();
		boolean normalized = kind == DimensionalPortalKind.NETHER
				|| kind == DimensionalPortalKind.END_SOURCE
				|| (!kind.isReceiverOnly() && enabled);
		if(outgoingTraversalsEnabled == normalized)
		{
			return;
		}
		outgoingTraversalsEnabled = normalized;
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	void assignOutgoingTraversalsEnabled(boolean enabled)
	{
		outgoingTraversalsEnabled = enabled;
	}

	PortalTravelCost getTravelCost()
	{
		return travelCost;
	}

	void setVanillaTravelCostItem(ItemStack item)
	{
		int quantity = travelCost instanceof VanillaTravelCost vanilla ? vanilla.getQuantity() : 1;
		travelCost = VanillaTravelCost.of(item, quantity);
		travelCostChanged();
	}

	void setVanillaTravelCostQuantity(int quantity)
	{
		if(!(travelCost instanceof VanillaTravelCost vanilla))
		{
			return;
		}
		VanillaTravelCost updated = vanilla.withQuantity(quantity);
		if(updated.getQuantity() == vanilla.getQuantity())
		{
			return;
		}
		travelCost = updated;
		travelCostChanged();
	}

	void setVaultTravelCost(String amount)
	{
		travelCost = VaultTravelCost.of(amount);
		travelCostChanged();
	}

	void clearTravelCost()
	{
		if(travelCost == null)
		{
			return;
		}
		travelCost = null;
		travelCostChanged();
	}

	boolean isIncomingTraversalsEnabled()
	{
		return incomingTraversalsEnabled;
	}

	void setIncomingTraversalsEnabled(boolean enabled)
	{
		DimensionalPortalKind kind = portal.getDimensionalPortalKind();
		boolean normalized = kind == DimensionalPortalKind.NETHER
				|| kind.isReceiverOnly()
				|| (kind != DimensionalPortalKind.END_SOURCE && enabled);
		if(incomingTraversalsEnabled == normalized)
		{
			return;
		}
		incomingTraversalsEnabled = normalized;
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	void assignIncomingTraversalsEnabled(boolean enabled)
	{
		incomingTraversalsEnabled = enabled;
	}

	PortalTravelMode getTravelMode()
	{
		return PortalTravelMode.from(outgoingTraversalsEnabled, incomingTraversalsEnabled);
	}

	void setTravelMode(PortalTravelMode mode)
	{
		if(portal.getDimensionalPortalKind().isManagedPortal())
		{
			return;
		}
		boolean nextOutgoing = mode.allowsOutgoing();
		boolean nextIncoming = mode.allowsIncoming();
		if(outgoingTraversalsEnabled == nextOutgoing && incomingTraversalsEnabled == nextIncoming)
		{
			return;
		}
		outgoingTraversalsEnabled = nextOutgoing;
		incomingTraversalsEnabled = nextIncoming;
		Wormholes.v("QA_EVT {\"event\":\"travel_mode\",\"status\":\"info\",\"details\":\"" + mode.name().toLowerCase(Locale.ROOT)
				+ "\",\"context\":{\"portal\":\"" + portal.getId() + "\"}}");
		portal.save();
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	NetworkViewQuality getNetworkViewQuality()
	{
		if(networkViewCustomSelected)
		{
			return NetworkViewQuality.CUSTOM;
		}
		return NetworkViewQuality.from(networkViewDepth, networkViewHeartbeatTicks, networkViewEntityIntervalTicks, networkViewUnsubscribeGraceSeconds);
	}

	void setNetworkViewQuality(NetworkViewQuality quality)
	{
		if(quality == NetworkViewQuality.CUSTOM)
		{
			networkViewCustomSelected = true;
			return;
		}
		networkViewCustomSelected = false;
		if(networkViewDepth == quality.getDepth()
				&& networkViewHeartbeatTicks == quality.getHeartbeatTicks()
				&& networkViewEntityIntervalTicks == quality.getEntityIntervalTicks()
				&& networkViewUnsubscribeGraceSeconds == quality.getUnsubscribeGraceSeconds())
		{
			return;
		}
		networkViewDepth = quality.getDepth();
		networkViewHeartbeatTicks = quality.getHeartbeatTicks();
		networkViewEntityIntervalTicks = quality.getEntityIntervalTicks();
		networkViewUnsubscribeGraceSeconds = quality.getUnsubscribeGraceSeconds();
		Wormholes.v("QA_EVT {\"event\":\"stream_quality\",\"status\":\"info\",\"details\":\"" + quality.name().toLowerCase(Locale.ROOT)
				+ "\",\"context\":{\"portal\":\"" + portal.getId() + "\"}}");
		networkViewSettingsChanged();
	}

	int getNetworkViewDepth()
	{
		return networkViewDepth;
	}

	void setNetworkViewDepth(int depth)
	{
		int normalized = clampNetworkViewInt(depth, 1, 128);
		if(networkViewDepth == normalized)
		{
			return;
		}
		networkViewDepth = normalized;
		networkViewSettingsChanged();
	}

	int getNetworkViewLateralPad()
	{
		return networkViewLateralPad;
	}

	void setNetworkViewLateralPad(int lateralPad)
	{
		int normalized = clampNetworkViewInt(lateralPad, 0, 64);
		if(networkViewLateralPad == normalized)
		{
			return;
		}
		networkViewLateralPad = normalized;
		networkViewSettingsChanged();
	}

	int getNetworkViewHeartbeatTicks()
	{
		return networkViewHeartbeatTicks;
	}

	void setNetworkViewHeartbeatTicks(int ticks)
	{
		int normalized = clampNetworkViewInt(ticks, 2, 600);
		if(networkViewHeartbeatTicks == normalized)
		{
			return;
		}
		networkViewHeartbeatTicks = normalized;
		renderSettingsChanged();
	}

	int getNetworkViewEntityIntervalTicks()
	{
		return networkViewEntityIntervalTicks;
	}

	void setNetworkViewEntityIntervalTicks(int ticks)
	{
		int normalized = clampNetworkViewInt(ticks, 2, 600);
		if(networkViewEntityIntervalTicks == normalized)
		{
			return;
		}
		networkViewEntityIntervalTicks = normalized;
		renderSettingsChanged();
	}

	int getNetworkViewUnsubscribeGraceSeconds()
	{
		return networkViewUnsubscribeGraceSeconds;
	}

	void setNetworkViewUnsubscribeGraceSeconds(int seconds)
	{
		int normalized = clampNetworkViewInt(seconds, 5, 600);
		if(networkViewUnsubscribeGraceSeconds == normalized)
		{
			return;
		}
		networkViewUnsubscribeGraceSeconds = normalized;
		renderSettingsChanged();
	}

	String getNetworkViewFallbackBlock()
	{
		return networkViewFallbackBlock;
	}

	void setNetworkViewFallbackBlock(String blockState)
	{
		String normalized = normalizeNetworkViewFallbackBlock(blockState);
		if(networkViewFallbackBlock.equals(normalized))
		{
			return;
		}
		networkViewFallbackBlock = normalized;
		renderSettingsChanged();
	}

	boolean isBlackoutBackground()
	{
		return blackoutBackground;
	}

	void setBlackoutBackground(boolean enabled)
	{
		if(blackoutBackground == enabled)
		{
			return;
		}
		blackoutBackground = enabled;
		renderSettingsChanged();
	}

	BlackoutColor getBlackoutColor()
	{
		return blackoutColor;
	}

	void setBlackoutColor(BlackoutColor color)
	{
		if(color == null || blackoutColor == color)
		{
			return;
		}
		blackoutColor = color;
		renderSettingsChanged();
	}

	int getActivationRange()
	{
		return activationRange;
	}

	void setActivationRange(int rangeBlocks)
	{
		int normalized = normalizeActivationRange(rangeBlocks);
		if(activationRange == normalized)
		{
			return;
		}
		activationRange = normalized;
		renderSettingsChanged();
	}

	double getEffectiveActivationRange()
	{
		return activationRange > 0 ? activationRange : Settings.PROJECTION_RANGE;
	}

	ProjectionRenderMode getRenderMode()
	{
		return renderMode;
	}

	void setRenderMode(ProjectionRenderMode mode)
	{
		if(mode == null || renderMode == mode)
		{
			return;
		}
		renderMode = mode;
		networkViewSettingsChanged();
	}

	AmbientParticleStyle getAmbientStyle()
	{
		return ambientStyle;
	}

	void setAmbientStyle(AmbientParticleStyle style)
	{
		if(style == null || ambientStyle == style)
		{
			return;
		}
		ambientStyle = style;
		renderSettingsChanged();
	}

	int getAmbientColor()
	{
		return ambientColor;
	}

	void setAmbientColor(int color)
	{
		int normalized = normalizeAmbientColor(color);
		if(ambientColor == normalized)
		{
			return;
		}
		ambientColor = normalized;
		renderSettingsChanged();
	}

	String getSurfaceSkin()
	{
		return surfaceSkin;
	}

	void setSurfaceSkin(String skin)
	{
		String normalized = PortalSurfaceSkins.normalizeSkin(skin);
		if(surfaceSkin.equals(normalized))
		{
			return;
		}
		surfaceSkin = normalized;
		renderSettingsChanged();
	}

	boolean isSettingsSyncEnabled()
	{
		return settingsSyncEnabled;
	}

	void setSettingsSyncEnabled(boolean enabled)
	{
		if(settingsSyncEnabled == enabled)
		{
			return;
		}
		settingsSyncEnabled = enabled;
		portal.save();
		if(portal.isGateway() && Wormholes.portalSyncService != null && !PortalSyncService.isApplyingRemote())
		{
			Wormholes.portalSyncService.broadcastSettingsToggle(portal);
		}
		refreshOpenMenusUnlessApplyingRemote();
	}

	private void networkViewSettingsChanged()
	{
		settingsChanged(true);
	}

	private void travelCostChanged()
	{
		portal.save();
		refreshOpenMenusUnlessApplyingRemote();
	}

	private void renderSettingsChanged()
	{
		settingsChanged(false);
	}

	private void settingsChanged(boolean captureShapeChanged)
	{
		portal.save();
		if(captureShapeChanged && Wormholes.viewServer != null)
		{
			Wormholes.viewServer.refreshPortal(portal);
		}
		if(Wormholes.projectionManager != null)
		{
			Wormholes.projectionManager.removeProjector(portal);
		}
		broadcastSettingsIfEnabled();
		refreshOpenMenusUnlessApplyingRemote();
	}

	void refreshOpenMenusUnlessApplyingRemote()
	{
		if(PortalSyncService.isApplyingRemote())
		{
			return;
		}
		portal.refreshOpenMenus();
	}

	void broadcastSettingsIfEnabled()
	{
		if(!settingsSyncEnabled)
		{
			return;
		}
		if(PortalSyncService.isApplyingRemote())
		{
			return;
		}
		if(Wormholes.portalSyncService == null)
		{
			return;
		}
		Wormholes.portalSyncService.broadcastSettings(portal);
	}

	void syncLinkedLocalsIfEnabled()
	{
		if(!settingsSyncEnabled || PortalSyncService.isApplyingRemote() || Wormholes.portalSyncService == null)
		{
			return;
		}

		Wormholes.portalSyncService.syncLinkedLocals(portal);
	}

	private static int readNetworkViewInt(JSONObject j, String key, int defaultValue, int min, int max)
	{
		int value = j.has(key) ? j.optInt(key, defaultValue) : defaultValue;
		return clampNetworkViewInt(value, min, max);
	}

	private static int readActivationRange(JSONObject j, String key)
	{
		return j.has(key) ? normalizeActivationRange(j.optInt(key, DEFAULT_ACTIVATION_RANGE)) : DEFAULT_ACTIVATION_RANGE;
	}

	private static int normalizeActivationRange(int value)
	{
		return value <= 0 ? 0 : clampNetworkViewInt(value, 8, 256);
	}

	private static int normalizeAmbientColor(int color)
	{
		return clampNetworkViewInt(color, 0, 0xFFFFFF);
	}

	private static int clampNetworkViewInt(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}

	static String normalizeNetworkViewFallbackBlock(String blockState)
	{
		String normalized = blockState == null || blockState.isBlank() ? DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK : blockState.trim();
		try
		{
			BlockData data = Bukkit.createBlockData(normalized);
			return data.getAsString();
		}
		catch(IllegalArgumentException e)
		{
			return DEFAULT_NETWORK_VIEW_FALLBACK_BLOCK;
		}
	}

	static String sanitizePermissionName(String name)
	{
		String source = name == null || name.isBlank() ? "unnamed" : name.toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(source.length());
		boolean previousSeparator = false;
		for(int i = 0; i < source.length(); i++)
		{
			char c = source.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
			if(allowed)
			{
				builder.append(c);
				previousSeparator = false;
				continue;
			}
			if(!previousSeparator)
			{
				builder.append('_');
				previousSeparator = true;
			}
		}

		String sanitized = trimPermissionSeparators(builder.toString());
		return sanitized.isEmpty() ? "unnamed" : sanitized;
	}

	private static String trimPermissionSeparators(String value)
	{
		int start = 0;
		int end = value.length();
		while(start < end && value.charAt(start) == '_')
		{
			start++;
		}
		while(end > start && value.charAt(end - 1) == '_')
		{
			end--;
		}
		return value.substring(start, end);
	}
}
