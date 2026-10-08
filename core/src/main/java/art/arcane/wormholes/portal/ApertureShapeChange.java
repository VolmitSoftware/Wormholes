package art.arcane.wormholes.portal;

import java.util.Objects;
import java.util.function.Predicate;

import art.arcane.optics.shape.ShapeDescriptor;

public record ApertureShapeChange(Status status, ShapeDescriptor shape, String reason) {
    public ApertureShapeChange {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(shape, "shape");
        reason = reason == null ? "" : reason;
    }

    public static ApertureShapeChange request(String text, ShapeDescriptor current, Predicate<ShapeDescriptor> apply) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return new ApertureShapeChange(Status.SHOWN, current, "");
        }
        ShapeDescriptor parsed;
        try {
            parsed = ShapeDescriptor.parse(trimmed);
        } catch (IllegalArgumentException error) {
            return new ApertureShapeChange(Status.INVALID, current, error.getMessage());
        }
        return apply.test(parsed) ? new ApertureShapeChange(Status.SET, parsed, "") : new ApertureShapeChange(Status.TOO_SMALL, parsed, "");
    }

    public enum Status {
        SHOWN, SET, INVALID, TOO_SMALL
    }
}
