package art.arcane.optics.state;

import java.util.Map;

public final class StateProperties {
    private StateProperties() {
    }

    public static StateProperties of(Map<String, String> values) {
        throw new UnsupportedOperationException();
    }

    public String get(String name) {
        throw new UnsupportedOperationException();
    }

    public StateProperties with(String name, String value) {
        throw new UnsupportedOperationException();
    }

    public Map<String, String> asMap() {
        throw new UnsupportedOperationException();
    }
}
