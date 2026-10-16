package art.arcane.wormholes.menu;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class FakeMenuViewer implements MenuViewer {
    public final Set<String> permissions = new HashSet<>();
    private final UUID id;
    private final String name;
    private final MenuStrings strings = new FakeStrings();
    public boolean administrator;
    public int stringLookups;

    public FakeMenuViewer(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean hasPermission(String node) {
        return administrator || permissions.contains(node);
    }

    @Override
    public boolean administrator() {
        return administrator;
    }

    @Override
    public MenuStrings strings() {
        stringLookups++;
        return strings;
    }
}
