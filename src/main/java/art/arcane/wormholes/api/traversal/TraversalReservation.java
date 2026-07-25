package art.arcane.wormholes.api.traversal;

import java.util.Objects;

public record TraversalReservation(TraversalReservationStatus status, TraversalReceipt receipt, String reason) {
    public TraversalReservation {
        Objects.requireNonNull(status, "status");
        reason = TraversalText.sanitize(reason);

        if (status == TraversalReservationStatus.RESERVED && receipt == null) {
            throw new IllegalArgumentException("a RESERVED reservation requires a receipt");
        }
    }

    public static TraversalReservation reserved(TraversalReceipt receipt) {
        return new TraversalReservation(TraversalReservationStatus.RESERVED,
            Objects.requireNonNull(receipt, "receipt"), "");
    }

    public static TraversalReservation failed(String reason) {
        return new TraversalReservation(TraversalReservationStatus.FAILED, null, reason);
    }

    public boolean reserved() {
        return status == TraversalReservationStatus.RESERVED;
    }
}
