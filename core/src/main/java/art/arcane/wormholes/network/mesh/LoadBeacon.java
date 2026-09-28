package art.arcane.wormholes.network.mesh;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * One server's load sample, sent to every LOAD_BEACON-capable peer on the beacon cadence. Headroom
 * is {@code max - online - reserved}; {@code reserved} counts inbound handoff admissions that have
 * not arrived yet so two gateways cannot both fill the last slot.
 */
public record LoadBeacon(int online, int max, int reserved, double tps, double msptP95, boolean drain, long capabilities, long sentAtMillis) {
    public void write(DataOutputStream out) throws IOException {
        out.writeInt(online);
        out.writeInt(max);
        out.writeInt(reserved);
        out.writeDouble(tps);
        out.writeDouble(msptP95);
        out.writeBoolean(drain);
        out.writeLong(capabilities);
        out.writeLong(sentAtMillis);
    }

    public static LoadBeacon read(DataInputStream in) throws IOException {
        int online = in.readInt();
        int max = in.readInt();
        int reserved = in.readInt();
        double tps = in.readDouble();
        double msptP95 = in.readDouble();
        boolean drain = in.readBoolean();
        long capabilities = in.readLong();
        long sentAtMillis = in.readLong();
        if (online < 0 || reserved < 0 || max < 0) {
            throw new IOException("Invalid load beacon counts");
        }
        return new LoadBeacon(online, max, reserved, tps, msptP95, drain, capabilities, sentAtMillis);
    }

    /** Free player slots after online players and pending admissions; unlimited (max 0) reports Integer.MAX_VALUE. */
    public int headroom() {
        if (max <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, max - online - reserved);
    }
}
