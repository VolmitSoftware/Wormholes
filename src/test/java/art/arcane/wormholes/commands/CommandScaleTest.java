package art.arcane.wormholes.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.wormholes.transit.ScaleResetRequest;

final class CommandScaleTest {
    @Test
    void resetTakesKeyedOptionalTargetAndRadius() {
        List<DirectorParameterDescriptor> parameters = child(DirectorEngineFactory.create(new CommandScale()).getRoot(), "reset")
            .getDescriptor().getParameters();

        assertEquals(List.of("sender", "target", "radius"), parameters.stream().map(DirectorParameterDescriptor::getName).toList());
        assertTrue(parameters.get(0).isContextual());
        assertFalse(parameters.get(1).isRequired());
        assertEquals("self", parameters.get(1).getDefaultValue());
        assertFalse(parameters.get(2).isRequired());
    }

    @Test
    void allRequiresARadiusAndPlayersAreNamed() {
        assertEquals(ScaleResetRequest.Problem.RADIUS_REQUIRED, ScaleResetRequest.parse("all", "").problem());
        assertEquals(ScaleResetRequest.Target.ALL, ScaleResetRequest.parse("all", "16").target());
        assertEquals(ScaleResetRequest.Target.SELF, ScaleResetRequest.parse("self", "").target());
        assertEquals("Alex", ScaleResetRequest.parse("Alex", "").player());
    }

    @Test
    void scaleIsMountedUnderAdminAndPortalsScaleTakesKeyedBounds() {
        DirectorRuntimeNode admin = DirectorEngineFactory.create(new CommandAdmin()).getRoot();
        child(child(admin, "scale"), "reset");
        List<DirectorParameterDescriptor> parameters = child(child(admin, "portals"), "scale").getDescriptor().getParameters();

        assertEquals(List.of("sender", "portal", "mode", "min", "max"), parameters.stream().map(DirectorParameterDescriptor::getName).toList());
        assertTrue(parameters.get(1).isRequired());
        assertFalse(parameters.get(2).isRequired());
        assertFalse(parameters.get(3).isRequired());
        assertFalse(parameters.get(4).isRequired());
    }

    private static DirectorRuntimeNode child(DirectorRuntimeNode node, String name) {
        for (DirectorRuntimeNode child : node.getChildren()) {
            if (child.getDescriptor().getName().equals(name)) {
                return child;
            }
        }
        throw new AssertionError("missing " + name);
    }
}
