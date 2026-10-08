package art.arcane.wormholes.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.shape.Shapes;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.wormholes.portal.ApertureShapeChange;

final class CommandPortalsShapeTest {
    @Test
    void shapeTakesAPortalThenAnOptionalKeyedShape() {
        List<DirectorParameterDescriptor> parameters = shapeNode().getDescriptor().getParameters();
        assertEquals(List.of("sender", "portal", "shape"), parameters.stream().map(DirectorParameterDescriptor::getName).toList());
        assertTrue(parameters.get(0).isContextual());
        assertTrue(parameters.get(1).isRequired());
        assertFalse(parameters.get(2).isRequired());
        assertEquals("", parameters.get(2).getDefaultValue());
    }

    @Test
    void shapeCompletionOffersThePresetNames() {
        CommandPortals.ShapeHandler handler = new CommandPortals.ShapeHandler();
        assertEquals(Shapes.presetNames(), new ArrayList<String>(handler.getPossibilities()));
        assertEquals(List.of("full", "flower", "feather"), new ArrayList<String>(handler.getPossibilities("F")));
        assertEquals("flower(petals=7,depth=0.7)", handler.parse(" flower(petals=7,depth=0.7) ", false));
    }

    @Test
    void anUnreadableShapeReportsTheErrorPosition() {
        ApertureShapeChange change = ApertureShapeChange.request("circle(radius=", ShapeDescriptor.FULL, shape -> true);
        assertEquals(ApertureShapeChange.Status.INVALID, change.status());
        assertEquals(ShapeDescriptor.FULL, change.shape());
        assertTrue(change.reason().contains("at position"), change.reason());
    }

    @Test
    void aShapeThatLeavesNoCellIsRefusedWhileAFittingShapeIsSet() {
        List<ShapeDescriptor> applied = new ArrayList<ShapeDescriptor>();
        ApertureShapeChange refused = ApertureShapeChange.request("circle(radius=0.05)", ShapeDescriptor.FULL, shape -> false);
        assertEquals(ApertureShapeChange.Status.TOO_SMALL, refused.status());
        assertEquals(ShapeDescriptor.parse("circle(radius=0.05)"), refused.shape());
        ApertureShapeChange set = ApertureShapeChange.request("flower(petals=7,depth=0.7)", ShapeDescriptor.FULL, applied::add);
        assertEquals(ApertureShapeChange.Status.SET, set.status());
        assertEquals(List.of(ShapeDescriptor.parse("flower(petals=7,depth=0.7)")), applied);
        ApertureShapeChange shown = ApertureShapeChange.request("  ", set.shape(), shape -> true);
        assertEquals(ApertureShapeChange.Status.SHOWN, shown.status());
        assertEquals(set.shape(), shown.shape());
    }

    private static DirectorRuntimeNode shapeNode() {
        DirectorRuntimeNode root = DirectorEngineFactory.create(new CommandPortals()).getRoot();
        for (DirectorRuntimeNode child : root.getChildren()) {
            if (child.getDescriptor().getName().equals("shape")) {
                return child;
            }
        }
        throw new AssertionError("missing /wh admin portals shape");
    }
}
