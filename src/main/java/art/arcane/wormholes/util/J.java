package art.arcane.wormholes.util;

import art.arcane.volmlib.util.scheduling.SchedulerBridge;

public final class J {
    private J() {
    }

    public static void s(Runnable r) {
        SchedulerBridge.scheduleSync(r);
    }

    public static void s(Runnable r, int delay) {
        SchedulerBridge.scheduleSync(r, delay);
    }

    public static int sr(Runnable r, int interval) {
        return SchedulerBridge.scheduleSyncRepeating(r, interval);
    }

    public static int ar(Runnable r, int interval) {
        return SchedulerBridge.scheduleAsyncRepeating(r, interval);
    }

    public static void csr(int id) {
        SchedulerBridge.cancel(id);
    }
}
