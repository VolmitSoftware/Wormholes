package art.arcane.wormholes.access;

import java.util.Locale;

public final class PortalPermissionKey {
    public static final String NODE_PREFIX = "wormholes.portal.";
    public static final int MAX_LENGTH = 64;
    private static final String FALLBACK = "unnamed";

    private PortalPermissionKey() {
    }

    public static String sanitize(String name) {
        String source = name == null || name.isBlank() ? FALLBACK : name.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(source.length());
        boolean previousSeparator = false;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_';
            if (allowed) {
                builder.append(character);
                previousSeparator = false;
                continue;
            }
            if (!previousSeparator) {
                builder.append('_');
                previousSeparator = true;
            }
        }
        String trimmed = trimSeparators(builder.toString());
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }

    public static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    public static String node(String key) {
        return NODE_PREFIX + key;
    }

    private static String trimSeparators(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }
}
