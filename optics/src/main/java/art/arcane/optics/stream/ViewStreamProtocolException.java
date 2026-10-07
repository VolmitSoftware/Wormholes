package art.arcane.optics.stream;

import java.io.IOException;

public final class ViewStreamProtocolException extends IOException {
    public ViewStreamProtocolException(String message) {
        super(message);
    }

    public ViewStreamProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
