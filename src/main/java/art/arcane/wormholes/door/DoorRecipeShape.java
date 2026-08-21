package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The grid of one configurable door recipe, written as pipe-separated rows.
 *
 * <p>{@code "EDE|ORO| D "} is three rows of three. A space means an empty slot.
 * Rows shorter than the widest are padded, because a trailing space is the
 * easiest thing in the world to lose out of a config file.</p>
 */
public record DoorRecipeShape(List<String> rows) {
    public static final char EMPTY = ' ';
    public static final char ROW_SEPARATOR = '|';
    public static final int MAX_ROWS = 3;
    public static final int MAX_COLUMNS = 3;

    public DoorRecipeShape {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("a recipe needs 1 to " + MAX_ROWS + " rows, not " + rows.size());
        }
        int width = rows.getFirst().length();
        if (width == 0 || width > MAX_COLUMNS) {
            throw new IllegalArgumentException("a recipe row needs 1 to " + MAX_COLUMNS + " columns, not " + width);
        }
        for (String row : rows) {
            if (row.length() != width) {
                throw new IllegalArgumentException("every recipe row must be the same width");
            }
        }
        rows = List.copyOf(rows);
    }

    /** Parses {@code "EDE|ORO| D "}, padding short rows to the widest one. */
    public static DoorRecipeShape parse(String shape) {
        String required = Objects.requireNonNull(shape, "shape");
        if (required.isBlank()) {
            throw new IllegalArgumentException("shape cannot be blank");
        }

        List<String> parsed = new ArrayList<>(MAX_ROWS);
        int width = 0;
        for (String row : required.split("\\" + ROW_SEPARATOR, -1)) {
            parsed.add(row);
            width = Math.max(width, row.length());
        }
        if (width == 0) {
            throw new IllegalArgumentException("shape cannot be empty");
        }

        List<String> padded = new ArrayList<>(parsed.size());
        for (String row : parsed) {
            padded.add(row.length() == width ? row : row + String.valueOf(EMPTY).repeat(width - row.length()));
        }
        return new DoorRecipeShape(padded);
    }

    /** Every distinct non-empty slot symbol, in the order it first appears. */
    public Set<Character> symbols() {
        Set<Character> symbols = new LinkedHashSet<>();
        for (String row : rows) {
            for (int index = 0; index < row.length(); index++) {
                char symbol = row.charAt(index);
                if (symbol != EMPTY) {
                    symbols.add(Character.valueOf(symbol));
                }
            }
        }
        return symbols;
    }

    public String[] toShapeRows() {
        return rows.toArray(String[]::new);
    }

    @Override
    public String toString() {
        return String.join(String.valueOf(ROW_SEPARATOR), rows);
    }
}
