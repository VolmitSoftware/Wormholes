package art.arcane.wormholes.portal;

import art.arcane.optics.shape.ShapeDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ApertureShapePresetsTest {
    @Test
    void leftClicksCycleThePresetsInOrderAndWrap() {
        List<String> expected = List.of("circle", "rounded(radius=0.35)", "polygon(sides=6)", "star", "flower", "heart", "feather", "ring", "full");
        ShapeDescriptor current = ShapeDescriptor.FULL;
        for (String text : expected) {
            current = ApertureShapePresets.next(current);
            assertEquals(ShapeDescriptor.parse(text), current, text);
        }
    }

    @Test
    void aCustomShapeCyclesBackToTheFirstPreset() {
        assertEquals(ShapeDescriptor.FULL, ApertureShapePresets.next(ShapeDescriptor.parse("ellipse(radiusU=0.5,radiusV=1)")));
        assertEquals(ShapeDescriptor.FULL, ApertureShapePresets.next(ApertureShapePresets.rotated(ShapeDescriptor.parse("heart"))));
    }

    @Test
    void rotationStepsByFortyFiveDegreesAndReturnsToThePresetAfterAFullTurn() {
        ShapeDescriptor heart = ShapeDescriptor.parse("heart");
        ShapeDescriptor turned = ApertureShapePresets.rotated(heart);
        assertEquals(ShapeDescriptor.parse("heart@rotate(45)"), turned);
        ShapeDescriptor current = heart;
        for (int step = 0; step < 8; step++) {
            current = ApertureShapePresets.rotated(current);
            if (step < 7) {
                assertNotEquals(heart, current);
            }
        }
        assertEquals(heart, current);
        assertEquals(ShapeDescriptor.FULL, ApertureShapePresets.rotated(ShapeDescriptor.FULL));
    }

    @Test
    void rotationKeepsTheFitMode() {
        ShapeDescriptor stretched = ShapeDescriptor.parse("heart@fit(stretch)");
        assertEquals(stretched.fit(), ApertureShapePresets.rotated(stretched).fit());
    }
}
