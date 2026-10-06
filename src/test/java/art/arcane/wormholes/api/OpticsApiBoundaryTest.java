package art.arcane.wormholes.api;

import art.arcane.wormholes.api.traversal.TraversalQuote;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OpticsApiBoundaryTest {
    private static final String API_ROOT = "art/arcane/wormholes/api/";
    private static final String OPTICS_ROOT = "art/arcane/optics/";

    @Test
    void publicApiExposesNoOpticsType() throws IOException, URISyntaxException {
        List<String> violations = new ArrayList<String>();
        int scanned = 0;
        for (Class<?> anchor : List.<Class<?>>of(WormholesApi.class, TraversalQuote.class)) {
            Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<ApiClass> classes = Files.isDirectory(location) ? directoryClasses(location) : jarClasses(location);
            for (ApiClass type : classes) {
                scanned++;
                if (referencesOptics(type.bytes())) {
                    violations.add(type.name() + " from " + location);
                }
            }
        }
        assertTrue(scanned > 0, "no public API classes were scanned");
        assertEquals(List.of(), violations);
    }

    private static boolean referencesOptics(byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof Utf8Entry utf8 && utf8.stringValue().contains(OPTICS_ROOT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean publicApi(String name) {
        return name.startsWith(API_ROOT) && name.endsWith(".class") && !name.contains("/internal/");
    }

    private static List<ApiClass> directoryClasses(Path root) throws IOException {
        List<ApiClass> classes = new ArrayList<ApiClass>();
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (publicApi(name)) {
                classes.add(new ApiClass(name, Files.readAllBytes(file)));
            }
        }
        return classes;
    }

    private static List<ApiClass> jarClasses(Path jar) throws IOException {
        List<ApiClass> classes = new ArrayList<ApiClass>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !publicApi(entry.getName())) {
                    continue;
                }
                try (InputStream stream = archive.getInputStream(entry)) {
                    classes.add(new ApiClass(entry.getName(), stream.readAllBytes()));
                }
            }
        }
        return classes;
    }

    private record ApiClass(String name, byte[] bytes) {
    }
}
