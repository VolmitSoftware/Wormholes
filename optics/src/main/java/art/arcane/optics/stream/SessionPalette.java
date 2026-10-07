package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionPalette {
    public static final String AIR = "minecraft:air";
    public static final String OCCLUDED = "optics:occluded";
    public static final String BACKING = "optics:backing";
    private static final String DEFAULT_NAMESPACE = "minecraft:";

    private final ConcurrentHashMap<String, Integer> ids;
    private volatile String[] states;
    private volatile int size;

    public SessionPalette() {
        this.ids = new ConcurrentHashMap<String, Integer>(256);
        this.states = new String[256];
        this.size = 0;
        assign(AIR);
        assign(OCCLUDED);
        assign(BACKING);
    }

    public int id(String state) {
        String canonical = canonical(state);
        Integer known = ids.get(canonical);
        if (known != null) {
            return known;
        }
        return assign(canonical);
    }

    public int lookup(String state) {
        Integer known = ids.get(canonical(state));
        return known == null ? -1 : known;
    }

    public String state(int id) {
        String[] snapshot = states;
        if (id < 0 || id >= size || id >= snapshot.length) {
            return null;
        }
        return snapshot[id];
    }

    public int size() {
        return size;
    }

    public List<ViewStreamMessage.PaletteEntry> entries(int fromId, int toIdExclusive) {
        String[] snapshot = states;
        int end = Math.min(toIdExclusive, size);
        int start = Math.max(0, fromId);
        if (start >= end) {
            return List.of();
        }
        List<ViewStreamMessage.PaletteEntry> out = new ArrayList<ViewStreamMessage.PaletteEntry>(end - start);
        for (int id = start; id < end; id++) {
            out.add(new ViewStreamMessage.PaletteEntry(id, snapshot[id]));
        }
        return out;
    }

    public Cursor cursor() {
        return new Cursor(this);
    }

    public static boolean reserved(int id) {
        return id >= 0 && id < ViewStreamLimits.RESERVED_PALETTE_IDS;
    }

    public static String canonical(String state) {
        String trimmed = Objects.requireNonNull(state, "state").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("blank block state string");
        }
        int bracket = trimmed.indexOf('[');
        String name = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
        if (name.indexOf(':') < 0) {
            name = DEFAULT_NAMESPACE + name;
        }
        if (bracket < 0) {
            return name;
        }
        int close = trimmed.lastIndexOf(']');
        if (close < bracket) {
            throw new IllegalArgumentException("unterminated block state properties: " + state);
        }
        String body = trimmed.substring(bracket + 1, close).trim();
        if (body.isEmpty()) {
            return name;
        }
        String[] properties = body.split(",");
        for (int i = 0; i < properties.length; i++) {
            String property = properties[i].trim();
            int equals = property.indexOf('=');
            if (equals <= 0 || equals == property.length() - 1) {
                throw new IllegalArgumentException("malformed block state property '" + property + "' in " + state);
            }
            properties[i] = property.substring(0, equals).trim() + "=" + property.substring(equals + 1).trim();
        }
        Arrays.sort(properties);
        StringBuilder out = new StringBuilder(name.length() + body.length() + 2);
        out.append(name).append('[');
        for (int i = 0; i < properties.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(properties[i]);
        }
        return out.append(']').toString();
    }

    private synchronized int assign(String canonical) {
        Integer known = ids.get(canonical);
        if (known != null) {
            return known;
        }
        int id = size;
        if (id >= ViewStreamLimits.MAX_SESSION_PALETTE_SIZE) {
            throw new IllegalStateException("session palette exhausted at " + id + " states");
        }
        String[] current = states;
        if (id >= current.length) {
            String[] grown = Arrays.copyOf(current, current.length * 2);
            grown[id] = canonical;
            states = grown;
        } else {
            current[id] = canonical;
        }
        size = id + 1;
        ids.put(canonical, id);
        return id;
    }

    public static final class Cursor {
        private final SessionPalette palette;
        private final BitSet sent;

        private Cursor(SessionPalette palette) {
            this.palette = palette;
            this.sent = new BitSet(256);
            reset();
        }

        public int sentCount() {
            return sent.cardinality();
        }

        public boolean isSent(int id) {
            return id >= 0 && sent.get(id);
        }

        public List<ViewStreamMessage.PaletteEntry> pending(int[] referencedIds) {
            List<ViewStreamMessage.PaletteEntry> entries = null;
            for (int id : referencedIds) {
                if (sent.get(id)) {
                    continue;
                }
                String state = palette.state(id);
                if (state == null) {
                    throw new IllegalArgumentException("palette id " + id + " is not assigned");
                }
                if (entries == null) {
                    entries = new ArrayList<ViewStreamMessage.PaletteEntry>(8);
                }
                entries.add(new ViewStreamMessage.PaletteEntry(id, state));
                sent.set(id);
            }
            return entries == null ? List.of() : entries;
        }

        public void reset() {
            sent.clear();
            sent.set(0, ViewStreamLimits.RESERVED_PALETTE_IDS);
        }
    }
}
