package art.arcane.wormholes.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FidelitySubsystemWiringTest {
    private static final String FIDELITY_SUBSYSTEM = "art/arcane/wormholes/render/FidelitySubsystem";
    private static final String FIDELITY_SETTINGS = "art/arcane/wormholes/render/FidelitySettings";
    private static final String PROJECTION_MANAGER = "art/arcane/wormholes/ProjectionManager";

    @Test
    void startAppliesTheRefreshedPlateSettingsToTheProjectionManager() throws IOException {
        List<Instruction> start = body(parse(FIDELITY_SUBSYSTEM), "start");
        int refresh = firstInvoke(start, FIDELITY_SETTINGS, "refresh");
        int applied = firstInvoke(start, PROJECTION_MANAGER, "onFidelitySettingsReloaded");

        assertTrue(refresh >= 0, "start() must refresh the fidelity settings snapshot");
        assertTrue(applied >= 0,
            "start() must hand the refreshed settings to the projection manager; the plate cache and plate workers "
                + "are built before the fidelity subsystem starts, so without this call plate-max-bytes and "
                + "plate-workers keep their defaults until the first settings reload");
        assertTrue(refresh < applied,
            "start() must apply the plate settings after refreshing them, or the cache is capped with stale values");
    }

    private static int firstInvoke(List<Instruction> body, String owner, String name) {
        for (int index = 0; index < body.size(); index++) {
            if (body.get(index) instanceof InvokeInstruction invoke
                && owner.equals(invoke.owner().asInternalName())
                && name.equals(invoke.name().stringValue())) {
                return index;
            }
        }
        return -1;
    }

    private static List<Instruction> body(ClassModel model, String methodName) {
        for (MethodModel method : model.methods()) {
            if (!method.methodName().equalsString(methodName)) {
                continue;
            }
            Optional<CodeModel> code = method.code();
            if (code.isPresent()) {
                List<Instruction> instructions = new ArrayList<Instruction>();
                for (CodeElement element : code.get().elementList()) {
                    if (element instanceof Instruction instruction) {
                        instructions.add(instruction);
                    }
                }
                return instructions;
            }
        }
        throw new AssertionError("No method named " + methodName + " in " + model.thisClass().asInternalName());
    }

    private static ClassModel parse(String internalName) throws IOException {
        try (InputStream input = FidelitySubsystemWiringTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + internalName);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
