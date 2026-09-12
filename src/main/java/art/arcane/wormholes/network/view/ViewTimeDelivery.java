package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireCapability;
import art.arcane.wormholes.network.WireMessage;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class ViewTimeDelivery {
    private final ViewSessionRegistry registry;
    private final AtomicLong sendCount = new AtomicLong();

    ViewTimeDelivery(ViewSessionRegistry registry) {
        this.registry = registry;
    }

    long sendCount() {
        return sendCount.get();
    }

    void queue(ViewSession session, String peerName, int skyDarken) {
        ViewServer.TimeDeliveryState state = session.timeDeliveryStates.get(peerName);
        if (state == null) {
            return;
        }
        state.updateDesired(skyDarken);
        start(session, peerName, state);
    }

    void queue(ViewSession session, String peerName, int skyDarken, boolean storm, boolean thunder) {
        ViewServer.TimeDeliveryState state = session.timeDeliveryStates.get(peerName);
        if (state == null) {
            return;
        }
        state.updateDesired(skyDarken, storm, thunder);
        start(session, peerName, state);
    }

    void retryPending(ViewSession session) {
        for (Map.Entry<String, ViewServer.TimeDeliveryState> entry : session.timeDeliveryStates.entrySet()) {
            ViewServer.TimeDeliveryState state = entry.getValue();
            if (state.needsDelivery() || weatherPending(entry.getKey(), state)) {
                start(session, entry.getKey(), state);
            }
        }
    }

    void start(ViewSession session, String peerName, ViewServer.TimeDeliveryState state) {
        if (state.tryStartDelivery()) {
            attempt(session, peerName, state);
        }
    }

    private void attempt(ViewSession session, String peerName, ViewServer.TimeDeliveryState state) {
        if (!isActive(session, peerName, state)) {
            state.finishDelivery();
            return;
        }
        if (!state.needsDelivery() && !weatherPending(peerName, state)) {
            finish(session, peerName, state);
            return;
        }
        NetworkManager network = registry.network();
        if (state.needsDelivery()) {
            int skyDarken = state.desiredSkyDarken();
            if (network.send(peerName, new WireMessage.ViewTime(session.portalId, skyDarken))) {
                state.markAccepted(skyDarken);
                sendCount.incrementAndGet();
            }
        }
        if (weatherPending(peerName, state)) {
            boolean storm = state.desiredStorm();
            boolean thunder = state.desiredThunder();
            if (network.send(peerName, new WireMessage.ViewWeather(session.portalId, storm, thunder))) {
                state.markWeatherAccepted(storm, thunder);
            }
        }
        if (!state.needsDelivery() && !weatherPending(peerName, state)) {
            finish(session, peerName, state);
            return;
        }
        boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
            () -> attempt(session, peerName, state), ViewServer.VIEW_TIME_RETRY_DELAY_TICKS);
        if (!scheduled) {
            state.finishDelivery();
        }
    }

    private boolean weatherPending(String peerName, ViewServer.TimeDeliveryState state) {
        return state.needsWeatherDelivery() && registry.network().peerSupports(peerName, WireCapability.VIEW_ATMOSPHERE);
    }

    private void finish(ViewSession session, String peerName, ViewServer.TimeDeliveryState state) {
        state.finishDelivery();
        if (isActive(session, peerName, state) && (state.needsDelivery() || weatherPending(peerName, state))) {
            start(session, peerName, state);
        }
    }

    private boolean isActive(ViewSession session, String peerName, ViewServer.TimeDeliveryState state) {
        return registry.isSessionPeerActive(session, peerName) && session.timeDeliveryStates.get(peerName) == state;
    }
}
