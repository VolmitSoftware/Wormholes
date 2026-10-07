package art.arcane.optics.stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

final class ViewStreamCodecFuzzTest {
    private static final int RANDOM_INPUTS = 10_000;
    private static final int MUTATED_INPUTS = 6_000;

    @Test
    void randomInputsOnlyEverThrowProtocolExceptions() {
        Random random = new Random(0x600DF00DL);
        int decoded = 0;
        for (int i = 0; i < RANDOM_INPUTS; i++) {
            byte[] payload = new byte[random.nextInt(i % 10 == 0 ? 4096 : 96)];
            random.nextBytes(payload);
            if (payload.length > 0 && random.nextBoolean()) {
                ViewStreamMessageType[] types = ViewStreamMessageType.values();
                payload[0] = (byte) types[random.nextInt(types.length)].id();
            }
            if (payload.length > 5 && random.nextInt(4) == 0) {
                payload[5] = (byte) (random.nextInt(8));
            }
            decoded += attempt(payload, random.nextLong());
        }
        assertTrue(decoded >= 0);
    }

    @Test
    void mutatedGoldenFramesOnlyEverThrowProtocolExceptions() throws ViewStreamProtocolException {
        Random random = new Random(0xBADC0FFEEL);
        List<byte[]> seeds = new ArrayList<byte[]>();
        List<Long> caps = new ArrayList<Long>();
        for (ViewStreamFixtures.Vector vector : ViewStreamFixtures.vectors()) {
            seeds.add(vector.clientbound()
                ? ViewStreamFixtures.CODEC.encodeS2C(vector.message(), vector.seq(), vector.flags(), random.nextBoolean())
                : ViewStreamFixtures.CODEC.encodeC2S(vector.message()));
            caps.add(vector.caps());
        }
        for (int i = 0; i < MUTATED_INPUTS; i++) {
            int pick = random.nextInt(seeds.size());
            byte[] base = seeds.get(pick);
            byte[] mutated;
            switch (random.nextInt(4)) {
                case 0 -> {
                    mutated = base.clone();
                    int flips = 1 + random.nextInt(4);
                    for (int f = 0; f < flips && mutated.length > 0; f++) {
                        mutated[random.nextInt(mutated.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                case 1 -> {
                    int length = random.nextInt(base.length + 1);
                    mutated = new byte[length];
                    System.arraycopy(base, 0, mutated, 0, length);
                }
                case 2 -> {
                    mutated = new byte[base.length + 1 + random.nextInt(64)];
                    System.arraycopy(base, 0, mutated, 0, base.length);
                    for (int b = base.length; b < mutated.length; b++) {
                        mutated[b] = (byte) random.nextInt(256);
                    }
                }
                default -> {
                    mutated = base.clone();
                    if (mutated.length > 6) {
                        int at = 6 + random.nextInt(mutated.length - 6);
                        mutated[at] = (byte) random.nextInt(256);
                    }
                }
            }
            attempt(mutated, caps.get(pick));
        }
    }

    private static int attempt(byte[] payload, long caps) {
        int decoded = 0;
        try {
            ViewStreamFixtures.CODEC.decodeS2C(payload, caps);
            decoded++;
        } catch (ViewStreamProtocolException expected) {
            decoded += 0;
        } catch (Throwable unexpected) {
            fail("S2C decode threw " + unexpected + " for " + payload.length + " bytes", unexpected);
        }
        try {
            ViewStreamFixtures.CODEC.decodeC2S(payload);
            decoded++;
        } catch (ViewStreamProtocolException expected) {
            decoded += 0;
        } catch (Throwable unexpected) {
            fail("C2S decode threw " + unexpected + " for " + payload.length + " bytes", unexpected);
        }
        return decoded;
    }
}
