package art.arcane.wormholes.portal;

import java.io.IOException;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.F;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.ParticleEffect;

public class LocalPortal extends Portal implements ILocalPortal, IProgressivePortal, IFXPortal, IOwnablePortal, Listener
{
	private final LocalPortalLinking linking = new LocalPortalLinking(this);
	private final LocalPortalSettings settings = new LocalPortalSettings(this);
	private final LocalPortalGate gate = new LocalPortalGate(this);
	private final LocalPortalPersistence persistence = new LocalPortalPersistence(this);
	private final LocalPortalEffects effects = new LocalPortalEffects(this);
	private final LocalPortalTraversal traversal = new LocalPortalTraversal(this);
	private final LocalPortalRtp rtp = new LocalPortalRtp(this);
	private final LocalPortalDepartureHold departureHold = new LocalPortalDepartureHold(this);
	private final LocalPortalPrompts prompts = new LocalPortalPrompts(this);
	private final LocalPortalMenus menus = new LocalPortalMenus(this);
	private PortalStructure structure;
	private volatile PortalType type;
	private UUID owner;

	public LocalPortal(UUID id, PortalType type, PortalStructure structure)
	{
		super(id, structure.getCenter().toVector());
		this.owner = id;
		this.type = type;
		this.structure = structure;
		gate.assignOpen(false);
		setName(F.capitalize(getType().name().toLowerCase()) + " " + id.toString().substring(0, 4));
		persistence.markClean();
		rtp.initializeDefaults(type);
		gate.rebuildView();
	}

	LocalPortalSettings settings()
	{
		return settings;
	}

	LocalPortalGate gate()
	{
		return gate;
	}

	LocalPortalEffects effects()
	{
		return effects;
	}

	LocalPortalTraversal traversal()
	{
		return traversal;
	}

	LocalPortalRtp rtp()
	{
		return rtp;
	}

	LocalPortalDepartureHold departureHold()
	{
		return departureHold;
	}

	LocalPortalLinking linking()
	{
		return linking;
	}

