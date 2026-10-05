package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalDepthTargetMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.targets.RenderTargets;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisDepthTargetTest {
    @Test
    public void equalTargetVersionsRebindDepthWithoutResizingRetainedAttachments() throws Exception {
        Fixture fixture = new Fixture(1);
        GpuTexture prepared = mock(GpuTexture.class);
        assertFalse(fixture.targets.resizeIfNeeded(1, prepared, 960, 540, DepthBufferFormat.DEPTH32F, null));
        assertSame(fixture.main, fixture.targets.getDepthTexture());
        verify(fixture.depthFramebuffer, never()).addDepthAttachment(prepared);

        assertFalse(fixture.resize(1, prepared));
        assertSame(prepared, fixture.targets.getDepthTexture());
        assertEquals(1, fixture.version());
        verify(fixture.depthFramebuffer).addDepthAttachment(prepared);
        verify(fixture.colorFramebuffer, never()).addDepthAttachment(prepared);
        assertSame(fixture.opaque, fixture.targets.getDepthTextureNoTranslucents());
        assertSame(fixture.beforeHand, fixture.targets.getDepthTextureNoHand());
        assertFalse(fixture.targets.isFullClearRequired());

        assertFalse(fixture.resize(1, fixture.main));
        assertSame(fixture.main, fixture.targets.getDepthTexture());
        verify(fixture.depthFramebuffer).addDepthAttachment(fixture.main);
        assertFalse(fixture.resize(1, fixture.main));
        verify(fixture.depthFramebuffer, times(1)).addDepthAttachment(fixture.main);
    }

    @Test
    public void sameDepthPreservesVanillaVersionChangesAndCounterWraparound() throws Exception {
        Fixture fixture = new Fixture(Integer.MAX_VALUE);
        assertFalse(fixture.resize(Integer.MIN_VALUE, fixture.main));
        assertEquals(Integer.MIN_VALUE, fixture.version());
        verify(fixture.depthFramebuffer).addDepthAttachment(fixture.main);
        GpuTexture prepared = mock(GpuTexture.class);
        assertFalse(fixture.resize(Integer.MIN_VALUE, prepared));
        assertEquals(Integer.MIN_VALUE, fixture.version());
        assertSame(prepared, fixture.targets.getDepthTexture());
        verify(fixture.depthFramebuffer).addDepthAttachment(prepared);
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture {
        private final RenderTargets targets = mock(RenderTargets.class, CALLS_REAL_METHODS);
        private final IrisPortalDepthTargetMixin mixin = new IrisPortalDepthTargetMixin() {
        };
        private final GpuTexture main = mock(GpuTexture.class);
        private final GpuTexture opaque = mock(GpuTexture.class);
        private final GpuTexture beforeHand = mock(GpuTexture.class);
        private final GlFramebuffer depthFramebuffer = mock(GlFramebuffer.class);
        private final GlFramebuffer colorFramebuffer = mock(GlFramebuffer.class);
        private final Field version;
        private final Method depthIdentity;

        private Fixture(int initialVersion) throws Exception {
            set(targets, RenderTargets.class, "currentDepthTexture", main);
            set(targets, RenderTargets.class, "currentDepthFormat", DepthBufferFormat.DEPTH32F);
            set(targets, RenderTargets.class, "cachedWidth", 960);
            set(targets, RenderTargets.class, "cachedHeight", 540);
            set(targets, RenderTargets.class, "cachedDepthBufferVersion", initialVersion);
            set(targets, RenderTargets.class, "noTranslucents", opaque);
            set(targets, RenderTargets.class, "noHand", beforeHand);
            set(targets, RenderTargets.class, "ownedFramebuffers", List.of(depthFramebuffer, colorFramebuffer));
            when(depthFramebuffer.hasDepthAttachment()).thenReturn(true);
            version = RenderTargets.class.getDeclaredField("cachedDepthBufferVersion");
            version.setAccessible(true);
            depthIdentity = IrisPortalDepthTargetMixin.class.getDeclaredMethod("wormholes$depthIdentity",
                RenderTargets.class, Operation.class, int.class, GpuTexture.class);
            depthIdentity.setAccessible(true);
        }

        private int version() throws IllegalAccessException {
            return version.getInt(targets);
        }

        private boolean resize(int inputVersion, GpuTexture depth) throws Exception {
            set(mixin, IrisPortalDepthTargetMixin.class, "currentDepthTexture", targets.getDepthTexture());
            int prior = version();
            int compared = (Integer) depthIdentity.invoke(mixin, targets, (Operation<Integer>) arguments -> prior,
                inputVersion, depth);
            version.setInt(targets, compared);
            return targets.resizeIfNeeded(inputVersion, depth, 960, 540, DepthBufferFormat.DEPTH32F, null);
        }
    }
}
