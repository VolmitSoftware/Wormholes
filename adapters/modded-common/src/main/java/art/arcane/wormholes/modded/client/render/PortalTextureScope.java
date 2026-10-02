package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
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

    private final int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
    private final int[][] textures = new int[GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS)][TARGETS.length];
    private final int[] samplers = new int[textures.length];
    private final int[] storage;
    private final int storageBuffer;
    private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
    private final int indexBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);

    PortalTextureScope() {
        for (int unit = 0; unit < textures.length; unit++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            for (int target = 0; target < TARGETS.length; target++) {
                textures[unit][target] = GL11C.glGetInteger(BINDINGS[target]);
            }
            samplers[unit] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        }
        GlStateManager._activeTexture(active);
        boolean ssbo = GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_shader_storage_buffer_object;
        storageBuffer = ssbo ? GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING) : 0;
        storage = new int[ssbo ? GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0];
        for (int slot = 0; slot < storage.length; slot++) {
            storage[slot] = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, slot);
        }
    }

    @Override
    public void close() {
        for (int slot = 0; slot < storage.length; slot++) {
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, slot, storage[slot]);
        }
        for (int unit = 0; unit < textures.length; unit++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
            for (int target = 0; target < TARGETS.length; target++) {
                if (TARGETS[target] == GL11C.GL_TEXTURE_2D) {
                    GlStateManager._bindTexture(textures[unit][target]);
                } else {
                    GL11C.glBindTexture(TARGETS[target], textures[unit][target]);
                }
            }
            GL33C.glBindSampler(unit, samplers[unit]);
        }
        GlStateManager._activeTexture(active);
        if (storage.length > 0) {
            GlStateManager._glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, storageBuffer);
        }
        GlStateManager._glUseProgram(program);
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
    }
}
