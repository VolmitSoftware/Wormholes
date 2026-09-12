package art.arcane.wormholes.door.view;

import art.arcane.volmlib.util.inventorygui.Window;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.MirrorRotation;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalSaveSnapshot;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * An {@link ILocalPortal} that only ever gets projected.
 *
 * <p>The projector reads roughly thirty getters off a portal and never writes one, so every mutator
 * here is a no-op and every render knob answers from {@code [doors]} instead of per-portal state.
 * Apertures live outside the portal manager, so nothing that saves, links, or moves entities is ever
 * reached; those methods refuse rather than pretend.</p>
 *
 * <p>Subclasses supply only what an aperture actually has: its cells, its frame, where its surface
 * sits, and whether it is open.</p>
 */
public abstract class AbstractApertureFacade implements ILocalPortal {
    /** Lateral padding around a one-block-wide aperture, in blocks. */
    public static final int VIEW_LATERAL_PAD = 4;
    public static final int VIEW_HEARTBEAT_TICKS = 60;
    public static final int VIEW_ENTITY_INTERVAL_TICKS = 10;
    public static final int VIEW_UNSUBSCRIBE_GRACE_SECONDS = 30;
    public static final String VIEW_FALLBACK_BLOCK = "minecraft:air";

    private static final DoorsConfig FALLBACK_DOORS = new DoorsConfig();

    private volatile boolean destroyed;

    protected static DoorsConfig doors() {
        WormholesSettings settings = Wormholes.settings;
        return settings == null ? FALLBACK_DOORS : settings.getDoors();
    }

    @Override
    public abstract art.arcane.wormholes.portal.PortalStructure getStructure();

    @Override
    public abstract PortalFrame getFrame();

    @Override
    public abstract Vector getOrigin();

    @Override
    public abstract org.bukkit.World getWorld();

    @Override
    public abstract UUID getId();

    @Override
    public abstract String getName();

    @Override
    public abstract boolean isOpen();

    @Override
    public AxisAlignedBB getView() {
        double range = getEffectiveActivationRange();
        AxisAlignedBB area = getStructure().getArea();
        return new AxisAlignedBB(
            area.min().add(new Vector(-range, -range, -range)),
            area.max().add(new Vector(range, range, range)));
    }

    @Override
    public AxisAlignedBB getArea() {
        return getStructure().getArea();
    }

    @Override
    public Location getCenter() {
        Vector origin = getOrigin();
        return new Location(getWorld(), origin.getX(), origin.getY(), origin.getZ());
    }

    @Override
    public Direction getDirection() {
        return getFrame().getNormal();
    }

    @Override
    public boolean supportsProjections() {
        return true;
    }

    @Override
    public boolean isProjecting() {
        return true;
    }

    @Override
    public boolean isMirrorMode() {
        return false;
    }

    @Override
    public boolean hasTunnel() {
        return false;
    }

