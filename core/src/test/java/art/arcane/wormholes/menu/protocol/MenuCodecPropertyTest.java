package art.arcane.wormholes.menu.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.menu.MenuAction;
import art.arcane.wormholes.menu.MenuFixtures;
import art.arcane.wormholes.menu.MenuInteraction;
import art.arcane.wormholes.menu.MenuScreen;
import art.arcane.wormholes.menu.MenuValue;

final class MenuCodecPropertyTest {
    private static final long[] SEEDS = {0x57484D4E55L, 0x1D0C5L, 0x7EA7L};
    private static final int CASES = 200;

    @Test
    void randomScreensRoundTripToTheirWireForm() throws MenuProtocolException {
        for (long seed : SEEDS) {
            SplittableRandom random = new SplittableRandom(seed);
            for (int index = 0; index < CASES; index++) {
                MenuScreen screen = MenuFixtures.randomScreen(random);
                MenuServerMessage.Open open = new MenuServerMessage.Open(screen);
                assertEquals(new MenuServerMessage.Open(MenuCodec.wireScreen(screen)), MenuCodec.decodeServer(MenuCodec.encodeServer(open)),
                    "seed " + seed + " case " + index);
            }
        }
    }

    @Test
    void randomActionsRoundTrip() throws MenuProtocolException {
        MenuInteraction[] interactions = MenuInteraction.values();
        for (long seed : SEEDS) {
            SplittableRandom random = new SplittableRandom(seed);
            for (int index = 0; index < CASES; index++) {
                MenuValue value = switch (random.nextInt(4)) {
                    case 0 -> null;
                    case 1 -> MenuValue.index(random.nextInt());
                    case 2 -> MenuValue.number((random.nextDouble() - 0.5D) * 1.0E6D);
                    default -> MenuValue.text(MenuFixtures.randomText(random, 64));
                };
                MenuClientMessage.Action action = new MenuClientMessage.Action(new MenuAction(random.nextLong(),
                    "element-" + random.nextInt(300), interactions[random.nextInt(interactions.length)], value));
                assertEquals(action, MenuCodec.decodeClient(MenuCodec.encodeClient(action)), "seed " + seed + " case " + index);
            }
        }
    }

    @Test
    void mutatedFramesDecodeOrFailOnlyWithProtocolErrors() throws MenuProtocolException {
        for (long seed : SEEDS) {
            SplittableRandom random = new SplittableRandom(seed);
            for (int index = 0; index < CASES; index++) {
                byte[] server = MenuCodec.encodeServer(new MenuServerMessage.Open(MenuFixtures.randomScreen(random)));
                mutate(random, server);
                byte[] client = MenuCodec.encodeClient(new MenuClientMessage.Action(new MenuAction(random.nextLong(), "e" + index,
                    MenuInteraction.SET_TEXT, MenuValue.text(MenuFixtures.randomText(random, 20)))));
                mutate(random, client);
                assertDecodesOrRejects(() -> MenuCodec.decodeServer(server), "seed " + seed + " server case " + index);
                assertDecodesOrRejects(() -> MenuCodec.decodeClient(client), "seed " + seed + " client case " + index);
            }
        }
    }

    private static void mutate(SplittableRandom random, byte[] frame) {
        int mutations = 1 + random.nextInt(4);
        for (int mutation = 0; mutation < mutations; mutation++) {
            frame[random.nextInt(frame.length)] = (byte) random.nextInt(256);
        }
    }

    private static void assertDecodesOrRejects(Decode decode, String label) {
        try {
            decode.run();
        } catch (MenuProtocolException expected) {
            return;
        } catch (RuntimeException unexpected) {
            fail(label + " raised " + unexpected, unexpected);
        }
    }

    @FunctionalInterface
    private interface Decode {
        Object run() throws MenuProtocolException;
    }
}
