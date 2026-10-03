package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;

final class PortalTextureScope implements AutoCloseable {
    private static final int[] TARGETS = {GL11C.GL_TEXTURE_1D, GL11C.GL_TEXTURE_2D, GL13C.GL_TEXTURE_3D,
        GL13C.GL_TEXTURE_CUBE_MAP, GL30C.GL_TEXTURE_2D_ARRAY, GL31C.GL_TEXTURE_RECTANGLE};
    private static final int[] BINDINGS = {GL11C.GL_TEXTURE_BINDING_1D, GL11C.GL_TEXTURE_BINDING_2D, GL13C.GL_TEXTURE_BINDING_3D,
        GL13C.GL_TEXTURE_BINDING_CUBE_MAP, GL30C.GL_TEXTURE_BINDING_2D_ARRAY, GL31C.GL_TEXTURE_BINDING_RECTANGLE};
    private static final Bindings OPEN_GL = new OpenGlBindings();
    private static Batch batch;

    private final Bindings bindings;
    private final int active;
    private final int[][] textures;
    private final int[] samplers;
    private final int[] storage;
    private final int storageBuffer;
    private final int program;
    private final int vertexArray;
    private final int indexBuffer;
    private boolean closed;

    PortalTextureScope() {
        this(OPEN_GL);
    }

    PortalTextureScope(Bindings bindings) {
        this(bindings, batch == null || batch.bindings != bindings);
    }

    private PortalTextureScope(Bindings bindings, boolean captureTextures) {
        this.bindings = bindings;
        active = bindings.integer(GL13C.GL_ACTIVE_TEXTURE);
        program = bindings.integer(GL20C.GL_CURRENT_PROGRAM);
        vertexArray = bindings.integer(GL30C.GL_VERTEX_ARRAY_BINDING);
        indexBuffer = bindings.integer(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        textures = captureTextures ? new int[bindings.integer(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS)][TARGETS.length] : null;
        samplers = captureTextures ? new int[textures.length] : null;
        if (captureTextures) {
            for (int unit = 0; unit < textures.length; unit++) {
                bindings.activeTexture(GL13C.GL_TEXTURE0 + unit);
                for (int target = 0; target < TARGETS.length; target++) {
                    textures[unit][target] = bindings.integer(BINDINGS[target]);
                }
                samplers[unit] = bindings.integer(GL33C.GL_SAMPLER_BINDING);
            }
            bindings.activeTexture(active);
        }
        boolean ssbo = bindings.storageSupported();
        storageBuffer = ssbo ? bindings.integer(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING) : 0;
        storage = new int[ssbo ? bindings.integer(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0];
        for (int slot = 0; slot < storage.length; slot++) {
            storage[slot] = bindings.indexedInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
        }
    }

    static Batch batch() {
        return PortalShaderScope.shaders() ? batch(OPEN_GL) : null;
    }

    static Batch batch(Bindings bindings) {
        return new Batch(bindings);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int slot = 0; slot < storage.length; slot++) {
            bindings.storage(slot, storage[slot]);
        }
        if (textures != null) {
            for (int unit = 0; unit < textures.length; unit++) {
                bindings.activeTexture(GL13C.GL_TEXTURE0 + unit);
                for (int target = 0; target < TARGETS.length; target++) {
                    bindings.texture(TARGETS[target], textures[unit][target]);
                }
                bindings.sampler(unit, samplers[unit]);
            }
        }
        bindings.activeTexture(active);
        if (storage.length > 0) {
            bindings.buffer(GL43C.GL_SHADER_STORAGE_BUFFER, storageBuffer);
        }
        bindings.program(program);
        bindings.vertexArray(vertexArray);
        bindings.buffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
    }

    interface Bindings {
        int integer(int parameter);

        int indexedInteger(int parameter, int index);

        boolean storageSupported();

        void activeTexture(int texture);

        void texture(int target, int texture);

        void sampler(int unit, int sampler);

        void storage(int slot, int buffer);

        void buffer(int target, int buffer);

        void program(int program);

        void vertexArray(int array);
    }

    static final class Batch implements AutoCloseable {
        private final Batch previous;
        private final Bindings bindings;
        private final PortalTextureScope source;
        private boolean closed;

        private Batch(Bindings bindings) {
            this.bindings = bindings;
            previous = batch;
            source = new PortalTextureScope(bindings, true);
            batch = this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (batch != this) {
                throw new IllegalStateException("Portal texture batch scope order");
            }
            closed = true;
            batch = previous;
            source.close();
        }
    }

    private static final class OpenGlBindings implements Bindings {
        @Override
        public int integer(int parameter) {
            return GL11C.glGetInteger(parameter);
        }

        @Override
        public int indexedInteger(int parameter, int index) {
            return GL30C.glGetIntegeri(parameter, index);
        }

        @Override
        public boolean storageSupported() {
            return GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_shader_storage_buffer_object;
        }

        @Override
        public void activeTexture(int texture) {
            GlStateManager._activeTexture(texture);
        }

        @Override
        public void texture(int target, int texture) {
            if (target == GL11C.GL_TEXTURE_2D) {
                GlStateManager._bindTexture(texture);
            } else {
                GL11C.glBindTexture(target, texture);
            }
        }

        @Override
        public void sampler(int unit, int sampler) {
            GL33C.glBindSampler(unit, sampler);
            if (PortalShaderScope.irisPresent()) {
                IrisRenderSystem.bindSamplerToUnit(unit, sampler);
            }
        }

        @Override
        public void storage(int slot, int buffer) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, slot, buffer);
        }

        @Override
        public void buffer(int target, int buffer) {
            GlStateManager._glBindBuffer(target, buffer);
        }

        @Override
        public void program(int program) {
            GlStateManager._glUseProgram(program);
        }

        @Override
        public void vertexArray(int array) {
            GlStateManager._glBindVertexArray(array);
        }
    }
}
