package art.arcane.wormholes.papi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class WormholesPlaceholderWiringBytecodeTest {
    private static final String WORMHOLES = "art/arcane/wormholes/Wormholes";
    private static final String ATTENDANCE = "art/arcane/wormholes/PortalRegistryAttendance";
    private static final String HOLDER = "art/arcane/wormholes/papi/WormholesPlaceholders";
    private static final String REGISTRATION = "art/arcane/volmlib/util/bukkit/papi/PlaceholderRegistration";
    private static final String STORE = "art/arcane/volmlib/util/bukkit/papi/PlayerSnapshotStore";
    private static final long GRACE_MS = 60_000L;

    @Test
    void registerPlaceholdersAsksWhetherPlaceholderApiIsPresentBeforeItTouchesAnythingElse() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);

        for (Instruction instruction : body(wormholes, "registerPlaceholders")) {
            if (instruction instanceof InvokeInstruction invoke) {
                assertEquals(REGISTRATION + ".isPlaceholderApiEnabled",
                    invoke.owner().asInternalName() + "." + invoke.name().stringValue(),
                    "registerPlaceholders() must gate on PlaceholderAPI being enabled before it builds, registers "
                        + "or logs anything; without that gate a server without PlaceholderAPI still walks into the "
                        + "expansion wiring");
                return;
            }
        }

        throw new AssertionError("registerPlaceholders() must call PlaceholderRegistration.isPlaceholderApiEnabled()");
    }

    @Test
    void forgettingAPlayerStartsTheSixtySecondGraceWindowInsteadOfDroppingTheAnswer() throws IOException {
        ClassModel holder = parse(HOLDER);
        List<Instruction> forget = body(holder, "forget");

        assertTrue(invoked(forget).contains(STORE + ".evictAfterGrace"),
            "WormholesPlaceholders.forget() must hand the quit to PlayerSnapshotStore.evictAfterGrace(); "
                + "publishing null instead drops the answer the moment the player leaves");

        boolean gracePassed = false;

        for (Instruction instruction : forget) {
            if (instruction instanceof ConstantInstruction constant && Long.valueOf(GRACE_MS).equals(constant.constantValue())) {
                gracePassed = true;
            }
        }

        assertTrue(gracePassed,
            "WormholesPlaceholders.forget() must pass the " + GRACE_MS + "ms grace window the plan requires");
    }

    @Test
    void theAttendanceSweepHandsQuitsToThePlaceholderHolder() throws IOException {
        ClassModel attendance = parse(ATTENDANCE);

        assertTrue(invoked(body(attendance, "forget")).contains(HOLDER + ".forget"),
            "PortalRegistryAttendance.forget() must tell the placeholder holder about the quit; otherwise the "
                + "per-player answer is never retired and leaks for the life of the server");
    }

    private static Set<String> invoked(List<Instruction> body) {
        Set<String> invocations = new LinkedHashSet<String>();

        for (Instruction instruction : body) {
            if (instruction instanceof InvokeInstruction invoke) {
                invocations.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
            }
        }

        return invocations;
    }

    private static List<Instruction> body(ClassModel model, String methodName) {
        for (MethodModel method : model.methods()) {
            if (!method.methodName().equalsString(methodName)) {
                continue;
            }

            Optional<CodeModel> code = method.code();

            if (code.isPresent()) {
                return instructions(code.get());
            }
        }

        throw new AssertionError("No method named " + methodName + " in " + model.thisClass().asInternalName());
    }

    private static List<Instruction> instructions(CodeModel code) {
        List<Instruction> body = new ArrayList<Instruction>();

        for (CodeElement element : code.elementList()) {
            if (element instanceof Instruction instruction) {
                body.add(instruction);
            }
        }

        return body;
    }

    private static ClassModel parse(String internalName) throws IOException {
        try (InputStream input = WormholesPlaceholderWiringBytecodeTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + internalName);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
