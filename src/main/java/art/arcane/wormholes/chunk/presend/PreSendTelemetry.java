package art.arcane.wormholes.chunk.presend;

import org.bukkit.entity.Player;

import art.arcane.wormholes.Wormholes;

/** One verbose line per traversal pre-send: what happened, how many chunks went out, how long it took. */
public final class PreSendTelemetry {
    private PreSendTelemetry() {
    }

    public static void record(Player player, ChunkPreSendOutcome outcome, int sentChunks, int plannedChunks, long elapsedNanos) {
        Wormholes.v(() -> "[presend] " + (player == null ? "traveler" : player.getName()) + " " + outcome
            + " chunks=" + sentChunks + "/" + plannedChunks + " micros=" + (elapsedNanos / 1_000L));
    }
}
