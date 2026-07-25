package art.arcane.wormholes.api.traversal;

final class TraversalText {
    static final int MAX_LENGTH = 128;

    private TraversalText() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String trimmed = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
        StringBuilder builder = new StringBuilder(trimmed.length());

        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            builder.append(character < ' ' || character == '\u007F' ? ' ' : character);
        }

        return builder.toString().strip();
    }
}
