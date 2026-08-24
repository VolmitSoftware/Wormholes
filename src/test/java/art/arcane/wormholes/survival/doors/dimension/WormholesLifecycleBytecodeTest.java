package art.arcane.wormholes.survival.doors.dimension;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class WormholesLifecycleBytecodeTest {
    private static final String WORMHOLES = "art/arcane/wormholes/Wormholes";
    private static final String WORMHOLES_NETWORK_RUNTIME = "art/arcane/wormholes/WormholesNetworkRuntime";
    private static final String FOLIA_SCHEDULER = "art/arcane/volmlib/util/scheduling/FoliaScheduler";
    private static final String CHUNK_PRE_SEND_PROVIDER =
        "art/arcane/wormholes/chunk/presend/BukkitChunkPreSendProvider";

    private static final List<String> REVERSE_CONSTRUCTION_ORDER = List.of(
        "placeholders",
        "viewServer",
        "viewSubscriptions",
        "remoteViewCache",
        "traversalService",
        "portalSyncService",
        "importExportService",
        "networkManager",
        "remotePortalRegistry",
        "dimensionalDoorManager",
        "pocketWorldService",
        "rtpRuntime",
        "arrivalWarmer",
        "projectionChangeTracker",
        "projectionManager",
        "traversableManager",
        "portalManager",
        "vanillaTravelCostCapture",
        "wandSelectionManager",
        "constructionManager",
        "effectManager",
        "blockManager",
        "vaultEconomy",
        "instance",
        "INSTANCE"
    );

    private static final List<String> IDENTITY_STATICS = List.of(
        "instance",
        "INSTANCE"
    );

    private static final List<String> SCHEDULER_SENSITIVE_CLASSES = List.of(
        "art/arcane/wormholes/Wormholes",
        "art/arcane/wormholes/WormholesReloadCoordinator",
        "art/arcane/wormholes/WormholesNetworkRuntime",
        "art/arcane/wormholes/WormholesDoorLifecycle",
        "art/arcane/wormholes/BlockManager",
        "art/arcane/wormholes/BlockOpsRuneConstruction",
        "art/arcane/wormholes/BlockOpsRuneIndex"
    );

    @Test
    void drainDelegatesTheStaticServiceClearToTheEndOfTeardown() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        assertTrue(invokedMethods(wormholes, "drain").contains(WORMHOLES + ".clearStaticServices"),
            "drain() must hand off to clearStaticServices() so the static service graph is released after teardown");
    }

    @Test
    void clearStaticServicesNullsEveryServiceStaticInReverseConstructionOrder() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<Instruction> body = body(wormholes, "clearStaticServices");
        List<String> nulled = new ArrayList<String>();

        for (int index = 0; index < body.size(); index++) {
            if (!(body.get(index) instanceof FieldInstruction field)) {
                continue;
            }
            if (field.opcode() != Opcode.PUTSTATIC || !WORMHOLES.equals(field.owner().asInternalName())) {
                continue;
            }
            assertTrue(index > 0 && body.get(index - 1).opcode() == Opcode.ACONST_NULL,
                "clearStaticServices() must assign null to " + field.name().stringValue());
            nulled.add(field.name().stringValue());
        }

        assertEquals(REVERSE_CONSTRUCTION_ORDER, nulled,
            "clearStaticServices() must release every static service in reverse construction order");
    }

    @Test
    void clearStaticServicesRefusesToWipeStaticsOwnedByANewerPluginInstance() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<Instruction> body = body(wormholes, "clearStaticServices");
        FieldInstruction firstStaticAccess = null;

        for (Instruction instruction : body) {
            if (instruction instanceof FieldInstruction field
                && WORMHOLES.equals(field.owner().asInternalName())
                && (field.opcode() == Opcode.GETSTATIC || field.opcode() == Opcode.PUTSTATIC)) {
                firstStaticAccess = field;
                break;
            }
        }

        assertNotNull(firstStaticAccess, "clearStaticServices() must touch the Wormholes statics");
        assertEquals("INSTANCE", firstStaticAccess.name().stringValue(),
            "clearStaticServices() must read INSTANCE and bail out before nulling anything a newer plugin instance owns");
        assertEquals(Opcode.GETSTATIC, firstStaticAccess.opcode());
    }

    @Test
    void clearStaticServicesDropsPendingChatPrompts() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        assertTrue(invokedMethods(wormholes, "clearStaticServices").contains(WORMHOLES + ".clearChatInputs"),
            "clearStaticServices() must drop chat callbacks that captured the outgoing plugin instance");
    }

    @Test
    void aFailedEnableDisablesThePluginInsteadOfStayingHalfBuilt() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        assertTrue(invokedMethods(wormholes, "onEnable").contains(WORMHOLES + ".abortFailedEnable"),
            "onEnable() must abort when the service graph could not be built");

        Set<String> abort = invokedMethods(wormholes, "abortFailedEnable");
        assertTrue(abort.contains(WORMHOLES + ".tearDownBeforeDrain"),
            "A failed enable must tear down whatever was already constructed");
        assertTrue(abort.contains("org/bukkit/plugin/PluginManager.disablePlugin"),
            "A failed enable must disable the plugin so no listener runs against a half-built service graph");
    }

    @Test
    void hotloadAdmissionClosesBeforeManagerTeardownBegins() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<String> calls = new ArrayList<String>(invokedMethods(wormholes, "tearDownBeforeDrain"));
        int stopHotload = calls.indexOf(
            "art/arcane/wormholes/WormholesReloadCoordinator.stopHotloadManagerDuringDrain"
        );
        int stopDoors = calls.indexOf("art/arcane/wormholes/WormholesDoorLifecycle.shutdownManagerBeforeSchedulers");
        int stopProjection = calls.indexOf(WORMHOLES + ".shutdownProjectionBeforeSchedulers");

        assertTrue(stopHotload >= 0, "tearDownBeforeDrain() must stop hotloading");
        assertTrue(stopDoors > stopHotload, "Hotload callbacks must be rejected before door teardown begins");
        assertTrue(stopProjection > stopHotload, "Hotload callbacks must be rejected before projection teardown begins");
    }

    @Test
    void pendingCrossServerTravelersAreRestoredBeforePluginSchedulersAreCancelled() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<String> calls = new ArrayList<String>(invokedMethods(wormholes, "tearDownBeforeDrain"));
        int shutdownTraversal = calls.indexOf(WORMHOLES + ".shutdownTraversalBeforeSchedulers");
        int cancelRuntime = calls.indexOf("art/arcane/volmlib/util/scheduling/SchedulerRuntime.cancelPluginTasks");
        int cancelFolia = calls.indexOf(FOLIA_SCHEDULER + ".cancelTasks");

        assertTrue(shutdownTraversal >= 0, "tearDownBeforeDrain() must drain pending cross-server traversal state");
        assertTrue(cancelRuntime > shutdownTraversal,
            "Traversal rollback must finish before SchedulerRuntime cancels entity-owned restore tasks");
        assertTrue(cancelFolia > shutdownTraversal,
            "Traversal rollback must finish before the Folia scheduler cancels entity-owned restore tasks");
    }

    @Test
    void traversalCostGatewayDrainsAfterTraversalProducersAndBeforeSchedulerCancellation() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<String> calls = new ArrayList<String>(invokedMethods(wormholes, "tearDownBeforeDrain"));
        int stopDoors = calls.indexOf("art/arcane/wormholes/WormholesDoorLifecycle.shutdownManagerBeforeSchedulers");
        int stopRtp = calls.indexOf(WORMHOLES + ".shutdownRtpBeforeSchedulers");
        int stopTraversal = calls.indexOf(WORMHOLES + ".shutdownTraversalBeforeSchedulers");
        int stopPortals = calls.indexOf(WORMHOLES + ".shutdownPortalManagerBeforeCostGateway");
        int stopCosts = calls.indexOf(
            "art/arcane/wormholes/api/traversal/internal/TraversalCostGateway.shutdown");
        int stopRegionTasks = calls.indexOf(
            "art/arcane/wormholes/platform/BukkitRegionTaskProvider.shutdown");
        int cancelRuntime = calls.indexOf("art/arcane/volmlib/util/scheduling/SchedulerRuntime.cancelPluginTasks");
        int cancelFolia = calls.indexOf(FOLIA_SCHEDULER + ".cancelTasks");

        assertTrue(stopCosts > stopDoors, "Door settlement must finish before traversal cost shutdown");
        assertTrue(stopCosts > stopRtp, "RTP settlement must finish before traversal cost shutdown");
        assertTrue(stopCosts > stopTraversal, "Cross-server settlement must finish before traversal cost shutdown");
        assertTrue(stopCosts > stopPortals, "Local portal producers must stop before traversal cost shutdown");
        assertTrue(stopRegionTasks > stopCosts,
            "Accepted regional rollback tasks must remain available through traversal cost shutdown");
        assertTrue(cancelRuntime > stopRegionTasks,
            "Regional task retirement must finish before SchedulerRuntime cancellation");
        assertTrue(cancelFolia > stopRegionTasks,
            "Regional task retirement must finish before Folia cancellation");
        assertTrue(cancelRuntime > stopCosts,
            "Entity-owned cost settlement must drain before SchedulerRuntime cancellation");
        assertTrue(cancelFolia > stopCosts, "Entity-owned cost settlement must drain before Folia cancellation");
    }

    @Test
    void chunkPreSendIsInstalledForTheEnableLifetimeAndStoppedBeforeSchedulerCancellation() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        assertTrue(invokedMethods(wormholes, "onEnable").contains(CHUNK_PRE_SEND_PROVIDER + ".install"),
            "onEnable() must install the Bukkit chunk pre-send service");
        List<String> drain = new ArrayList<String>(invokedMethods(wormholes, "tearDownBeforeDrain"));
        int shutdown = drain.indexOf(WORMHOLES + ".shutdownChunkPreSendBeforeSchedulers");
        int cancelRuntime = drain.indexOf("art/arcane/volmlib/util/scheduling/SchedulerRuntime.cancelPluginTasks");

        assertTrue(shutdown >= 0, "tearDownBeforeDrain() must release the chunk pre-send service");
        assertTrue(cancelRuntime > shutdown, "Chunk pre-send must stop before plugin schedulers are cancelled");
        assertTrue(invokedMethods(wormholes, "shutdownChunkPreSendBeforeSchedulers")
            .contains(CHUNK_PRE_SEND_PROVIDER + ".shutdown"),
            "Chunk pre-send shutdown must release the installed provider");
    }

    @Test
    void networkResetAndDrainCannotDiscardPendingCrossServerTravelers() throws IOException {
        ClassModel runtime = parse(WORMHOLES_NETWORK_RUNTIME);
        String shutdown = "art/arcane/wormholes/network/TraversalService.shutdown";

        assertTrue(invokedMethods(runtime, "reset").contains(shutdown),
            "Network reset must restore pending travelers before replacing TraversalService");
        assertTrue(invokedMethods(runtime, "drain").contains(shutdown),
            "Network drain must restore pending travelers even when called outside the main teardown path");
    }

    @Test
    void enableResetsTheDrainLatchSoAReEnteredEnableCanStillDrain() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        assertTrue(invokedMethods(wormholes, "onEnable").contains(WORMHOLES + ".resetForEnable"),
            "onEnable() must reset per-enable state before it constructs anything");

        Set<String> reset = invokedMethods(wormholes, "resetForEnable");
        assertTrue(reset.contains("java/util/concurrent/atomic/AtomicBoolean.set"),
            "resetForEnable() must clear the already-drained latch or a re-entered enable can never drain again");
        assertTrue(reset.contains("org/bukkit/event/HandlerList.unregisterAll"),
            "resetForEnable() must drop stale listener registrations before re-registering them");
    }

    @Test
    void enableReclaimsTheIdentityStaticsThatDrainReleased() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);
        List<Instruction> body = body(wormholes, "resetForEnable");
        List<String> reclaimed = new ArrayList<String>();

        for (int index = 0; index < body.size(); index++) {
            if (!(body.get(index) instanceof FieldInstruction field)) {
                continue;
            }
            if (field.opcode() != Opcode.PUTSTATIC || !WORMHOLES.equals(field.owner().asInternalName())) {
                continue;
            }
            assertTrue(index > 0
                    && body.get(index - 1) instanceof LoadInstruction load
                    && load.slot() == 0,
                "resetForEnable() must assign the enabling plugin to " + field.name().stringValue()
                    + ", not some other value");
            reclaimed.add(field.name().stringValue());
        }

        assertEquals(IDENTITY_STATICS, reclaimed,
            "resetForEnable() must reclaim both identity statics that clearStaticServices() released; "
                + "onLoad() is the only other writer and Bukkit never calls it again on a re-enable, "
                + "so without this a plugin that drained can never be enabled again");
    }

    @Test
    void nothingInEnableRunsBeforeTheIdentityStaticsAreReclaimed() throws IOException {
        ClassModel wormholes = parse(WORMHOLES);

        for (Instruction instruction : body(wormholes, "onEnable")) {
            if (instruction instanceof InvokeInstruction invoke) {
                assertEquals(WORMHOLES + ".resetForEnable",
                    invoke.owner().asInternalName() + "." + invoke.name().stringValue(),
                    "resetForEnable() must be the first call in onEnable(); anything invoked earlier runs "
                        + "against the null Wormholes.instance that the previous drain left behind");
                return;
            }
        }

        throw new AssertionError("onEnable() must call resetForEnable()");
    }

    @Test
    void noFoliaSchedulerResultIsDiscardedInLifecycleOrRuneCode() throws IOException {
        List<String> discarded = new ArrayList<String>();

        for (String internalName : SCHEDULER_SENSITIVE_CLASSES) {
            ClassModel model = parse(internalName);
            for (MethodModel method : model.methods()) {
                Optional<CodeModel> code = method.code();
                if (code.isEmpty()) {
                    continue;
                }
                List<Instruction> body = instructions(code.get());
                for (int index = 0; index < body.size() - 1; index++) {
                    if (!(body.get(index) instanceof InvokeInstruction invoke)) {
                        continue;
                    }
                    if (!FOLIA_SCHEDULER.equals(invoke.owner().asInternalName())
                        || !invoke.name().stringValue().startsWith("run")) {
                        continue;
                    }
                    if (body.get(index + 1).opcode() == Opcode.POP) {
                        discarded.add(internalName + "." + method.methodName().stringValue()
                            + " -> FoliaScheduler." + invoke.name().stringValue());
                    }
                }
            }
        }

        assertEquals(List.of(), discarded,
            "Every FoliaScheduler schedule result must be checked; a discarded false is a silently dropped task");
    }

    @Test
    void aRejectedRollbackScheduleStillReindexesTheRunesItLeftInTheWorld() throws IOException {
        ClassModel construction = parse("art/arcane/wormholes/BlockOpsRuneConstruction");
        assertTrue(invokedMethods(construction, "rollbackRuneReservation")
                .contains("art/arcane/wormholes/BlockOpsRuneIndex.registerBlockSilently"),
            "rollbackRuneReservation() must re-index untouched runes on the rejected-schedule path, "
                + "not only inside the scheduled rollback task");
    }

    @Test
    void chunkTeardownReadsTheChunkEntryExactlyOnce() throws IOException {
        ClassModel index = parse("art/arcane/wormholes/BlockOpsRuneIndex");
        Set<String> invoked = invokedMethods(index, "destroyAllInChunk");
        assertTrue(invoked.contains("java/util/Map.remove"),
            "destroyAllInChunk() must claim the chunk entry with a single remove()");
        assertTrue(invoked.stream().noneMatch(name -> name.equals("java/util/Map.get")),
            "destroyAllInChunk() must not re-read the chunk entry after checking it; that is the NPE window");
    }

    @Test
    void chunkSnapshotToleratesAConcurrentlyRemovedChunk() throws IOException {
        ClassModel index = parse("art/arcane/wormholes/BlockOpsRuneIndex");
        boolean branchesOnNull = false;
        for (Instruction instruction : body(index, "snapshotChunk")) {
            if (instruction.opcode() == Opcode.IFNULL || instruction.opcode() == Opcode.IFNONNULL) {
                branchesOnNull = true;
            }
        }
        assertTrue(branchesOnNull, "snapshotChunk() must handle a chunk entry that vanished between calls");
    }

    @Test
    void runeLifecycleNeverPrintsAStacktraceInsteadOfLogging() throws IOException {
        for (String internalName : SCHEDULER_SENSITIVE_CLASSES) {
            ClassModel model = parse(internalName);
            for (MethodModel method : model.methods()) {
                Optional<CodeModel> code = method.code();
                if (code.isEmpty()) {
                    continue;
                }
                for (Instruction instruction : instructions(code.get())) {
                    if (instruction instanceof InvokeInstruction invoke
                        && "printStackTrace".equals(invoke.name().stringValue())) {
                        throw new AssertionError(internalName + "." + method.methodName().stringValue()
                            + " swallows a failure into printStackTrace instead of the plugin logger");
                    }
                }
            }
        }
    }

    @Test
    void theAnimationSweepNoLongerSwallowsFailuresIntoAnEmptyCatch() throws IOException {
        ClassModel index = parse("art/arcane/wormholes/BlockOpsRuneIndex");
        assertTrue(invokedMethods(index, "animatePlacedBlocksFor")
                .contains("art/arcane/wormholes/BlockOpsRuneIndex.reportAnimationFailure"),
            "animatePlacedBlocksFor() must report the failures it catches");
        assertTrue(invokedMethods(index, "reportAnimationFailure").contains("java/util/logging/Logger.log"),
            "reportAnimationFailure() must reach the logger");
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
        try (InputStream input = WormholesLifecycleBytecodeTest.class.getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(input, "Compiled class is missing: " + internalName);
            return ClassFile.of().parse(input.readAllBytes());
        }
    }
}
