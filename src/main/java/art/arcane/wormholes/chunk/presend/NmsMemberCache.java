package art.arcane.wormholes.chunk.presend;

final class NmsMemberCache<T> {
    private volatile Class<?> owner;
    private volatile boolean resolved;
    private volatile T member;

    interface Resolver<T> {
        T resolve(Class<?> owner);
    }

    T get(Class<?> type, Resolver<T> resolver) {
        if (type == null) {
            return null;
        }
        if (resolved && owner == type) {
            return member;
        }
        T found = resolver.resolve(type);
        member = found;
        owner = type;
        resolved = true;
        return found;
    }
}
