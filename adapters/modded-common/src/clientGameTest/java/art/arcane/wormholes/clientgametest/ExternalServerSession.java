package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

final class ExternalServerSession implements AutoCloseable {
    private static final String SERVER = "wormholes.clientgametest.server";
    private static final String USERNAME = "wormholes.clientgametest.username";
    private static final int JOIN_TIMEOUT_TICKS = 1200;
    private static final int REPLY_TIMEOUT_TICKS = 200;

    private final TestClient client;
    private final String address;

    private ExternalServerSession(TestClient client, String address) {
        this.client = client;
        this.address = address;
    }

    static String configuredAddress() {
        return System.getProperty(SERVER, "").trim();
    }

    static ExternalServerSession connect(TestClient client, String address) {
        if (address.isEmpty()) {
            throw new IllegalArgumentException("An external server address is required");
        }
        client.runOnClient(minecraft -> ConnectScreen.startConnecting(ClientGui.screen(minecraft), minecraft, ServerAddress.parseString(address),
            new ServerData("Wormholes client gametest", address, ServerData.Type.OTHER), false, null));
        ExternalServerSession session = new ExternalServerSession(client, address);
        try {
            session.awaitJoin();
        } catch (RuntimeException | Error failure) {
            try {
                session.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return session;
    }

    String command(String command, String reply) {
        return QaReplies.command(client, command, reply, REPLY_TIMEOUT_TICKS);
    }

    String command(String command, String reply, Consumer<Minecraft> sameTick) {
        return QaReplies.command(client, command, reply, REPLY_TIMEOUT_TICKS, sameTick);
    }

    @Override
    public void close() {
        client.runOnClient(ExternalServerSession::disconnect);
        client.waitFor(minecraft -> minecraft.level == null, JOIN_TIMEOUT_TICKS);
        client.waitTicks(2);
        client.runOnClient(minecraft -> ClientGui.setScreen(minecraft, new TitleScreen()));
        client.waitFor(minecraft -> ClientGui.screen(minecraft) instanceof TitleScreen, JOIN_TIMEOUT_TICKS);
    }

    private void awaitJoin() {
        for (int tick = 0; tick < JOIN_TIMEOUT_TICKS; tick++) {
            String disconnected = client.computeOnClient(minecraft -> ClientGui.screen(minecraft) instanceof DisconnectedScreen screen
                ? screen.getNarrationMessage().getString() : null);
            if (disconnected != null) {
                throw new AssertionError("disconnected while joining " + address + ": " + disconnected);
            }
            if (client.computeOnClient(ExternalServerSession::joined)) {
                verifyUsername();
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("did not join " + address + " within " + JOIN_TIMEOUT_TICKS + " ticks");
    }

    private void verifyUsername() {
        String expected = System.getProperty(USERNAME, "").trim();
        if (expected.isEmpty()) {
            return;
        }
        String actual = client.computeOnClient(minecraft -> minecraft.player.getName().getString());
        if (!expected.equals(actual)) {
            throw new AssertionError("joined " + address + " as " + actual + " instead of " + expected);
        }
    }

    private static boolean joined(Minecraft minecraft) {
        return minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null
            && !(ClientGui.screen(minecraft) instanceof LevelLoadingScreen);
    }

    private static void disconnect(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        minecraft.level.disconnect(Component.literal("Disconnecting"));
        minecraft.disconnectWithSavingScreen();
    }
}
