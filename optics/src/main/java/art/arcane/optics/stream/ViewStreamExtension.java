package art.arcane.optics.stream;

import java.util.List;

public interface ViewStreamExtension<X> {
    int firstId();

    int lastId();

    boolean serverbound(int id);

    boolean clientbound(int id);

    Class<X> type();

    String name(int id);

    int id(X message);

    void encode(X message, ViewStreamWriter out) throws ViewStreamProtocolException;

    X decode(int id, ViewStreamReader in) throws ViewStreamProtocolException;

    long capabilities();

    default long requires(long capability) {
        return ViewStreamCapability.NONE;
    }

    default List<X> coalesce(List<X> messages) {
        return messages;
    }

    default ViewStreamMessage.Extension wrap(X message) {
        return new ViewStreamMessage.Extension(id(message), message);
    }
}
