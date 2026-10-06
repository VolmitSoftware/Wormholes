package art.arcane.wormholes.localization;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.render.client.session.ClientViewSessionStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Function;

public final class ClientViewReplies {
    private ClientViewReplies() {
    }

    public static List<Reply> status(boolean runtimeEnabled, boolean configured, List<ClientViewSessionStats> sessions,
                                     Function<UUID, String> names) {
        List<Reply> replies = new ArrayList<>(sessions.size() + 1);
        replies.add(new Reply(ClientViewMessages.HEADER, arguments(
            MessageArgument.untrusted("state", onOff(runtimeEnabled)),
            MessageArgument.untrusted("mode", onOff(configured)),
            MessageArgument.untrusted("count", Integer.valueOf(sessions.size())))));
        if (sessions.isEmpty()) {
            replies.add(new Reply(ClientViewMessages.NONE, MessageArgs.empty()));
            return replies;
        }
        for (ClientViewSessionStats stats : sessions) {
            String name = names.apply(stats.playerId());
            replies.add(new Reply(ClientViewMessages.ROW, arguments(
                MessageArgument.untrusted("name", name == null ? stats.playerId().toString() : name),
                MessageArgument.untrusted("state", stats.state().name()),
                MessageArgument.untrusted("value", describe(stats)))));
        }
        return replies;
    }

    public static Reply enabled(boolean configured) {
        return new Reply(ClientViewMessages.ENABLED, arguments(MessageArgument.untrusted("mode", onOff(configured))));
    }

    public static Reply disabled() {
        return new Reply(ClientViewMessages.DISABLED, MessageArgs.empty());
    }

    public static Reply reset(String name, boolean restarted) {
        return new Reply(restarted ? ClientViewMessages.RESET : ClientViewMessages.NO_SESSION, name(name));
    }

    public static Reply playerMissing(String name) {
        return new Reply(ClientViewMessages.PLAYER_MISSING, name(name));
    }

    static String describe(ClientViewSessionStats stats) {
        StringJoiner caps = new StringJoiner(",");
        for (ViewStreamCapability capability : ViewStreamCapability.decode(stats.caps())) {
            caps.add(capability.name().toLowerCase(Locale.ROOT));
        }
        StringBuilder line = new StringBuilder(160);
        line.append("caps ").append(stats.caps() == 0L ? "-" : caps.toString())
            .append(" portals ").append(stats.attended())
            .append(" frames ").append(stats.framesSent())
            .append(" sent ").append(String.format(Locale.ROOT, "%.1f", stats.bytesSent() / 1024.0D)).append(" KiB")
            .append(" unacked ").append(stats.outstandingGroups())
            .append(" rtt ").append(stats.ackRttMicros() / 1000L).append("ms")
            .append(" cells ").append(stats.appliedCells());
        ClientViewMessage.ViewStats view = stats.viewStats();
        if (view != null) {
            line.append(" plate ").append(view.plateMb()).append("MB")
                .append(" sweep ").append(view.sweepMicrosP50()).append("us")
                .append(" apply ").append(view.applyMicrosP50()).append("us");
        }
        if (stats.c2sDropped() > 0L) {
            line.append(" dropped ").append(stats.c2sDropped());
        }
        if (stats.c2sStale() > 0L) {
            line.append(" stale ").append(stats.c2sStale());
        }
        return line.toString();
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static MessageArgs name(String name) {
        return arguments(MessageArgument.untrusted("name", name));
    }

    private static MessageArgs arguments(MessageArgument... values) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (MessageArgument value : values) {
            builder.add(value);
        }
        return builder.build();
    }

    public record Reply(TextKey key, MessageArgs arguments) {
    }
}
