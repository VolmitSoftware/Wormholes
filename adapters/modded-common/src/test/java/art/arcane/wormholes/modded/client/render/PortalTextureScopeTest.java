package art.arcane.wormholes.modded.client.render;

import org.junit.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PortalTextureScopeTest {
    @Test
    public void sixViewsReadTextureBindingsOnceAndKeepPerViewProgramAndStorageIsolation() {
        StateBindings bindings = new StateBindings();
        State source = bindings.state();
        try (PortalTextureScope.Batch batch = PortalTextureScope.batch(bindings)) {
            for (int view = 0; view < 6; view++) {
                State before = bindings.state();
                try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
                    bindings.change(200 + view);
                }
                State after = bindings.state();
                assertEquals(before.active(), after.active());
                assertEquals(before.program(), after.program());
                assertEquals(before.vertexArray(), after.vertexArray());
                assertEquals(before.indexBuffer(), after.indexBuffer());
                assertEquals(before.storage(), after.storage());
                assertEquals(before.storageBuffer(), after.storageBuffer());
            }
            assertEquals(4 * 7, bindings.textureQueries);
            assertEquals(3 * 7, bindings.storageQueries);
        }
        assertEquals(source, bindings.state());
    }

    @Test
    public void changingViewOrderAndMidBatchFailureRestoreEverySourceTextureTargetAndSampler() {
        StateBindings bindings = new StateBindings();
        State source = bindings.state();
        assertThrows(IllegalStateException.class, () -> {
            try (PortalTextureScope.Batch batch = PortalTextureScope.batch(bindings)) {
                for (int view : new int[]{5, 2, 4, 0, 3, 1}) {
                    try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
                        bindings.change(400 + view);
                        if (view == 0) {
                            throw new IllegalStateException("destination draw failed");
                        }
                    }
                }
            }
        });
        assertEquals(source, bindings.state());
        bindings.change(800);
        State followingSource = bindings.state();
        try (PortalTextureScope.Batch batch = PortalTextureScope.batch(bindings)) {
            try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
                bindings.change(900);
            }
        }
        assertEquals(followingSource, bindings.state());
    }

    @Test
    public void nestedBatchRestoresItsActualCallerBindingsBeforeOuterBatchRestoresSource() {
        StateBindings bindings = new StateBindings();
        State source = bindings.state();
        try (PortalTextureScope.Batch outer = PortalTextureScope.batch(bindings)) {
            bindings.change(300);
            State parent = bindings.state();
            try (PortalTextureScope.Batch child = PortalTextureScope.batch(bindings)) {
                try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
                    bindings.change(500);
                }
            }
            assertEquals(parent, bindings.state());
            try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
                bindings.change(700);
            }
        }
        assertEquals(source, bindings.state());
        assertEquals(4 * 7 * 2, bindings.textureQueries);
    }

    @Test
    public void standaloneAndOtherRenderContextsKeepFullImmediateRestoration() {
        StateBindings bindings = new StateBindings();
        State source = bindings.state();
        try (PortalTextureScope scope = new PortalTextureScope(bindings)) {
            bindings.change(600);
        }
        assertEquals(source, bindings.state());
        StateBindings other = new StateBindings();
        State otherSource = other.state();
        try (PortalTextureScope.Batch batch = PortalTextureScope.batch(bindings)) {
            try (PortalTextureScope scope = new PortalTextureScope(other)) {
                other.change(800);
            }
            assertEquals(otherSource, other.state());
        }
        assertEquals(source, bindings.state());
    }

    private record State(int active, int program, int vertexArray, int indexBuffer, int storageBuffer,
                         List<List<Integer>> textures, List<Integer> samplers, List<Integer> storage) {
    }

    private static final class StateBindings implements PortalTextureScope.Bindings {
        private final int[][] textures = new int[4][6];
        private final int[] samplers = new int[4];
        private final int[] storage = new int[3];
        private int active;
        private int program;
        private int vertexArray;
        private int indexBuffer;
        private int storageBuffer;
        private int textureQueries;
        private int storageQueries;

        private StateBindings() {
            change(100);
        }

        @Override
        public int integer(int parameter) {
            return switch (parameter) {
                case GL13C.GL_ACTIVE_TEXTURE -> active;
                case GL20C.GL_CURRENT_PROGRAM -> program;
                case GL30C.GL_VERTEX_ARRAY_BINDING -> vertexArray;
                case GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING -> indexBuffer;
                case GL20C.GL_MAX_TEXTURE_IMAGE_UNITS -> textures.length;
                case GL43C.GL_SHADER_STORAGE_BUFFER_BINDING -> storageBuffer;
                case GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS -> storage.length;
                case GL33C.GL_SAMPLER_BINDING -> {
                    textureQueries++;
                    yield samplers[active - GL13C.GL_TEXTURE0];
                }
                default -> {
                    int index = switch (parameter) {
                        case GL11C.GL_TEXTURE_BINDING_1D -> 0;
                        case GL11C.GL_TEXTURE_BINDING_2D -> 1;
                        case GL13C.GL_TEXTURE_BINDING_3D -> 2;
                        case GL13C.GL_TEXTURE_BINDING_CUBE_MAP -> 3;
                        case GL30C.GL_TEXTURE_BINDING_2D_ARRAY -> 4;
                        case GL31C.GL_TEXTURE_BINDING_RECTANGLE -> 5;
                        default -> throw new IllegalArgumentException("Unexpected GL query " + parameter);
                    };
                    textureQueries++;
                    yield textures[active - GL13C.GL_TEXTURE0][index];
                }
            };
        }

        @Override
        public int indexedInteger(int parameter, int index) {
            assertEquals(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, parameter);
            storageQueries++;
            return storage[index];
        }

        @Override
        public boolean storageSupported() {
            return true;
        }

        @Override
        public void activeTexture(int texture) {
            active = texture;
        }

        @Override
        public void texture(int target, int texture) {
            int index = switch (target) {
                case GL11C.GL_TEXTURE_1D -> 0;
                case GL11C.GL_TEXTURE_2D -> 1;
                case GL13C.GL_TEXTURE_3D -> 2;
                case GL13C.GL_TEXTURE_CUBE_MAP -> 3;
                case GL30C.GL_TEXTURE_2D_ARRAY -> 4;
                case GL31C.GL_TEXTURE_RECTANGLE -> 5;
                default -> throw new IllegalArgumentException("Unexpected GL target " + target);
            };
            textures[active - GL13C.GL_TEXTURE0][index] = texture;
        }

        @Override
        public void sampler(int unit, int sampler) {
            samplers[unit] = sampler;
        }

        @Override
        public void storage(int slot, int buffer) {
            storage[slot] = buffer;
            storageBuffer = buffer;
        }

        @Override
        public void buffer(int target, int buffer) {
            if (target == GL43C.GL_SHADER_STORAGE_BUFFER) {
                storageBuffer = buffer;
            } else if (target == GL15C.GL_ELEMENT_ARRAY_BUFFER) {
                indexBuffer = buffer;
            } else {
                throw new IllegalArgumentException("Unexpected GL buffer " + target);
            }
        }

        @Override
        public void program(int value) {
            program = value;
        }

        @Override
        public void vertexArray(int array) {
            vertexArray = array;
        }

        private void change(int base) {
            active = GL13C.GL_TEXTURE0 + 2;
            program = base + 1;
            vertexArray = base + 2;
            indexBuffer = base + 3;
            storageBuffer = base + 4;
            for (int unit = 0; unit < textures.length; unit++) {
                for (int target = 0; target < textures[unit].length; target++) {
                    textures[unit][target] = base + 10 + unit * 6 + target;
                }
                samplers[unit] = base + 40 + unit;
            }
            for (int index = 0; index < storage.length; index++) {
                storage[index] = base + 50 + index;
            }
        }

        private State state() {
            List<List<Integer>> targets = new ArrayList<>(textures.length);
            for (int[] unit : textures) {
                targets.add(Arrays.stream(unit).boxed().toList());
            }
            return new State(active, program, vertexArray, indexBuffer, storageBuffer, List.copyOf(targets),
                Arrays.stream(samplers).boxed().toList(), Arrays.stream(storage).boxed().toList());
        }
    }
}
