package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNexusTest {
    @Test
    void theNexusGroupExposesEverySubcommandTheOperatorNeeds() {
        DirectorRuntimeNode root = tree();

        assertEquals("nexus", root.getDescriptor().getName());
        assertEquals(List.of("add", "address", "create", "delete", "doctor", "hub", "info", "list", "remove", "visibility"),
                root.getChildren().stream().map(child -> child.getDescriptor().getName()).sorted().toList());
    }

    @Test
    void createTakesANameThenKeyedVisibilityAndTopologyDefaults() {
        DirectorRuntimeNode create = child("create");
        List<DirectorParameterDescriptor> parameters = create.getDescriptor().getParameters();

        assertEquals(List.of("sender", "name", "visibility", "topology"),
                parameters.stream().map(DirectorParameterDescriptor::getName).toList());
        assertTrue(parameters.get(1).isRequired());
        assertFalse(parameters.get(2).isRequired());
        assertEquals("members", parameters.get(2).getDefaultValue());
        assertFalse(parameters.get(3).isRequired());
        assertEquals("mesh", parameters.get(3).getDefaultValue());
    }

    @Test
    void addTakesANetworkAndAPortalWithAnOptionalKeyedAddress() {
        List<DirectorParameterDescriptor> parameters = child("add").getDescriptor().getParameters();

        assertEquals(List.of("sender", "network", "portal", "address"),
                parameters.stream().map(DirectorParameterDescriptor::getName).toList());
        assertTrue(parameters.get(1).isRequired());
        assertTrue(parameters.get(2).isRequired());
        assertFalse(parameters.get(3).isRequired());
        assertEquals(CommandNexus.AUTO_ADDRESS, parameters.get(3).getDefaultValue());
    }

    @Test
    void everyOptionalParameterCarriesADefaultSoItMustBeGivenAsKeyEqualsValue() {
        for (DirectorRuntimeNode node : tree().getChildren()) {
            List<DirectorParameterDescriptor> parameters = node.getDescriptor().getParameters();
            for (DirectorParameterDescriptor parameter : parameters) {
                if (parameter.isContextual() || parameter.isRequired()) {
                    continue;
                }
                assertNotNull(parameter.getDefaultValue(),
                        node.getDescriptor().getName() + "." + parameter.getName() + " is optional without a default");
            }
        }
    }

    @Test
    void requiredParametersAlwaysComeBeforeOptionalOnesSoStrayArgumentsCannotBeAbsorbed() {
        for (DirectorRuntimeNode node : tree().getChildren()) {
            List<String> order = new ArrayList<>();
            boolean seenOptional = false;
            for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters()) {
                if (parameter.isContextual()) {
                    continue;
                }
                order.add(parameter.getName());
                if (parameter.isRequired()) {
                    assertFalse(seenOptional, node.getDescriptor().getName() + " puts " + parameter.getName()
                            + " after an optional parameter: " + order);
                } else {
                    seenOptional = true;
                }
            }
        }
    }

    @Test
    void listAndDoctorTakeNothingButTheSender() {
        for (String name : List.of("list", "doctor")) {
            List<DirectorParameterDescriptor> parameters = child(name).getDescriptor().getParameters();
            assertEquals(1, parameters.size(), name + " should only take the sender");
            assertTrue(parameters.getFirst().isContextual());
        }
    }

    private static DirectorRuntimeNode tree() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandNexus());
        return engine.getRoot();
    }

    private static DirectorRuntimeNode child(String name) {
        return tree().getChildren().stream()
                .filter(node -> node.getDescriptor().getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing /wh nexus " + name));
    }
}
