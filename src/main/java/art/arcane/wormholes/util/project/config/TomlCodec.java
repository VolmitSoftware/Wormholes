package art.arcane.wormholes.util.project.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TomlCodec {
    private static final Logger LOGGER = Logger.getLogger("Wormholes");
    private static final int PARSE_RETRY_ATTEMPTS = 4;
    private static final long PARSE_RETRY_BACKOFF_MS = 60L;

    private TomlCodec() {
    }

    public static <T> T loadOrCreate(File tomlFile, Class<T> type) {
        T defaults = newInstance(type);

        if (!tomlFile.exists()) {
            writeCanonical(tomlFile, defaults);
            return defaults;
        }

        LoadResult<T> result = readExisting(tomlFile, type);
        if (!result.isSuccess()) {
            LOGGER.log(Level.WARNING, "Failed to parse " + tomlFile.getName() + " after " + PARSE_RETRY_ATTEMPTS + " attempts; using defaults for this standalone load (file left untouched).", result.error());
            return defaults;
        }

        writeCanonical(tomlFile, result.value());
        return result.value();
    }

    public static <T> LoadResult<T> readExisting(File tomlFile, Class<T> type) {
        if (tomlFile == null || !tomlFile.isFile()) {
            return new LoadResult<>(null, new IOException("Configuration file does not exist: " + tomlFile));
        }
        T defaults = newInstance(type);
        ReadAttempt<T> attempt = readWithRetries(tomlFile, type, defaults);
        return new LoadResult<>(attempt.value(), attempt.error());
    }

    public static <T> LoadResult<T> readContent(String content, Class<T> type) {
        if (content == null || content.isBlank()) {
            return new LoadResult<>(null, new IOException("Configuration content is empty"));
        }
        try {
            T defaults = newInstance(type);
            UnmodifiableConfig toml = new TomlParser().parse(content);
            T fresh = newInstance(type);
            T applied = applyToml(toml, fresh, type);
            copySectionRefs(applied, defaults);
            return new LoadResult<>(applied, null);
        } catch (Exception failure) {
            return new LoadResult<>(null, failure);
        }
    }

    public static void writeCanonical(File tomlFile, Object instance) {
        File parent = tomlFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        String next = canonicalContent(instance);
        try {
            if (tomlFile.isFile()) {
                String existing = Files.readString(tomlFile.toPath(), StandardCharsets.UTF_8);
                if (existing.equals(next)) {
                    return;
                }
            }
            atomicWrite(tomlFile.toPath(), next);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + tomlFile, e);
        }
    }

    public static String canonicalContent(Object instance) {
        StringBuilder out = new StringBuilder(2048);
        Class<?> type = instance.getClass();
        ConfigDoc classDoc = type.getAnnotation(ConfigDoc.class);
        if (classDoc != null) {
            for (String line : classDoc.value()) {
                out.append("# ").append(line).append('\n');
            }
            out.append('\n');
        }

        try {
            writeObjectContents(out, instance, "");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read config field", e);
        }
        return out.toString();
    }

    private static void writeObjectContents(StringBuilder out, Object instance, String sectionPath) throws IllegalAccessException {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || isSection(field.getType()) || isSectionList(field)) {
                continue;
            }
            field.setAccessible(true);
            if (field.get(instance) != null) {
                writeField(out, field, instance, "");
            }
        }

        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || !isSection(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object section = field.get(instance);
            if (!hasWritableContent(section)) {
                continue;
            }
            String name = toTomlKey(field.getName());
            String nestedPath = sectionPath.isEmpty() ? name : sectionPath + "." + name;
            out.append('\n');
            appendDoc(out, section.getClass().getAnnotation(ConfigDoc.class));
            out.append('[').append(nestedPath).append("]\n");
            writeObjectContents(out, section, nestedPath);
        }

        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field) || !isSectionList(field)) {
                continue;
            }
            field.setAccessible(true);
            List<?> entries = (List<?>) field.get(instance);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            String name = toTomlKey(field.getName());
            String nestedPath = sectionPath.isEmpty() ? name : sectionPath + "." + name;
            for (Object entry : entries) {
                if (entry == null) {
                    continue;
                }
                out.append('\n');
                appendDescription(out, field.getAnnotation(ConfigDescription.class));
                out.append("[[").append(nestedPath).append("]]\n");
                writeObjectContents(out, entry, nestedPath);
            }
        }
    }

    private static boolean hasWritableContent(Object instance) throws IllegalAccessException {
        if (instance == null) {
            return false;
        }
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (!isSerializableField(field)) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(instance);
            if (isSectionList(field)) {
                if (value instanceof List<?> entries && !entries.isEmpty()) {
                    return true;
                }
                continue;
            }
            if (isSection(field.getType())) {
                if (hasWritableContent(value)) {
                    return true;
                }
                continue;
            }
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSerializableField(Field field) {
        return !Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers());
    }

    private static void appendDoc(StringBuilder out, ConfigDoc doc) {
        if (doc == null) {
            return;
        }
        for (String line : doc.value()) {
            out.append("# ").append(line).append('\n');
        }
    }

    private static void appendDescription(StringBuilder out, ConfigDescription description) {
        if (description == null) {
            return;
        }
        for (String line : description.value()) {
            out.append("# ").append(line).append('\n');
        }
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Config type " + type.getName() + " must have a public no-arg constructor", e);
        }
    }

    private static <T> ReadAttempt<T> readWithRetries(File tomlFile, Class<T> type, T defaults) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= PARSE_RETRY_ATTEMPTS; attempt++) {
            try {
                long sizeBefore = tomlFile.length();
                if (sizeBefore <= 0L) {
                    sleepBackoff(attempt);
                    continue;
                }
                UnmodifiableConfig toml = new TomlParser().parse(Files.readString(tomlFile.toPath(), StandardCharsets.UTF_8));
                long sizeAfter = tomlFile.length();
                if (sizeBefore != sizeAfter) {
                    sleepBackoff(attempt);
                    continue;
                }
                T fresh = newInstance(type);
                T applied = applyToml(toml, fresh, type);
                copySectionRefs(applied, defaults);
                return new ReadAttempt<>(applied, null);
            } catch (Throwable e) {
                lastError = e;
                sleepBackoff(attempt);
            }
        }
        if (lastError == null) {
            lastError = new IOException("Configuration file stayed empty or changed during all parse attempts: " + tomlFile);
        }
        return new ReadAttempt<>(null, lastError);
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(PARSE_RETRY_BACKOFF_MS * (long) attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> void copySectionRefs(T target, T defaults) {
        if (target == null || defaults == null) {
            return;
        }
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            if (!isSection(f.getType())) {
                continue;
            }
            f.setAccessible(true);
            try {
                Object existing = f.get(target);
                if (existing == null) {
                    f.set(target, f.get(defaults));
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static <T> T applyToml(UnmodifiableConfig toml, T target, Class<T> type) throws Exception {
        applyTomlObject(toml, target, type);
        return target;
    }

    private static void applyTomlObject(UnmodifiableConfig toml, Object target, Class<?> type) throws Exception {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            String key = toTomlKey(f.getName());
            Object value = toml.get(key);
            if (value == null) {
                continue;
            }

            if (isSectionList(f)) {
                if (!(value instanceof List<?> tables)) {
                    throw new IllegalArgumentException("Configuration " + key + " must be an array of tables");
                }
                Class<?> elementType = listElementType(f);
                List<Object> entries = new ArrayList<>(tables.size());
                for (Object table : tables) {
                    if (!(table instanceof UnmodifiableConfig section)) {
                        throw new IllegalArgumentException("Configuration " + key + " must contain only tables");
                    }
                    Object entry = elementType.getDeclaredConstructor().newInstance();
                    applyTomlObject(section, entry, elementType);
                    entries.add(entry);
                }
                f.set(target, entries);
                continue;
            }

            if (isSection(f.getType())) {
                if (!(value instanceof UnmodifiableConfig sub)) {
                    throw new IllegalArgumentException("Configuration " + key + " must be a table");
                }
                Object existing = f.get(target);
                if (existing == null) {
                    existing = f.getType().getDeclaredConstructor().newInstance();
                    f.set(target, existing);
                }
                applyTomlObject(sub, existing, existing.getClass());
                continue;
            }

            applyScalarField(value, f, target, key);
        }
    }

    private static void applyScalarField(Object value, Field f, Object target, String key) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (isStringList(f)) {
            if (!(value instanceof List<?> values)) {
                throw new IllegalArgumentException("Configuration " + key + " must be an array of strings");
            }
            List<String> strings = new ArrayList<>(values.size());
            for (Object element : values) {
                if (!(element instanceof String string)) {
                    throw new IllegalArgumentException("Configuration " + key + " must contain only strings");
                }
                strings.add(string);
            }
            f.set(target, strings);
        } else if (t == int.class || t == Integer.class) {
            f.set(target, Math.toIntExact(integerValue(value, key)));
        } else if (t == long.class || t == Long.class) {
            f.set(target, integerValue(value, key));
        } else if (t == double.class || t == Double.class) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("Configuration " + key + " must be a number");
            }
            f.set(target, number.doubleValue());
        } else if (t == float.class || t == Float.class) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("Configuration " + key + " must be a number");
            }
            f.set(target, number.floatValue());
        } else if (t == boolean.class || t == Boolean.class) {
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("Configuration " + key + " must be a boolean");
            }
            f.set(target, value);
        } else if (t == String.class) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Configuration " + key + " must be a string");
            }
            f.set(target, value);
        }
    }

    private static long integerValue(Object value, String key) {
        if (value instanceof Long integer) {
            return integer.longValue();
        }
        if (value instanceof Integer integer) {
            return integer.longValue();
        }
        throw new IllegalArgumentException("Configuration " + key + " must be an integer");
    }

    private static void writeField(StringBuilder out, Field f, Object owner, String indent) throws IllegalAccessException {
        Object value = f.get(owner);
        if (value == null) {
            return;
        }
        ConfigDescription desc = f.getAnnotation(ConfigDescription.class);
        if (desc != null) {
            for (String line : desc.value()) {
                out.append(indent).append("# ").append(line).append('\n');
            }
        }
        String formatted = isStringList(f) ? formatStringList((List<?>) value) : formatValue(value);
        out.append(indent).append(toTomlKey(f.getName())).append(" = ").append(formatted).append('\n');
    }

    private static String formatStringList(List<?> values) {
        StringBuilder out = new StringBuilder("[");
        for (Object value : values) {
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException("Configuration string arrays must contain only non-null strings");
            }
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(formatString(string));
        }
        return out.append(']').toString();
    }

    private static String formatString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (character < 0x20 || character == 0x7f) {
                        String hexadecimal = Integer.toHexString(character);
                        out.append("\\u").append("0".repeat(4 - hexadecimal.length())).append(hexadecimal);
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String formatValue(Object value) {
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number n) {
            if (value instanceof Double || value instanceof Float) {
                double d = n.doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    return Double.toString(d);
                }
                return n.toString();
            }
            return n.toString();
        }
        if (value instanceof String s) {
            return formatString(s);
        }
        return "\"" + value + "\"";
    }

    private static String toTomlKey(String fieldName) {
        StringBuilder sb = new StringBuilder(fieldName.length() + 4);
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isSection(Class<?> type) {
        if (type.isPrimitive() || type == String.class) {
            return false;
        }
        if (Number.class.isAssignableFrom(type) || type == Boolean.class) {
            return false;
        }
        if (List.class.isAssignableFrom(type)) {
            return false;
        }
        return type.getPackageName().startsWith("art.arcane.wormholes");
    }

    private static boolean isSectionList(Field f) {
        if (!List.class.isAssignableFrom(f.getType())) {
            return false;
        }
        Class<?> elementType = listElementType(f);
        return elementType != null && isSection(elementType);
    }

    private static boolean isStringList(Field field) {
        return List.class.isAssignableFrom(field.getType()) && listElementType(field) == String.class;
    }

    private static Class<?> listElementType(Field f) {
        if (!(f.getGenericType() instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> element)) {
            return null;
        }
        return element;
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return;
        }
        Path tmp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    public record LoadResult<T>(T value, Throwable error) {
        public boolean isSuccess() {
            return error == null && value != null;
        }
    }

    private record ReadAttempt<T>(T value, Throwable error) {
    }
}