    @Override
    public ITunnel getTunnel() {
        return null;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public void destroy() {
        destroyed = true;
    }

    @Override
    public int getNetworkViewDepth() {
        return doors().projectionDepthBlocks;
    }

    @Override
    public int getNetworkViewLateralPad() {
        return VIEW_LATERAL_PAD;
    }

    @Override
    public int getNetworkViewHeartbeatTicks() {
        return VIEW_HEARTBEAT_TICKS;
    }

    @Override
    public int getNetworkViewEntityIntervalTicks() {
        return VIEW_ENTITY_INTERVAL_TICKS;
    }

    @Override
    public int getNetworkViewUnsubscribeGraceSeconds() {
        return VIEW_UNSUBSCRIBE_GRACE_SECONDS;
    }

    @Override
    public String getNetworkViewFallbackBlock() {
        return VIEW_FALLBACK_BLOCK;
    }

    @Override
    public boolean isBlackoutBackground() {
        return false;
    }

    @Override
    public BlackoutColor getBlackoutColor() {
        return BlackoutColor.BLACK;
    }

    @Override
    public int getActivationRange() {
        return doors().projectionRange;
    }

    @Override
    public double getEffectiveActivationRange() {
        return getActivationRange();
    }

    @Override
    public ProjectionRenderMode getRenderMode() {
        return ProjectionRenderMode.VENTICULAR;
    }

    @Override
    public AmbientParticleStyle getAmbientStyle() {
        return AmbientParticleStyle.OFF;
    }

    @Override
    public int getAmbientColor() {
        return 0;
    }

    @Override
    public String getSurfaceSkin() {
        return "";
    }

    @Override
    public MirrorRotation getMirrorRotation() {
        return MirrorRotation.DEGREES_0;
    }

    @Override
    public ProjectionMode getProjectionMode() {
        return ProjectionMode.ON;
    }

    @Override
    public PortalType getType() {
        return PortalType.PORTAL;
    }

    @Override
    public PortalPermissionMode getPermissionMode() {
        return PortalPermissionMode.BLACKLIST;
    }

    @Override
    public String getPermissionNode() {
        return "";
    }

    @Override
    public DimensionalPortalKind getDimensionalPortalKind() {
        return DimensionalPortalKind.NONE;
    }

    @Override
    public UUID getDimensionalCounterpartId() {
        return null;
    }

    @Override
    public boolean isGateway() {
        return false;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public boolean isPublicLookLabel() {
        return false;
    }

    @Override
    public boolean isAmbientAttended() {
        return false;
    }

    @Override
    public boolean isOutgoingTraversalsEnabled() {
        return false;
    }

    @Override
    public boolean isIncomingTraversalsEnabled() {
        return false;
    }

    @Override
    public boolean needsSaving() {
        return false;
    }

    @Override
    public String getRouter(boolean dark) {
        return getName();
    }

    @Override
    public String getRouter(boolean dark, IPortal source) {
        return getName();
    }

    @Override
    public JSONObject toJSON() {
        return new JSONObject();
    }

    @Override
    public void loadJSON(JSONObject json) {
    }

    @Override
    public void saveJSON(JSONObject json) {
    }

    @Override
    public void save() {
    }

    @Override
    public PortalSaveSnapshot prepareSave() {
        return null;
    }

    @Override
    public void writeSave(PortalSaveSnapshot snapshot) {
    }

    @Override
    public void rejectSave() {
    }

    @Override
    public void saveNow() {
    }

    @Override
    public void deleteData() {
    }

    @Override
    public void update() {
    }

    @Override
    public void close() {
    }

    @Override
    public void open() {
    }

    @Override
    public void setOpen(boolean open) {
    }

    @Override
    public void setType(PortalType type) {
    }

    @Override
    public void setName(String name) {
    }

    @Override
    public void setDirection(Direction direction) {
    }

    @Override
    public void setFrame(PortalFrame frame) {
    }

    @Override
    public void setAmbientAttended(boolean attended) {
    }

    @Override
    public void setProjectionMode(ProjectionMode mode) {
    }

    @Override
    public void setMirrorMode(boolean mirrorMode) {
    }

    @Override
    public void setMirrorRotation(MirrorRotation rotation) {
    }

    @Override
    public void setPermissionMode(PortalPermissionMode mode) {
    }

    @Override
    public void setOutgoingTraversalsEnabled(boolean enabled) {
    }

    @Override
    public void setIncomingTraversalsEnabled(boolean enabled) {
    }

    @Override
    public void setNetworkViewDepth(int depth) {
    }

    @Override
    public void setNetworkViewLateralPad(int lateralPad) {
    }

    @Override
    public void setNetworkViewHeartbeatTicks(int ticks) {
    }

    @Override
    public void setNetworkViewEntityIntervalTicks(int ticks) {
    }

    @Override
    public void setNetworkViewUnsubscribeGraceSeconds(int seconds) {
    }

    @Override
    public void setNetworkViewFallbackBlock(String blockState) {
    }

    @Override
    public void setBlackoutBackground(boolean enabled) {
    }

    @Override
    public void setBlackoutColor(BlackoutColor color) {
    }

    @Override
    public void setActivationRange(int rangeBlocks) {
    }

    @Override
    public void setRenderMode(ProjectionRenderMode mode) {
    }

    @Override
    public void setAmbientStyle(AmbientParticleStyle style) {
    }

    @Override
    public void setAmbientColor(int color) {
    }

    @Override
    public void setSurfaceSkin(String skin) {
    }

    @Override
    public void setDimensionalCounterpartId(UUID counterpartId) {
    }

    @Override
    public void setDimensionalPortalKind(DimensionalPortalKind kind) {
    }

    @Override
    public boolean setDestination(IPortal portal) {
        return false;
    }

    @Override
    public boolean linkRemote(String serverName, UUID portalId) {
        return false;
    }

    @Override
    public void unlink() {
    }

    @Override
    public void onLooking(Player player, boolean holdingWand) {
    }

    @Override
    public void onWanded(Player player) {
    }

    @Override
    public boolean isLookingAt(Player player) {
        return false;
    }

    @Override
    public void receive(Traversive traversive) {
    }

    @Override
    public Location computeExitTarget(Traversive traversive) {
        return null;
    }

    @Override
    public void completeRemoteArrival(Entity entity, Traversive traversive) {
    }

    @Override
    public boolean canCompleteDeparture(Entity entity, Traversive traversive) {
        return false;
    }

    @Override
    public void confirmDeparture(Entity entity, Traversive traversive) {
    }

    @Override
    public void rejectRemoteArrival(Entity entity, Traversive traversive) {
    }

    @Override
    public void rejectDeparture(Entity entity, Traversive traversive) {
    }

    @Override
    public boolean canDepart(Entity entity) {
        return false;
    }

    @Override
    public boolean canArrive(Entity entity) {
        return false;
    }

    @Override
    public void uiOpenPortalMenu(Player player) {
    }

    @Override
    public Window uiCreatePortalMenu(Player player) {
        return null;
    }

    @Override
    public void uiChooseMode(Player player) {
    }

    @Override
    public void uiChooseDestination(Player player) {
    }

    @Override
    public void uiChangeName(Player player) {
    }

    @Override
    public void uiChangeDirection(Player player) {
    }
}
