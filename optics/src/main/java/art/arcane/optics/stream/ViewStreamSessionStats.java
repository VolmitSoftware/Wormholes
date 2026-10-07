package art.arcane.optics.stream;

import java.util.UUID;

public record ViewStreamSessionStats(UUID playerId,
                                     int sessionId,
                                     ViewStreamSessionState state,
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
                                     ViewStreamMessage.ViewStats viewStats) {
}
