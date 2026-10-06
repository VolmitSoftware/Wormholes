package art.arcane.optics;

import art.arcane.optics.spi.OpticsMetrics;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpticsBoundaryTest {
    private static final ClassDesc PROBE = ClassDesc.of("art.arcane.optics.Probe");

    @Test
    void mainClassesStayInsideTheOpticsBoundary() throws IOException, URISyntaxException {
        Path root = Path.of(OpticsMetrics.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "optics main classes must be scanned from their output directory: " + root);
        List<String> violations = new ArrayList<String>();
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        assertFalse(files.isEmpty(), "no optics classes found under " + root);
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (!relative.endsWith(".class")) {
                violations.add("non-class entry in the optics output: " + relative);
                continue;
            }
            if (!relative.startsWith("art/arcane/optics/")) {
                violations.add("class outside the art.arcane.optics root: " + relative);
                continue;
            }
            violations.addAll(BoundaryScanner.violations(Files.readAllBytes(file)));
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void cleanClassPasses() {
        assertEquals(List.of(), BoundaryScanner.violations(probe(builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL))));
    }

    @Test
    void platformTypeReferenceFails() {
        byte[] bytes = probe(builder -> builder.withField("server", ClassDesc.of("org.bukkit.Server"), ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
        assertViolation(bytes, "org/bukkit");
    }

    @Test
    void productTypeReferenceFails() {
        byte[] bytes = probe(builder -> builder.withField("portal", ClassDesc.of("art.arcane.wormholes.portal.IPortal"), ClassFile.ACC_PRIVATE));
        assertViolation(bytes, "art/arcane/wormholes");
    }

    @Test
    void reflectiveClassLookupFails() {
        byte[] bytes = probe(builder -> builder.withMethod("lookup", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc("java.lang.String")
                .invokestatic(ConstantDescs.CD_Class, "forName", MethodTypeDesc.of(ConstantDescs.CD_Class, ConstantDescs.CD_String))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/Class.forName");
    }

    @Test
    void serviceLoaderFails() {
        ClassDesc serviceLoader = ClassDesc.of("java.util.ServiceLoader");
        byte[] bytes = probe(builder -> builder.withMethod("load", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc(ConstantDescs.CD_Object)
                .invokestatic(serviceLoader, "load", MethodTypeDesc.of(serviceLoader, ConstantDescs.CD_Class))
                .pop()
                .return_())));
        assertViolation(bytes, "java/util/ServiceLoader");
    }

    @Test
    void systemPropertyReadFails() {
        ClassDesc system = ClassDesc.of("java.lang.System");
        byte[] bytes = probe(builder -> builder.withMethod("read", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc("key")
                .invokestatic(system, "getProperty", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/System.getProperty");
    }

    @Test
    void productNameInStringConstantFails() {
        byte[] bytes = probe(builder -> builder.withMethod("name", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc("Wormholes-Plate-").areturn())));
        assertViolation(bytes, "Wormholes-Plate-");
    }

    @Test
    void productNameInSimpleNameFails() {
        byte[] bytes = ClassFile.of().build(ClassDesc.of("art.arcane.optics.DoorPlan"), builder -> builder.withFlags(ClassFile.ACC_FINAL));
        assertViolation(bytes, "Door");
    }

    @Test
    void exposedInternalTypeFails() {
        ClassDesc hidden = ClassDesc.of("art.arcane.optics.internal.scan.Hidden");
        byte[] bytes = probe(builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withMethod("hidden", MethodTypeDesc.of(hidden), ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, method -> {
            }));
        assertViolation(bytes, "exposes an internal type");
    }

    @Test
    void internalTypeOnPrivateMemberPasses() {
        ClassDesc hidden = ClassDesc.of("art.arcane.optics.internal.scan.Hidden");
        byte[] bytes = probe(builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withField("hidden", hidden, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
        assertEquals(List.of(), BoundaryScanner.violations(bytes));
    }

    @Test
    void staticMutableFieldFails() {
        byte[] bytes = probe(builder -> builder.withField("counter", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC));
        assertViolation(bytes, "static mutable field counter");
    }

    private static byte[] probe(Consumer<ClassBuilder> body) {
        return ClassFile.of().build(PROBE, body);
    }

    private static void assertViolation(byte[] bytes, String expected) {
        List<String> violations = BoundaryScanner.violations(bytes);
        assertTrue(violations.stream().anyMatch(violation -> violation.contains(expected)),
            "expected a violation mentioning " + expected + " but got " + violations);
    }
}
