package art.arcane.optics;

import art.arcane.optics.spi.OpticsMetrics;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    void mainOutputCarriesNoResources() throws IOException {
        String roots = System.getProperty("optics.resourceRoots");
        assertTrue(roots != null && !roots.isBlank(), "the build must pass the optics resource roots");
        List<String> resources = new ArrayList<String>();
        for (String root : roots.split(File.pathSeparator)) {
            Path directory = Path.of(root);
            if (!Files.exists(directory)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile).forEach(file -> resources.add(directory.relativize(file).toString()));
            }
        }
        assertEquals(List.of(), resources);
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
    void productNameInFieldNameFails() {
        byte[] bytes = probe(builder -> builder.withField("rtpTarget", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
        assertViolation(bytes, "product name rtp in its field");
    }

    @Test
    void productNameInUpperCaseConstantFails() {
        byte[] bytes = probe(builder -> builder.withField("KIND_DOOR", ConstantDescs.CD_int,
            ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));
        assertViolation(bytes, "product name door in its field");
    }

    @Test
    void productNameInMethodNameFails() {
        byte[] bytes = probe(builder -> builder.withMethod("wormholeKey", MethodTypeDesc.of(ConstantDescs.CD_void),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, method -> {
            }));
        assertViolation(bytes, "product name wormhole in its method");
    }

    @Test
    void productNameInParameterNameFails() {
        byte[] bytes = probe(builder -> builder.withMethod("scan", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object),
            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
            method -> method.with(MethodParametersAttribute.of(MethodParameterInfo.ofParameter(Optional.of("rtpTarget"), 0)))));
        assertViolation(bytes, "product name rtp in its parameter of scan");
    }

    @Test
    void productNameInRecordComponentFails() {
        byte[] bytes = probe(builder -> builder.with(RecordAttribute.of(RecordComponentInfo.of("pocketWorld", ConstantDescs.CD_int))));
        assertViolation(bytes, "product name pocket in its record component");
    }

    @Test
    void productPrefixInStringConstantFails() {
        byte[] bytes = probe(builder -> builder.withMethod("name", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc("whpn").areturn())));
        assertViolation(bytes, "product name whpn");
    }

    @Test
    void unrelatedWordsContainingProductLettersPass() {
        byte[] bytes = probe(builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withField("dirtPath", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
            .withField("whileVisible", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
            .withMethod("outdoorLight", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, method -> {
            }));
        assertEquals(List.of(), BoundaryScanner.violations(bytes));
    }

    @Test
    void staticFinalMutableObjectFails() {
        ClassDesc counter = ClassDesc.of("java.util.concurrent.atomic.AtomicInteger");
        byte[] bytes = probe(builder -> builder.withField("NEXT", counter, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL));
        assertViolation(bytes, "static mutable object NEXT");
    }

    @Test
    void staticFinalMutableAllocationFails() {
        ClassDesc map = ClassDesc.of("java.util.HashMap");
        ClassDesc declared = ClassDesc.of("java.util.Map");
        byte[] bytes = probe(builder -> builder.withField("CACHE", declared, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
            .withMethod("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
                method -> method.withCode(code -> code.new_(map).dup()
                    .invokespecial(map, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void))
                    .putstatic(PROBE, "CACHE", declared)
                    .return_())));
        assertViolation(bytes, "static mutable object CACHE created as java/util/HashMap");
    }

    @Test
    void staticFinalImmutableTablePasses() {
        ClassDesc list = ClassDesc.of("java.util.List");
        byte[] bytes = probe(builder -> builder.withField("TABLE", list, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
            .withMethod("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
                method -> method.withCode(code -> code.invokestatic(list, "of", MethodTypeDesc.of(list), true)
                    .putstatic(PROBE, "TABLE", list)
                    .return_())));
        assertEquals(List.of(), BoundaryScanner.violations(bytes));
    }

    @Test
    void reflectiveMemberLookupFails() {
        byte[] bytes = probe(builder -> builder.withMethod("lookup", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc(ConstantDescs.CD_Object).ldc("hashCode").iconst_0().anewarray(ConstantDescs.CD_Class)
                .invokevirtual(ConstantDescs.CD_Class, "getDeclaredMethod", MethodTypeDesc.of(ClassDesc.of("java.lang.reflect.Method"),
                    ConstantDescs.CD_String, ConstantDescs.CD_Class.arrayType()))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/Class.getDeclaredMethod");
    }

    @Test
    void classLoaderLookupFails() {
        ClassDesc loader = ClassDesc.of("java.lang.ClassLoader");
        byte[] bytes = probe(builder -> builder.withMethod("lookup", MethodTypeDesc.of(ConstantDescs.CD_void, loader), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.aload(0).ldc("java.lang.String")
                .invokevirtual(loader, "loadClass", MethodTypeDesc.of(ConstantDescs.CD_Class, ConstantDescs.CD_String))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/ClassLoader.loadClass");
    }

    @Test
    void lookupFindClassFails() {
        ClassDesc lookup = ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");
        byte[] bytes = probe(builder -> builder.withMethod("lookup", MethodTypeDesc.of(ConstantDescs.CD_void, lookup), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.aload(0).ldc("java.lang.String")
                .invokevirtual(lookup, "findClass", MethodTypeDesc.of(ConstantDescs.CD_Class, ConstantDescs.CD_String))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/invoke/MethodHandles$Lookup.findClass");
    }

    @Test
    void resourceLookupFails() {
        byte[] bytes = probe(builder -> builder.withMethod("lookup", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC,
            method -> method.withCode(code -> code.ldc(PROBE).ldc("data.bin")
                .invokevirtual(ConstantDescs.CD_Class, "getResourceAsStream", MethodTypeDesc.of(ClassDesc.of("java.io.InputStream"),
                    ConstantDescs.CD_String))
                .pop()
                .return_())));
        assertViolation(bytes, "java/lang/Class.getResourceAsStream");
    }

    @Test
    void serializationLibraryReferenceFails() {
        byte[] bytes = probe(builder -> builder.withField("gson", ClassDesc.of("com.google.gson.Gson"), ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL));
        assertViolation(bytes, "com/google/gson");
    }

    @Test
    void exposedInternalExceptionFails() {
        ClassDesc hidden = ClassDesc.of("art.arcane.optics.internal.scan.HiddenFailure");
        byte[] bytes = probe(builder -> builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
            .withMethod("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                method -> method.with(ExceptionsAttribute.ofSymbols(hidden))));
        assertViolation(bytes, "method run throws");
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