	@Override
	public void saveJSON(JSONObject j)
	{
		super.saveJSON(j);
		persistence.writeState(j);
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		super.loadJSON(j);
		persistence.readState(j);
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	@Override
	public PortalStructure getStructure()
	{
		return structure;
	}

	void assignStructure(PortalStructure structure)
	{
		this.structure = structure;
	}

	@Override
	public PortalType getType()
	{
		return type;
	}

	void assignType(PortalType type)
	{
		this.type = type;
	}

	@Override
	public void setType(PortalType type)
	{
		linking.setType(type);
	}

	public RtpSettings getRtpSettings()
	{
		return rtp.getRtpSettings();
	}

	public void setRtpSettings(RtpSettings settings)
	{
		rtp.setRtpSettings(settings);
	}

	@Override
	public void update()
	{
		traversal.update();
	}

	@Override
	public void close()
	{
		gate.setOpen(false);
	}

	@Override
	public boolean isOpen()
	{
		return gate.isOpen();
	}

	@Override
	public void open()
	{
		gate.setOpen(true);
	}

	@Override
	public boolean isAmbientAttended()
	{
		return gate.isAmbientAttended();
	}

	@Override
	public void setAmbientAttended(boolean attended)
	{
		gate.setAmbientAttended(attended);
	}

	@Override
	public void setOpen(boolean open)
	{
		gate.setOpen(open);
	}

	public void phase(Axis a, ParticleEffect e, Location l, float scale)
	{
		effects.phase(a, e, l, scale);
	}

	@Override
	public void playEffect(PortalEffect effect, Location location)
	{
		effects.playEffect(effect, location);
	}

	@Override
	public void playEffect(PortalEffect effect)
	{
		playEffect(effect, null);
	}

	@Override
	public void showProgress(String text)
	{
		prompts.showProgress(text);
	}

	@Override
	public void hideProgress()
	{
		prompts.hideProgress();
	}

	@Override
	public boolean isShowingProgress()
	{
		return prompts.isShowingProgress();
	}

	@Override
	public String getCurrentProgress()
	{
		return prompts.getCurrentProgress();
	}

	@Override
	public void onLooking(Player p, boolean holdingWand)
	{
		prompts.onLooking(p, holdingWand);
	}

	@Override
	public void onWanded(Player p)
	{
		uiOpenPortalMenu(p);
	}

	@Override
	public boolean isLookingAt(Player p)
	{
		return prompts.isLookingAt(p);
	}

	@Override
	public void setDirection(Direction d)
	{
		gate.setDirection(d);
	}

	@Override
	public void setFrame(PortalFrame frame)
	{
		gate.setFrame(frame);
	}

	@Override
	public void receive(Traversive t)
	{
		traversal.receive(t);
	}

	@Override
	public Location computeExitTarget(Traversive t)
	{
		return traversal.computeExitTarget(t);
	}

	@Override
	public void completeRemoteArrival(Entity entity, Traversive t)
	{
		traversal.completeRemoteArrival(entity, t);
	}

	@Override
	public boolean canCompleteDeparture(Entity entity, Traversive traversive)
	{
		return traversal.canCompleteDeparture(entity, traversive);
	}

	@Override
	public void confirmDeparture(Entity entity, Traversive t)
	{
		traversal.confirmDeparture(entity, t);
	}

	@Override
	public void rejectDeparture(Entity entity, Traversive t)
	{
		traversal.rejectDeparture(entity, t);
	}

	@Override
	public void rejectRemoteArrival(Entity entity, Traversive t)
	{
		traversal.rejectRemoteArrival(entity, t);
	}

	@Override
	public boolean canDepart(Entity entity)
	{
		return gate.canDepart(entity);
	}

	@Override
	public boolean canArrive(Entity entity)
	{
		return gate.canArrive(entity);
	}

	public boolean beginRtpTraversal(Entity entity, long nowMillis)
	{
		return rtp.beginTraversal(entity, nowMillis);
	}

	public boolean canContinueRtpTraversal(Entity entity)
	{
		return rtp.canContinueTraversal(entity);
	}

	public void cancelRtpTraversal(Entity entity)
	{
		rtp.cancelTraversal(entity);
	}

	public void completeRtpTraversal(Entity entity, Traversive traversive, PortalFrame targetFrame, Location target)
	{
		rtp.completeTraversal(entity, traversive, targetFrame, target);
	}

	public void startPlayerDepartureHold(Player player, Traversive traversive)
	{
		departureHold.startPlayerDepartureHold(player, traversive);
	}

	static boolean isTeleportCoolingDown(UUID entityId, long now)
	{
		return LocalPortalTransitRegistry.isTeleportCoolingDown(entityId, now);
	}

	static void markTeleportCooldown(UUID entityId, long now)
	{
		LocalPortalTransitRegistry.markTeleportCooldown(entityId, now);
	}

	public static boolean clearTeleportCooldown(UUID entityId)
	{
		return LocalPortalTransitRegistry.clearTeleportCooldown(entityId);
	}

	public static void markRefusedBounce(UUID entityId, UUID portalId)
	{
		LocalPortalTransitRegistry.markRefusedBounce(entityId, portalId);
	}

	static boolean markTeleportInFlight(UUID entityId, long now)
	{
		return LocalPortalTransitRegistry.markTeleportInFlight(entityId, now);
	}

	static boolean isTeleportInFlight(UUID entityId, long now)
	{
		return LocalPortalTransitRegistry.isTeleportInFlight(entityId, now);
	}

	public static boolean clearTeleportInFlight(UUID entityId)
	{
		return LocalPortalTransitRegistry.clearTeleportInFlight(entityId);
	}

	public static void latchReentry(UUID entityId, UUID portalId)
	{
		LocalPortalTransitRegistry.latchReentry(entityId, portalId);
	}

	public static boolean isReentryLatched(UUID entityId)
	{
		return LocalPortalTransitRegistry.isReentryLatched(entityId);
	}

	public static void clearReentryLatch(UUID entityId)
	{
		LocalPortalTransitRegistry.clearReentryLatch(entityId);
	}

	static boolean reentryLatchExpired(boolean rejected, long stampMillis, long now)
	{
		return LocalPortalTransitRegistry.reentryLatchExpired(rejected, stampMillis, now);
	}

	static boolean shouldReleaseReentryLatchOutsidePortal(boolean armed, long stampMillis, long now)
	{
		return LocalPortalTransitRegistry.shouldReleaseReentryLatchOutsidePortal(armed, stampMillis, now);
	}

	public static void latchReentryIfInsidePortal(Entity entity)
	{
		LocalPortalTransitRegistry.latchReentryIfInsidePortal(entity);
	}

	static Vector sourceRejectionPoint(Traversive traversive)
	{
		return LocalPortalTraversal.sourceRejectionPoint(traversive);
	}

	static boolean withinDepartureCommitmentRadius(double distanceSquared)
	{
		return LocalPortalTraversal.withinDepartureCommitmentRadius(distanceSquared);
	}

	static Vector sourceRejectionVelocity(Traversive traversive)
	{
		return LocalPortalTraversal.sourceRejectionVelocity(traversive);
	}

	static ProjectionMode resolveProjectionMode(JSONObject j)
	{
		return LocalPortalSettings.resolveProjectionMode(j);
	}

	static boolean resolveMirrorMode(JSONObject j)
	{
		return LocalPortalSettings.resolveMirrorMode(j);
	}

	static MirrorRotation resolveMirrorRotation(JSONObject j)
	{
		return LocalPortalSettings.resolveMirrorRotation(j);
	}

	static String sanitizePermissionName(String name)
	{
		return LocalPortalSettings.sanitizePermissionName(name);
	}

	static UUID resolveOptionalUuid(String value)
	{
		return LocalPortalPersistence.resolveOptionalUuid(value);
	}

	@Override
	public ITunnel getTunnel()
	{
		return linking.getTunnel();
	}

	@Override
	public void setDestination(IPortal portal)
	{
		linking.setDestination(portal);
	}

	@Override
	public void linkRemote(String serverName, UUID portalId)
	{
		linking.linkRemote(serverName, portalId);
	}

	@Override
	public void destroy()
	{
		linking.destroy();
	}

	@Override
	public boolean isDestroyed()
	{
		return linking.isDestroyed();
	}

	@Override
	public boolean hasTunnel()
	{
		return linking.hasTunnel();
	}

	@Override
	public void unlink()
	{
		linking.unlink();
	}

	@Override
	public UUID getDimensionalCounterpartId()
	{
		return linking.getDimensionalCounterpartId();
	}

	@Override
	public void setDimensionalCounterpartId(UUID counterpartId)
	{
		linking.setDimensionalCounterpartId(counterpartId);
	}

	@Override
	public DimensionalPortalKind getDimensionalPortalKind()
	{
		return linking.getDimensionalPortalKind();
	}

	@Override
	public void setDimensionalPortalKind(DimensionalPortalKind kind)
	{
		linking.setDimensionalPortalKind(kind);
	}

	@Override
	public void uiOpenPortalMenu(Player p)
	{
		menus.uiOpenPortalMenu(p);
	}

	@Override
	public Window uiCreatePortalMenu(Player p)
	{
		return menus.uiCreatePortalMenu(p);
	}

	@Override
	public void uiChooseMode(Player p)
	{
		menus.uiChooseMode(p);
	}

	@Override
	public void uiChooseDestination(Player p)
	{
		menus.uiChooseDestination(p);
	}

	@Override
	public void uiChangeName(Player p)
	{
		menus.uiChangeName(p);
	}

	@Override
	public void uiChangeDirection(Player p)
	{
		prompts.uiChangeDirection(p);
	}

	public void refreshOpenMenus()
	{
		menus.refreshOpenMenus();
	}

	public boolean applySurfaceSkinFromInteraction(Player player, String skin)
	{
		return menus.cosmetics().applySurfaceSkinFromInteraction(player, skin);
	}

	public boolean clearSurfaceSkinFromInteraction(Player player)
	{
		return menus.cosmetics().clearSurfaceSkinFromInteraction(player);
	}

	@EventHandler
	public void on(PlayerInteractEvent e)
	{
		prompts.handleInteract(e);
	}

	@Override
	public String getRouter(boolean dark)
	{
		return getRouter(dark, null);
	}

	@Override
	public String getRouter(boolean dark, IPortal source)
	{
		return menus.text().router(dark, source);
	}

	@Override
	public boolean isGateway()
	{
		return getType().equals(PortalType.GATEWAY);
	}

	@Override
	public boolean supportsProjections()
	{
		return true;
	}

	@Override
	public boolean isProjecting()
	{
		return settings.isProjecting();
	}

	@Override
	public ProjectionMode getProjectionMode()
	{
		return settings.getProjectionMode();
	}

	@Override
	public void setProjectionMode(ProjectionMode mode)
	{
		settings.setProjectionMode(mode);
	}

	@Override
	public boolean isMirrorMode()
	{
		return settings.isMirrorMode();
	}

	@Override
	public void setMirrorMode(boolean mirrorMode)
	{
		settings.setMirrorMode(mirrorMode);
	}

	@Override
	public MirrorRotation getMirrorRotation()
	{
		return settings.getMirrorRotation();
	}

	@Override
	public void setMirrorRotation(MirrorRotation rotation)
	{
		settings.setMirrorRotation(rotation);
	}

	@Override
	public PortalPermissionMode getPermissionMode()
	{
		return settings.getPermissionMode();
	}

	@Override
	public void setPermissionMode(PortalPermissionMode mode)
	{
		settings.setPermissionMode(mode);
	}

	@Override
	public String getPermissionNode()
	{
		return settings.getPermissionNode();
	}

	@Override
	public boolean isOutgoingTraversalsEnabled()
	{
		return settings.isOutgoingTraversalsEnabled();
	}

	@Override
	public void setOutgoingTraversalsEnabled(boolean enabled)
	{
		settings.setOutgoingTraversalsEnabled(enabled);
	}

	@Override
	public boolean isIncomingTraversalsEnabled()
	{
		return settings.isIncomingTraversalsEnabled();
	}

	@Override
	public void setIncomingTraversalsEnabled(boolean enabled)
	{
		settings.setIncomingTraversalsEnabled(enabled);
	}

	@Override
	public int getNetworkViewDepth()
	{
		return settings.getNetworkViewDepth();
	}

	@Override
	public void setNetworkViewDepth(int depth)
	{
		settings.setNetworkViewDepth(depth);
	}

	@Override
	public int getNetworkViewLateralPad()
	{
		return settings.getNetworkViewLateralPad();
	}

	@Override
	public void setNetworkViewLateralPad(int lateralPad)
	{
		settings.setNetworkViewLateralPad(lateralPad);
	}

	@Override
	public int getNetworkViewHeartbeatTicks()
	{
		return settings.getNetworkViewHeartbeatTicks();
	}

	@Override
	public void setNetworkViewHeartbeatTicks(int ticks)
	{
		settings.setNetworkViewHeartbeatTicks(ticks);
	}

	@Override
	public int getNetworkViewEntityIntervalTicks()
	{
		return settings.getNetworkViewEntityIntervalTicks();
	}

	@Override
	public void setNetworkViewEntityIntervalTicks(int ticks)
	{
		settings.setNetworkViewEntityIntervalTicks(ticks);
	}

	@Override
	public int getNetworkViewUnsubscribeGraceSeconds()
	{
		return settings.getNetworkViewUnsubscribeGraceSeconds();
	}

	@Override
	public void setNetworkViewUnsubscribeGraceSeconds(int seconds)
	{
		settings.setNetworkViewUnsubscribeGraceSeconds(seconds);
	}

	@Override
	public String getNetworkViewFallbackBlock()
	{
		return settings.getNetworkViewFallbackBlock();
	}

	@Override
	public void setNetworkViewFallbackBlock(String blockState)
	{
		settings.setNetworkViewFallbackBlock(blockState);
	}

	@Override
	public boolean isBlackoutBackground()
	{
		return settings.isBlackoutBackground();
	}

	@Override
	public void setBlackoutBackground(boolean enabled)
	{
		settings.setBlackoutBackground(enabled);
	}

	@Override
	public BlackoutColor getBlackoutColor()
	{
		return settings.getBlackoutColor();
	}

	@Override
	public void setBlackoutColor(BlackoutColor color)
	{
		settings.setBlackoutColor(color);
	}

	@Override
	public int getActivationRange()
	{
		return settings.getActivationRange();
	}

	@Override
	public void setActivationRange(int rangeBlocks)
	{
		settings.setActivationRange(rangeBlocks);
	}

	@Override
	public double getEffectiveActivationRange()
	{
		return settings.getEffectiveActivationRange();
	}

	@Override
	public ProjectionRenderMode getRenderMode()
	{
		return settings.getRenderMode();
	}

	@Override
	public void setRenderMode(ProjectionRenderMode mode)
	{
		settings.setRenderMode(mode);
	}

	@Override
	public AmbientParticleStyle getAmbientStyle()
	{
		return settings.getAmbientStyle();
	}

	@Override
	public void setAmbientStyle(AmbientParticleStyle style)
	{
		settings.setAmbientStyle(style);
	}

	@Override
	public int getAmbientColor()
	{
		return settings.getAmbientColor();
	}

	@Override
	public void setAmbientColor(int color)
	{
		settings.setAmbientColor(color);
	}

	@Override
	public String getSurfaceSkin()
	{
		return settings.getSurfaceSkin();
	}

	@Override
	public void setSurfaceSkin(String skin)
	{
		settings.setSurfaceSkin(skin);
	}

	@Override
	public int getSurfaceThickness()
	{
		return settings.getSurfaceThickness();
	}

	@Override
	public void setSurfaceThickness(int thicknessCentiblocks)
	{
		settings.setSurfaceThickness(thicknessCentiblocks);
	}

	public boolean isSettingsSyncEnabled()
	{
		return settings.isSettingsSyncEnabled();
	}

	public void setSettingsSyncEnabled(boolean enabled)
	{
		settings.setSettingsSyncEnabled(enabled);
	}

	@Override
	public AxisAlignedBB getView()
	{
		return gate.getView();
	}

	@Override
	public UUID getOwner()
	{
		return owner;
	}

	@Override
	public void setOwner(UUID owner)
	{
		this.owner = owner;
	}

	@Override
	public boolean isSelfOwned()
	{
		return getOwner().equals(getId());
	}

	@Override
	public void setSelfOwned()
	{
		setOwner(getId());
	}

	@Override
	public boolean isRemote()
	{
		return false;
	}

	@Override
	public World getWorld()
	{
		return getStructure().getWorld();
	}

	@Override
	public Location getCenter()
	{
		return getStructure().getCenter();
	}

	@Override
	public AxisAlignedBB getArea()
	{
		return getStructure().getArea();
	}

	@Override
	public void save()
	{
		persistence.save();
	}

	@Override
	public boolean needsSaving()
	{
		return persistence.needsSaving();
	}

	@Override
	public void saveNow() throws IOException
	{
		persistence.saveNow();
	}

	@Override
	public void willSave()
	{
		persistence.willSave();
	}

	@Override
	public void setName(String name)
	{
		super.setName(name);
		save();
	}

	@Override
	public void deleteData()
	{
		persistence.deleteData();
	}
}
