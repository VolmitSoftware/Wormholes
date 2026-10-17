package art.arcane.wormholes.modded.client;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class WormholesDebugScreen {
    private final Supplier<WormholesDebugStatus> status;
    private final Supplier<List<String>> detail;
    private long summaryTick = Long.MIN_VALUE;
    private long detailTick = Long.MIN_VALUE;
    private WormholesDebugStatus lastStatus;
    private List<String> summaryLines = List.of();
    private List<String> detailLines = List.of();

    public WormholesDebugScreen(Supplier<WormholesDebugStatus> status, Supplier<List<String>> detail) {
        this.status = Objects.requireNonNull(status, "status");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public List<String> summary(long tick) {
        if (tick == summaryTick) {
            return summaryLines;
        }
        summaryTick = tick;
        WormholesDebugStatus next = status.get();
        if (!next.equals(lastStatus)) {
            lastStatus = next;
            summaryLines = WormholesDebugLines.summary(next);
        }
        return summaryLines;
    }

    public List<String> detail(long tick) {
        if (tick != detailTick) {
            detailTick = tick;
            detailLines = detail.get();
        }
        return detailLines;
    }
}
