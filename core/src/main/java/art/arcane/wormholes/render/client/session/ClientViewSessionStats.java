package art.arcane.wormholes.render.client.session;

import java.util.UUID;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ClientViewSessionState;

public record ClientViewSessionStats(UUID playerId,
                                     int sessionId,
                                     ClientViewSessionState state,
                                     long caps,
                                     int attended,
                                     long framesSent,
                                     long bytesSent,
                                     long groupsSent,
                                     int outstandingGroups,
                                     long ackedGroups,
                                     long ackRttMicros,
                                     long appliedCells,
                                     long c2sAdmitted,
                                     long c2sDropped,
                                     long c2sStale,
                                     long staleBrickMisses,
                                     long lateSwitches,
                                     ClientViewMessage.ViewStats viewStats) {
}
