package art.arcane.wormholes.menu.rtp;

import java.util.Objects;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;

public record RtpOutcome(Status status, TextKey notice, MessageArgs arguments) {
    public RtpOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(arguments, "arguments");
    }

    public static RtpOutcome applied(TextKey notice) {
        return new RtpOutcome(Status.APPLIED, notice, MessageArgs.empty());
    }

    public static RtpOutcome rejected(TextKey notice, MessageArgs arguments) {
        return new RtpOutcome(Status.REJECTED, notice, arguments);
    }

    public static RtpOutcome stale() {
        return new RtpOutcome(Status.STALE, WormholesMessages.PORTAL_RTP_EDITOR_REFRESHED, MessageArgs.empty());
    }

    public static RtpOutcome notRtp() {
        return new RtpOutcome(Status.NOT_RTP, WormholesMessages.PORTAL_NOT_RTP, MessageArgs.empty());
    }

    public static RtpOutcome denied() {
        return new RtpOutcome(Status.DENIED, WormholesMessages.PORTAL_EDIT_DENIED, MessageArgs.empty());
    }

    public enum Status {
        APPLIED,
        REJECTED,
        STALE,
        NOT_RTP,
        DENIED
    }
}
