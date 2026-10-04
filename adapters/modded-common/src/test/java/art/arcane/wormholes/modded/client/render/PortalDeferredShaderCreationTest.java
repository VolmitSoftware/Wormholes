package art.arcane.wormholes.modded.client.render;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortalDeferredShaderCreationTest {
    @Test
    public void normalWorldCreatesAndAssignsWithoutQueueing() {
        PortalDeferredShaderCreation<String> creation = new PortalDeferredShaderCreation<>(null);
        AtomicInteger factories = new AtomicInteger();
        AtomicReference<String> pass = new AtomicReference<>();

        String program = creation.create(() -> {
            factories.incrementAndGet();
            return "linked";
        });
        creation.assign(program, pass::set);

        assertEquals(1, factories.get());
        assertEquals("linked", pass.get());
        assertFalse(PortalIrisShaderLoading.deferred());
    }

    @Test
    public void deferredProgramsInstallIntoTheirOriginalPassesInOrder() {
        PortalDeferredShaderCreation<String> creation = new PortalDeferredShaderCreation<>(null);
        AtomicReference<String> first = new AtomicReference<>("old");
        AtomicReference<String> second = new AtomicReference<>("old");
        List<String> created = new ArrayList<>();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            creation.assign(creation.create(() -> {
                created.add("first");
                return "first";
            }), first::set);
            creation.assign(creation.create(() -> {
                created.add("second");
                return "second";
            }), second::set);
            assertNull(first.get());
            assertNull(second.get());
            assertTrue(created.isEmpty());
            assertEquals(2, loading.stats().pending());
        }
        try (loading) {
            while (loading.stats().pending() > 0) {
                loading.advance();
            }
            assertEquals(List.of("first", "second"), created);
            assertEquals("first", first.get());
            assertEquals("second", second.get());
        }
    }

    @Test
    public void closingPendingLoadingNeverCreatesCancelledPrograms() {
        PortalDeferredShaderCreation<String> creation = new PortalDeferredShaderCreation<>(null);
        AtomicInteger factories = new AtomicInteger();
        AtomicReference<String> pass = new AtomicReference<>();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            creation.assign(creation.create(() -> {
                factories.incrementAndGet();
                return "linked";
            }), pass::set);
        }

        loading.close();

        assertEquals(0, factories.get());
        assertNull(pass.get());
        assertEquals(0, loading.stats().pending());
        assertThrows(IllegalStateException.class, loading::advance);
    }

    @Test
    public void failedCreationCancelsRemainingWorkAndPreservesInstalledPrograms() {
        PortalDeferredShaderCreation<String> creation = new PortalDeferredShaderCreation<>(null);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        AtomicInteger cancelled = new AtomicInteger();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            creation.assign(creation.create(() -> "first"), first::set);
            creation.assign(creation.create(() -> {
                throw new IllegalStateException("compile failed");
            }), failed::set);
            creation.assign(creation.create(() -> {
                cancelled.incrementAndGet();
                return "cancelled";
            }), ignored -> { });
        }
        try (loading) {
            assertThrows(IllegalStateException.class, () -> {
                while (loading.stats().pending() > 0) {
                    loading.advance();
                }
            });
            assertEquals("first", first.get());
            assertNull(failed.get());
            assertEquals(0, cancelled.get());
            assertEquals(0, loading.stats().pending());
        }
    }

    @Test
    public void pendingComputeArraysRemainSafeToDestroyBeforeCompilation() {
        Integer[] empty = new Integer[0];
        PortalDeferredShaderCreation<Integer[]> creation = new PortalDeferredShaderCreation<>(empty);
        AtomicReference<Integer[]> pass = new AtomicReference<>();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            creation.assign(creation.create(() -> new Integer[]{1, 2}), pass::set);
            assertEquals(0, pass.get().length);
        }
        try (loading) {
            assertTrue(loading.advance());
            assertEquals(List.of(1, 2), List.of(pass.get()));
        }
    }
}
