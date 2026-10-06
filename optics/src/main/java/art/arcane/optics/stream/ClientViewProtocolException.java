package art.arcane.optics.stream;

import java.io.IOException;

public final class ClientViewProtocolException extends IOException {
    public ClientViewProtocolException(String message) {
        super(message);
    }

    public ClientViewProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
