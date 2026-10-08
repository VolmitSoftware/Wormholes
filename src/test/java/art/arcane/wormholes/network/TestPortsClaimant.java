package art.arcane.wormholes.network;

import java.io.IOException;

public final class TestPortsClaimant {
    private TestPortsClaimant() {
    }

    public static void main(String[] args) throws IOException {
        System.out.println(TestPorts.free());
        System.out.flush();
        while (System.in.read() >= 0) {
            continue;
        }
    }
}
