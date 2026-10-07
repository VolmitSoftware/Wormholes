package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalCompositeLoadingMixin;
import art.arcane.wormholes.modded.mixin.client.IrisPortalFinalLoadingMixin;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPendingPassMixin;
import art.arcane.wormholes.modded.mixin.client.IrisPortalShadowCompositeLoadingMixin;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.withSettings;

public class PortalIrisProgramLoadingTest {
    @Test
    public void shadowProgramsFinishBeforeClearPassesAndUniformOptimisation() {
        IrisRenderingPipeline pipeline = mock(IrisRenderingPipeline.class, withSettings().extraInterfaces(PortalDeferredShaderPipeline.class));
        PortalDeferredShaderPipeline deferred = (PortalDeferredShaderPipeline) pipeline;
        ProgramSet programs = mock(ProgramSet.class);
        List<String> stages = new ArrayList<>();
        PortalIrisShaderLoading loading;
        try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
            loading = scope.loading();
            loading.attach(pipeline, programs);
        }
        doAnswer(invocation -> {
            assertTrue(PortalIrisShaderLoading.deferred());
            PortalIrisShaderLoading.defer(() -> {
                assertFalse(loading.ready());
                stages.add("shadow");
            });
            return null;
        }).when(deferred).wormholes$finishShaders(programs);
        doAnswer(invocation -> {
            stages.add("complete");
            return null;
        }).when(deferred).wormholes$completeShaders();

        try (loading) {
            while (!loading.ready()) {
                loading.advance(4_000_000L);
            }
            assertEquals(List.of("shadow", "complete"), stages);
            assertFalse(PortalIrisShaderLoading.deferred());
        }
    }

    @Test
    public void allPostprocessHooksCompileThenAssignTheirOriginalPass() throws ReflectiveOperationException {
        for (Object renderer : renderers()) {
            Program linked = mock(Program.class);
            Object pass = new Object();
            AtomicReference<Program> installed = new AtomicReference<>();
            AtomicInteger factories = new AtomicInteger();
            Operation<Program> compiler = arguments -> {
                factories.incrementAndGet();
                assertEquals(4, arguments.length);
                return linked;
            };
            Operation<Void> assignment = arguments -> {
                assertSame(pass, arguments[0]);
                installed.set((Program) arguments[1]);
                return null;
            };
            PortalIrisShaderLoading loading;
            try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
                loading = scope.loading();
                Object pending = method(renderer, "wormholes$deferProgram").invoke(renderer, null, ImmutableSet.of(), ImmutableSet.of(), null, compiler);
                method(renderer, "wormholes$assignProgram").invoke(renderer, pass, pending, assignment);
                assertNull(pending);
                assertNull(installed.get());
                assertEquals(0, factories.get());
            }
            try (loading) {
                assertTrue(loading.advance(4_000_000L));
                assertEquals(1, factories.get());
                assertSame(linked, installed.get());
            }
        }
    }

    @Test
    public void allPostprocessComputeHooksKeepEmptyArraysUntilAssigned() throws ReflectiveOperationException {
        for (Object renderer : renderers()) {
            ComputeProgram[] linked = new ComputeProgram[]{mock(ComputeProgram.class)};
            ComputeSource[] sources = new ComputeSource[1];
            Object pass = new Object();
            AtomicReference<ComputeProgram[]> installed = new AtomicReference<>();
            AtomicInteger factories = new AtomicInteger();
            Operation<ComputeProgram[]> compiler = arguments -> {
                assertSame(sources, arguments[0]);
                assertEquals(5, arguments.length);
                factories.incrementAndGet();
                return linked;
            };
            Operation<Void> assignment = arguments -> {
                assertSame(pass, arguments[0]);
                installed.set((ComputeProgram[]) arguments[1]);
                return null;
            };
            PortalIrisShaderLoading loading;
            try (PortalIrisShaderLoading.Scope scope = PortalIrisShaderLoading.constructing()) {
                loading = scope.loading();
                Object pending = method(renderer, "wormholes$deferComputes").invoke(renderer, sources, ImmutableSet.of(), ImmutableSet.of(), null, null, compiler);
                method(renderer, "wormholes$assignComputes").invoke(renderer, pass, pending, assignment);
                assertEquals(0, installed.get().length);
                assertEquals(0, factories.get());
            }
            try (loading) {
                assertTrue(loading.advance(4_000_000L));
                assertSame(linked, installed.get());
                assertEquals(1, factories.get());
            }
        }
    }

    @Test
    public void pendingPassCleanupSkipsUncreatedProgramsAndDestroysLinkedPrograms() throws ReflectiveOperationException {
        IrisPortalPendingPassMixin pass = new IrisPortalPendingPassMixin() { };
        Program linked = mock(Program.class);
        AtomicInteger destroyed = new AtomicInteger();
        Operation<Void> original = arguments -> {
            assertSame(linked, arguments[0]);
            destroyed.incrementAndGet();
            return null;
        };
        Method cleanup = method(pass, "wormholes$destroyLinkedProgram");

        cleanup.invoke(pass, null, original);
        assertEquals(0, destroyed.get());
        cleanup.invoke(pass, linked, original);
        assertEquals(1, destroyed.get());
    }

    private static Object[] renderers() {
        return new Object[]{new IrisPortalCompositeLoadingMixin() { }, new IrisPortalFinalLoadingMixin() { },
            new IrisPortalShadowCompositeLoadingMixin() { }};
    }

    private static Method method(Object target, String name) throws NoSuchMethodException {
        for (Method method : target.getClass().getSuperclass().getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
