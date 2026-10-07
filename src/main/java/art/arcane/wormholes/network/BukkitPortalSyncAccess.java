package art.arcane.wormholes.network;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.optics.math.Box;
import org.bukkit.Location;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public enum BukkitPortalSyncAccess implements PortalSyncAccess<ILocalPortal> {
    INSTANCE;

    public static PortalSyncService<ILocalPortal> create(NetworkManager network, Supplier<List<ILocalPortal>> portals,
                                                        Consumer<Runnable> dispatcher) {
        PortalSettingsApplyQueue<ILocalPortal> queue = new PortalSettingsApplyQueue<>(INSTANCE,
            new PortalSettingsApplyQueue.Dispatch<>(BukkitPortalSyncAccess::dispatchPortalRegion,
                (task, delayTicks) -> dispatchRetry(dispatcher, task, delayTicks), BukkitPortalSyncAccess::reportApplyFailure));
        return new PortalSyncService<>(network, new PortalSyncService.Options<>(portals, dispatcher,
            () -> Wormholes.remotePortalRegistry, INSTANCE, queue));
    }

    @Override
    public boolean supportsSettings(ILocalPortal portal) {
        return portal instanceof LocalPortal;
    }

    @Override
    public boolean settingsSyncEnabled(ILocalPortal portal) {
        return ((LocalPortal) portal).isSettingsSyncEnabled();
    }

    @Override
    public boolean receiverOnly(ILocalPortal portal) {
        return ((LocalPortal) portal).getDimensionalPortalKind().isReceiverOnly();
    }

    @Override
    public UUID counterpartId(ILocalPortal portal) {
        return ((LocalPortal) portal).getDimensionalCounterpartId();
    }

    @Override
    public boolean rtp(ILocalPortal portal) {
        return portal.getType() == PortalType.RTP;
    }

    @Override
    public boolean gateway(ILocalPortal portal) {
        return portal.isGateway();
    }

    @Override
    public UUID remoteDestinationId(ILocalPortal portal) {
        return portal.getTunnel() instanceof UniversalTunnel tunnel ? tunnel.getDestinationPortalId() : null;
    }

    @Override
    public Map<String, String> collectSettings(ILocalPortal portal) {
        LocalPortal local = (LocalPortal) portal;
        Map<String, String> settings = PortalSettingsCodec.collectSettings(local);
        local.extensions().collectSync(settings);
        return settings;
    }

    @Override
    public void applySettings(ILocalPortal portal, Map<String, String> settings) {
        LocalPortal local = (LocalPortal) portal;
        PortalSyncService.applyRemote(() -> {
            PortalSettingsCodec.applyToLocal(local, settings);
            local.extensions().applySync(settings);
        });
    }

    @Override
    public void refreshMenus(ILocalPortal portal) {
        ((LocalPortal) portal).refreshOpenMenus();
    }

    private static void dispatchRetry(Consumer<Runnable> globalDispatcher, Runnable task, long delayTicks) {
        long delayMillis = Math.multiplyExact(Math.max(1L, delayTicks), 50L);
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
            .execute(() -> globalDispatcher.accept(task));
    }

    private static boolean dispatchPortalRegion(ILocalPortal portal, Runnable task, Runnable retired) {
        Location center = portal.getCenter();
        if (center == null || center.getWorld() == null) {
            return false;
        }
        return BukkitRegionTaskProvider.run(
            center.getWorld(),
            center.getBlockX() >> 4,
            center.getBlockZ() >> 4,
            task,
            retired,
            0L);
    }

    @Override
    public UUID forwardLinkId(ILocalPortal source) {
        ITunnel tunnel = source.getTunnel();
        if (tunnel == null || tunnel instanceof UniversalTunnel) {
            return null;
        }
        return tunnel.getDestinationId();
    }

    private static void reportApplyFailure(String reason, ILocalPortal portal, Throwable failure) {
        WormholesTelemetry.countFailure(reason);
        UUID portalId = portal == null ? null : portal.getId();
        String message = "Inbound portal settings failed for portal " + portalId + ": " + reason;
        Wormholes active = Wormholes.instance;
        Logger logger = active == null ? Logger.getLogger("Wormholes") : active.getLogger();
        if (failure == null) {
            logger.warning(message);
            return;
        }
        logger.log(Level.WARNING, message, failure);
    }

    @Override
    public String linkedPeer(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (!(tunnel instanceof UniversalTunnel universal)) {
            return null;
        }
        return universal.getServerName();
    }

    @Override
    public boolean shareable(ILocalPortal portal) {
        return portal.isGateway()
            && portal.getStructure() != null
            && portal.getStructure().getWorld() != null
            && portal.getStructure().getArea() != null;
    }

    @Override
    public PortalInfo describe(ILocalPortal portal) {
        Frame frame = portal.getFrame();
        Box area = portal.getStructure().getArea();
        return new PortalInfo(
            portal.getId(),
            portal.getName(),
            WorldIdentity.serialize(portal.getStructure().getWorld()),
            portal.getType().name(),
            portal.isOpen(),
            frame.getNormal().name(),
            frame.getRight().name(),
            frame.getUp().name(),
            portal.getOrigin().x(),
            portal.getOrigin().y(),
            portal.getOrigin().z(),
            Math.min(area.getXa(), area.getXb()),
            Math.min(area.getYa(), area.getYb()),
            Math.min(area.getZa(), area.getZb()),
            Math.max(area.getXa(), area.getXb()),
            Math.max(area.getYa(), area.getYb()),
            Math.max(area.getZa(), area.getZb())
        );
    }
}
