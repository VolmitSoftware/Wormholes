package art.arcane.optics.stream;

@FunctionalInterface
public interface ViewStreamHooksFactory<O, B> {
    ViewStreamHooks<O> create(ViewStreamSession<O, B> session);
}
