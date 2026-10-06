package art.arcane.optics.state;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class StateProperties {
    private static final String[] NONE = new String[0];
    public static final StateProperties EMPTY = new StateProperties(NONE, NONE);

    private final String[] names;
    private final String[] values;

    private StateProperties(String[] names, String[] values) {
        this.names = names;
        this.values = values;
    }

    public static StateProperties of(Map<String, String> values) {
        String[] names = values.keySet().toArray(NONE);
        for (String name : names) {
            Objects.requireNonNull(name, "property name");
        }
        Arrays.sort(names);
        String[] sorted = new String[names.length];
        for (int index = 0; index < names.length; index++) {
            sorted[index] = Objects.requireNonNull(values.get(names[index]), names[index]);
        }
        return new StateProperties(names, sorted);
    }

    public int size() {
        return names.length;
    }

    public String get(String name) {
        int index = Arrays.binarySearch(names, name);
        return index >= 0 ? values[index] : null;
    }

    public StateProperties with(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        int index = Arrays.binarySearch(names, name);
        if (index >= 0) {
            if (values[index].equals(value)) {
                return this;
            }
            String[] replaced = values.clone();
            replaced[index] = value;
            return new StateProperties(names, replaced);
        }
        int insert = -index - 1;
        String[] grownNames = new String[names.length + 1];
        String[] grownValues = new String[values.length + 1];
        System.arraycopy(names, 0, grownNames, 0, insert);
        System.arraycopy(values, 0, grownValues, 0, insert);
        grownNames[insert] = name;
        grownValues[insert] = value;
        System.arraycopy(names, insert, grownNames, insert + 1, names.length - insert);
        System.arraycopy(values, insert, grownValues, insert + 1, values.length - insert);
        return new StateProperties(grownNames, grownValues);
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<String, String>(names.length * 2);
        for (int index = 0; index < names.length; index++) {
            map.put(names[index], values[index]);
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateProperties properties && Arrays.equals(names, properties.names) && Arrays.equals(values, properties.values);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(names) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < names.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(names[index]).append('=').append(values[index]);
        }
        return builder.append(']').toString();
    }
}
