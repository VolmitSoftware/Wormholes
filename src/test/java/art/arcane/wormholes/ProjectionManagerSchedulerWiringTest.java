package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.BootstrapMethodEntry;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.LoadableConstantEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectionManagerSchedulerWiringTest {
    private static final String PROJECTION_MANAGER = "art/arcane/wormholes/ProjectionManager";
    private static final String FOLIA_SCHEDULER = "art/arcane/volmlib/util/scheduling/FoliaScheduler";
    private static final String J = "art/arcane/wormholes/util/J";
    private static final String RUN_ENTITY_WITH_RETIREMENT =
        "(Lorg/bukkit/plugin/Plugin;Lorg/bukkit/entity/Entity;Ljava/lang/Runnable;JLjava/lang/Runnable;)Z";

    @Test
    void everyObserverFrameTickDispatchesThroughTheRealEntityScheduler() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        List<Instruction> tick = body(manager, "tick");
        int dispatches = 0;

        for (int index = 0; index < tick.size(); index++) {
            if (!(tick.get(index) instanceof InvokeInstruction invoke)) {
                continue;
            }
            if (!PROJECTION_MANAGER.equals(invoke.owner().asInternalName())
                || !"dispatchObserverFrame".equals(invoke.name().stringValue())) {
                continue;
            }
            dispatches++;
            assertTrue(index > 0 && tick.get(index - 1) instanceof FieldInstruction field
                    && field.opcode() == Opcode.GETSTATIC
                    && PROJECTION_MANAGER.equals(field.owner().asInternalName())
                    && "ENTITY_FRAME_SCHEDULER".equals(field.name().stringValue()),
                "tick() must hand dispatchObserverFrame the real ENTITY_FRAME_SCHEDULER; a test-only scheduler "
                    + "here means nothing in production ever reaches FoliaScheduler");
        }

        assertEquals(1, dispatches, "tick() must dispatch every observer frame through dispatchObserverFrame");
    }

    @Test
    void theRealObserverFrameSchedulerHandsFoliaANonNullRetirementCallback() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        List<Instruction> scheduler = body(manager, lambdaBehind(manager, "ENTITY_FRAME_SCHEDULER"));
        int retirementAwareCalls = 0;

        for (int index = 0; index < scheduler.size(); index++) {
            if (!(scheduler.get(index) instanceof InvokeInstruction invoke)) {
                continue;
            }
            assertEquals(FOLIA_SCHEDULER, invoke.owner().asInternalName(),
                "ENTITY_FRAME_SCHEDULER must schedule through FoliaScheduler");
            assertEquals("runEntity", invoke.name().stringValue(),
                "ENTITY_FRAME_SCHEDULER must schedule the observer frame on the observer entity");
            assertEquals(RUN_ENTITY_WITH_RETIREMENT, invoke.type().stringValue(),
                "ENTITY_FRAME_SCHEDULER must call the runEntity overload that takes a retirement callback; "
                    + "without it a retired-but-accepted task never releases the in-flight observer id and "
                    + "that observer is starved of every later frame");
            assertTrue(index > 0 && scheduler.get(index - 1) instanceof LoadInstruction load
                    && load.typeKind() == TypeKind.REFERENCE
                    && load.slot() == 2,
                "the retirement argument must be the scheduler's own retired callback, not null");
            retirementAwareCalls++;
        }

        assertEquals(1, retirementAwareCalls,
            "ENTITY_FRAME_SCHEDULER must make exactly one scheduling call");
    }

    @Test
    void theRealObserverFrameSchedulerPassesTheRetirementCallbackUnconditionally() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        for (Instruction instruction : body(manager, lambdaBehind(manager, "ENTITY_FRAME_SCHEDULER"))) {
            assertFalse(instruction instanceof BranchInstruction,
                "ENTITY_FRAME_SCHEDULER must pass the retirement callback on every path; branching on the "
                    + "server threading model to dodge the entity-validity guard would reintroduce the "
                    + "starvation this callback exists to prevent and would cost an ownership probe per "
                    + "observer per tick on the render path");
        }
    }

    @Test
    void projectedEntityUpdatesDispatchThroughTheCountedSeam() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);

        for (String broadcaster : List.of("dispatchProjectedEntityAnimation", "dispatchProjectedEntityHurt")) {
            Set<String> invoked = invokedMethods(manager, broadcaster);
            assertTrue(invoked.contains(PROJECTION_MANAGER + ".dispatchProjectedEntityUpdate"),
                broadcaster + "() must route through dispatchProjectedEntityUpdate so a refused schedule is "
                    + "counted instead of dying in a private field");
            assertFalse(invoked.contains(FOLIA_SCHEDULER + ".runEntity"),
                broadcaster + "() must not schedule around the counted seam");
            assertTrue(invoked.contains("art/arcane/wormholes/ProjectionInterestSet.projectedEntityObservers"),
                broadcaster + "() must route through the reverse entity-interest index");
            assertFalse(invoked.contains("art/arcane/wormholes/ProjectionInterestSet.snapshot"),
                broadcaster + "() must not scan every projector for one source entity event");
        }

        assertTrue(invokedMethods(manager, "dispatchProjectedEntityUpdate")
                .contains("art/arcane/wormholes/service/WormholesTelemetry.countFailure"),
            "a dropped projected entity update is a terminal failure and must increment the plugin-wide counter");
    }

    @Test
    void theRealProjectedEntitySchedulerHandsFoliaANonNullRetirementCallback() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        List<Instruction> scheduler = body(manager, lambdaBehind(manager, "ENTITY_UPDATE_SCHEDULER"));
        int retirementAwareCalls = 0;

        for (int index = 0; index < scheduler.size(); index++) {
            if (!(scheduler.get(index) instanceof InvokeInstruction invoke)) {
                continue;
            }
            assertEquals(FOLIA_SCHEDULER, invoke.owner().asInternalName());
            assertEquals("runEntity", invoke.name().stringValue());
            assertEquals(RUN_ENTITY_WITH_RETIREMENT, invoke.type().stringValue());
            assertTrue(index > 0 && scheduler.get(index - 1) instanceof LoadInstruction load
                    && load.typeKind() == TypeKind.REFERENCE
                    && load.slot() == 2,
                "projected entity batches must pass their retirement callback so a retired observer cannot keep "
                    + "a permanently scheduled batch");
            retirementAwareCalls++;
        }

        assertEquals(1, retirementAwareCalls);
    }

    @Test
    void aDroppedObserverFrameIsCountedOnThePluginWideFailureSurface() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        assertTrue(invokedMethods(manager, "releaseObserverFrame")
                .contains("art/arcane/wormholes/service/WormholesTelemetry.countFailure"),
            "a dropped observer frame is a terminal failure and must increment the plugin-wide counter");
    }

    @Test
    void theProjectionTickIsScheduledOnceAndIsNeverCancelledAndRescheduled() throws IOException {
        ClassModel manager = parse(PROJECTION_MANAGER);
        Set<String> scheduling = invokedMethods(manager, "scheduleTick");

        assertTrue(scheduling.contains(J + ".sr"), "scheduleTick() must still start the projection tick");
        assertFalse(scheduling.contains(J + ".csr"),
            "scheduleTick() must not carry a cancel-and-reschedule branch; the tick interval is a constant 1 so "
                + "that branch can never be reached after the constructor's first call");
        assertTrue(invokedMethods(manager, "shutdown").contains(J + ".csr"),
            "shutdown() is the only place that may cancel the projection tick");
    }

    private static String lambdaBehind(ClassModel model, String fieldName) {
        String pending = null;
        for (Instruction instruction : body(model, "<clinit>")) {
            if (instruction instanceof InvokeDynamicInstruction indy) {
                pending = implementationMethod(indy);
            }
            if (instruction instanceof FieldInstruction field
                && field.opcode() == Opcode.PUTSTATIC
                && fieldName.equals(field.name().stringValue())) {
                assertNotNull(pending, fieldName + " must be built from a lambda in <clinit>");
                return pending;
            }
        }
        throw new AssertionError("No static assignment of " + fieldName + " in " + model.thisClass().asInternalName());
    }

    private static String implementationMethod(InvokeDynamicInstruction indy) {
        BootstrapMethodEntry bootstrap = indy.invokedynamic().bootstrap();
        for (LoadableConstantEntry argument : bootstrap.arguments()) {
            if (argument instanceof MethodHandleEntry handle) {
                return handle.reference().name().stringValue();
            }
        }
        return null;
    }

    private static Set<String> invokedMethods(ClassModel model, String methodName) {
        Set<String> invoked = new LinkedHashSet<String>();
        for (Instruction instruction : body(model, methodName)) {
            if (instruction instanceof InvokeInstruction invoke) {
                invoked.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
            }
        }
        return invoked;
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
        try (InputStream input = ProjectionManagerSchedulerWiringTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + internalName);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
