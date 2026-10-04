package art.arcane.wormholes.modded.client;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PreparedTerrainFadeBoundaryTest {
    @Test
    public void nativeFadeRequiresExactMainUploadedSectionAndPersistsAfterCoverRetirement() throws IOException {
        MethodNode callback = method("PreparedSectionFadeMixin", "wormholesPreparedSectionVisibility");
        List<String> calls = calls(callback);
        assertTrue(calls.indexOf("coversMainSection") < calls.indexOf("setReturnValue"));
        assertTrue(calls.indexOf("getRenderSectionAt") < calls.indexOf("setReturnValue"));
        assertTrue(calls.indexOf("getSectionMesh") < calls.indexOf("setReturnValue"));
        assertTrue(calls.indexOf("chunkSectionFadeInTime") < calls.indexOf("setReturnValue"));
        boolean persists = false;
        for (AbstractInsnNode instruction : callback.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("uploadedTime") && field.getOpcode() == 181) {
                persists = true;
            }
        }
        assertTrue(persists);
    }

    @Test
    public void sodiumConsumesOriginalFadeFlagAtActualUploadBeforeScopedOverride() throws IOException {
        MethodNode callback = method("SodiumPreparedSectionFadeMixin", "wormholesPreparedSectionFade");
        List<String> calls = calls(callback);
        assertTrue(calls.indexOf("call") < calls.indexOf("coversMainSection"));
        assertTrue(calls.containsAll(List.of("getChunkX", "getChunkY", "getChunkZ")));
        AnnotationNode wrap = callback.visibleAnnotations.getFirst();
        AnnotationNode point = (AnnotationNode) ((List<?>) value(wrap, "at")).getFirst();
        assertEquals("Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;consumeFade()Z", value(point, "target"));
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError(key);
    }

    private static List<String> calls(MethodNode method) {
        List<String> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                result.add(call.name);
            }
        }
        return result;
    }

    private static MethodNode method(String type, String name) throws IOException {
        try (InputStream bytes = PreparedTerrainFadeBoundaryTest.class.getResourceAsStream(
            "/art/arcane/wormholes/modded/mixin/client/" + type + ".class")) {
            assertNotNull(bytes);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (MethodNode method : node.methods) {
                if (method.name.equals(name)) {
                    return method;
                }
            }
        }
        throw new AssertionError(name);
    }
}
