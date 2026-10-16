package art.arcane.wormholes.menu;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MenuText {
    public static final String VALUE_MARK = "\uE000";

    private static final char SECTION = '§';

    private MenuText() {
    }

    public static byte[] encode(String value) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("text is not well-formed UTF-16", exception);
        }
    }

    public static String text(String value, String field) {
        String required = Objects.requireNonNull(value, field);
        int bytes = encode(required).length;
        if (bytes > MenuLimits.MAX_STRING_BYTES) {
            throw new IllegalArgumentException(field + " of " + bytes + " bytes exceeds " + MenuLimits.MAX_STRING_BYTES);
        }
        return required;
    }

    public static String id(String value, String field) {
        String required = text(value, field);
        if (required.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return required;
    }

    public static List<String> lines(List<String> values, int maxLines, String field) {
        List<String> required = Objects.requireNonNull(values, field);
        if (required.size() > maxLines) {
            throw new IllegalArgumentException(field + " has " + required.size() + " lines, more than " + maxLines);
        }
        List<String> copy = new ArrayList<>(required.size());
        for (String line : required) {
            copy.add(text(line, field));
        }
        return Collections.unmodifiableList(copy);
    }

    public static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    public static boolean plainInput(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == SECTION || character < 0x20 || character == 0x7F) {
                return false;
            }
        }
        return true;
    }

    public static String settingName(String markedLabel) {
        int mark = markedLabel.indexOf(VALUE_MARK);
        if (mark < 0) {
            return markedLabel;
        }
        String before = markedLabel.substring(0, mark);
        String name = trimEnd(before);
        if (!stripColors(name).isBlank()) {
            return name;
        }
        return before + trimStart(markedLabel.substring(mark + VALUE_MARK.length()));
    }

    public static String stripColors(String value) {
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == SECTION && index + 1 < value.length()) {
                index++;
                continue;
            }
            plain.append(character);
        }
        return plain.toString();
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0) {
            if (separator(value.charAt(end - 1))) {
                end--;
            } else if (end >= 2 && value.charAt(end - 2) == SECTION) {
                end -= 2;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }

    private static String trimStart(String value) {
        int start = 0;
        while (start < value.length()) {
            if (separator(value.charAt(start))) {
                start++;
            } else if (value.charAt(start) == SECTION && start + 1 < value.length()) {
                start += 2;
            } else {
                break;
            }
        }
        return value.substring(start);
    }

    private static boolean separator(char character) {
        return Character.isWhitespace(character) || character == ':' || character == '\uFF1A';
    }
}
