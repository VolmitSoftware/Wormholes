package art.arcane.optics;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class BoundaryScanner {
    private static final List<String> FORBIDDEN_PACKAGES = List.of("art/arcane/wormholes", "net/minecraft", "com/mojang", "org/bukkit",
        "io/papermc", "com/github/retrooper", "io/github/retrooper", "net/kyori", "art/arcane/volmlib", "com/google/gson", "io/netty",
        "org/slf4j", "com/electronwill", "com/github/luben", "org/yaml", "net/fabricmc", "net/neoforged", "net/minecraftforge",
        "org/spongepowered", "com/velocitypowered", "net/md_5");
    private static final List<String> FORBIDDEN_NAME_PREFIXES = List.of("rtp", "door", "pocket", "nexus", "venticular", "panoptic",
        "portalplayer");
    private static final List<String> FORBIDDEN_NAME_TOKENS = List.of("wh", "whpn");
    private static final List<String> FORBIDDEN_CALLS = List.of("java/lang/Class.forName", "java/lang/Class.newInstance",
        "java/lang/Class.getMethod", "java/lang/Class.getMethods", "java/lang/Class.getDeclaredMethod", "java/lang/Class.getDeclaredMethods",
        "java/lang/Class.getField", "java/lang/Class.getFields", "java/lang/Class.getDeclaredField", "java/lang/Class.getDeclaredFields",
        "java/lang/Class.getConstructor", "java/lang/Class.getConstructors", "java/lang/Class.getDeclaredConstructor",
        "java/lang/Class.getDeclaredConstructors", "java/lang/Class.getResource", "java/lang/Class.getResourceAsStream",
        "java/lang/ClassLoader.loadClass", "java/lang/ClassLoader.getResource", "java/lang/ClassLoader.getResources",
        "java/lang/ClassLoader.getResourceAsStream", "java/lang/ClassLoader.getSystemResource", "java/lang/ClassLoader.getSystemResources",
        "java/lang/ClassLoader.getSystemResourceAsStream", "java/lang/Module.getResourceAsStream",
        "java/lang/invoke/MethodHandles$Lookup.findClass", "java/lang/invoke/MethodHandles$Lookup.findStatic",
        "java/lang/invoke/MethodHandles$Lookup.findVirtual", "java/lang/invoke/MethodHandles$Lookup.findGetter",
        "java/lang/invoke/MethodHandles$Lookup.findStaticGetter", "java/lang/System.getProperty", "java/lang/System.getProperties",
        "java/lang/System.getenv", "java/lang/Integer.getInteger", "java/lang/Long.getLong", "java/lang/Boolean.getBoolean");
    private static final String SERVICE_LOADER = "java/util/ServiceLoader";
    private static final String INTERNAL = "art/arcane/optics/internal/";
    private static final String STATIC_INITIALIZER = "<clinit>";
    private static final List<String> MUTABLE_TYPE_PREFIXES = List.of("java/util/concurrent/atomic/", "java/util/concurrent/locks/",
        "it/unimi/dsi/fastutil/");
    private static final Set<String> MUTABLE_TYPES = Set.of("java/util/Random", "java/util/HashMap", "java/util/LinkedHashMap",
        "java/util/TreeMap", "java/util/WeakHashMap", "java/util/IdentityHashMap", "java/util/EnumMap", "java/util/ArrayList",
        "java/util/LinkedList", "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/EnumSet",
        "java/util/ArrayDeque", "java/util/PriorityQueue", "java/util/BitSet", "java/util/Vector", "java/util/Stack",
        "java/util/Hashtable", "java/lang/StringBuilder", "java/lang/StringBuffer", "java/lang/ThreadLocal",
        "java/lang/InheritableThreadLocal", "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentLinkedQueue",
        "java/util/concurrent/ConcurrentLinkedDeque", "java/util/concurrent/ConcurrentSkipListMap", "java/util/concurrent/ConcurrentSkipListSet",
        "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet", "java/util/concurrent/LinkedBlockingQueue",
        "java/util/concurrent/ArrayBlockingQueue", "java/util/concurrent/LinkedBlockingDeque", "java/util/concurrent/PriorityBlockingQueue",
        "java/util/concurrent/ThreadLocalRandom");

    private BoundaryScanner() {
    }

    static List<String> violations(byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        String owner = model.thisClass().asInternalName();
        List<String> violations = new ArrayList<String>();
        names(owner, "simple name", owner.substring(owner.lastIndexOf('/') + 1), violations);
        for (PoolEntry entry : model.constantPool()) {
            inspect(owner, entry, violations);
        }
        inspectMembers(model, owner, violations);
        inspectStaticState(model, owner, violations);
        if (exported(model)) {
            inspectExposure(model, owner, violations);
        }
        return violations;
    }

    private static void inspect(String owner, PoolEntry entry, List<String> violations) {
        if (entry instanceof Utf8Entry utf8) {
            String value = utf8.stringValue();
            for (String forbidden : FORBIDDEN_PACKAGES) {
                if (value.contains(forbidden) || value.contains(forbidden.replace('/', '.'))) {
                    violations.add(owner + " references " + forbidden + " via " + value);
                }
            }
            return;
        }
        if (entry instanceof StringEntry string) {
            names(owner, "string constant", string.stringValue(), violations);
            return;
        }
        if (entry instanceof ClassEntry type && type.asInternalName().equals(SERVICE_LOADER)) {
            violations.add(owner + " uses " + SERVICE_LOADER);
            return;
        }
        if (entry instanceof MemberRefEntry member) {
            String call = member.owner().asInternalName() + "." + member.name().stringValue();
            if (FORBIDDEN_CALLS.contains(call)) {
                violations.add(owner + " calls " + call);
            }
        }
    }

    private static void inspectMembers(ClassModel model, String owner, List<String> violations) {
        for (FieldModel field : model.fields()) {
            names(owner, "field", field.fieldName().stringValue(), violations);
        }
        for (MethodModel method : model.methods()) {
            names(owner, "method", method.methodName().stringValue(), violations);
            Optional<MethodParametersAttribute> parameters = method.findAttribute(Attributes.methodParameters());
            if (parameters.isEmpty()) {
                continue;
            }
            for (MethodParameterInfo parameter : parameters.get().parameters()) {
                if (parameter.name().isPresent()) {
                    names(owner, "parameter of " + method.methodName().stringValue(), parameter.name().get().stringValue(), violations);
                }
            }
        }
        Optional<RecordAttribute> record = model.findAttribute(Attributes.record());
        if (record.isPresent()) {
            for (RecordComponentInfo component : record.get().components()) {
                names(owner, "record component", component.name().stringValue(), violations);
            }
        }
    }

    private static void inspectStaticState(ClassModel model, String owner, List<String> violations) {
        Set<String> finals = new HashSet<String>();
        for (FieldModel field : model.fields()) {
            if (!field.flags().has(AccessFlag.STATIC)) {
                continue;
            }
            String name = field.fieldName().stringValue();
            if (!field.flags().has(AccessFlag.FINAL)) {
                violations.add(owner + " holds static mutable field " + name);
                continue;
            }
            finals.add(name);
            String descriptor = field.fieldType().stringValue();
            if (descriptor.startsWith("L") && mutable(descriptor.substring(1, descriptor.length() - 1))) {
                violations.add(owner + " holds static mutable object " + name + " of type " + descriptor);
            }
        }
        for (MethodModel method : model.methods()) {
            if (!method.methodName().stringValue().equals(STATIC_INITIALIZER) || method.code().isEmpty()) {
                continue;
            }
            inspectInitializer(method.code().get(), owner, finals, violations);
        }
    }

    private static void inspectInitializer(CodeModel code, String owner, Set<String> finals, List<String> violations) {
        String pending = null;
        for (CodeElement element : code) {
            if (element instanceof NewObjectInstruction allocation) {
                pending = pending == null ? allocation.className().asInternalName() : pending;
                continue;
            }
            if (!(element instanceof FieldInstruction field) || !field.owner().asInternalName().equals(owner)) {
                continue;
            }
            String name = field.name().stringValue();
            if (pending != null && finals.contains(name) && mutable(pending)) {
                violations.add(owner + " holds static mutable object " + name + " created as " + pending);
            }
            pending = null;
        }
    }

    private static boolean mutable(String type) {
        if (MUTABLE_TYPES.contains(type)) {
            return true;
        }
        for (String prefix : MUTABLE_TYPE_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void names(String owner, String kind, String value, List<String> violations) {
        List<String> tokens = tokens(value);
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String joined = index + 1 < tokens.size() ? token + tokens.get(index + 1) : token;
            String hit = forbidden(token, joined);
            if (hit != null) {
                violations.add(owner + " carries product name " + hit + " in its " + kind + " \"" + value + "\"");
                return;
            }
        }
    }

    private static String forbidden(String token, String joined) {
        for (String prefix : FORBIDDEN_NAME_PREFIXES) {
            if (token.startsWith(prefix) || joined.startsWith(prefix)) {
                return prefix;
            }
        }
        return FORBIDDEN_NAME_TOKENS.contains(token) ? token : null;
    }

    private static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                flush(current, tokens);
                continue;
            }
            boolean boundary = Character.isUpperCase(character) && !current.isEmpty()
                && (Character.isLowerCase(current.charAt(current.length() - 1))
                || index + 1 < value.length() && Character.isLowerCase(value.charAt(index + 1)));
            if (boundary) {
                flush(current, tokens);
            }
            current.append(character);
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
        }
    }

    private static boolean exported(ClassModel model) {
        String owner = model.thisClass().asInternalName();
        return model.flags().has(AccessFlag.PUBLIC) && !owner.startsWith(INTERNAL);
    }

    private static void inspectExposure(ClassModel model, String owner, List<String> violations) {
        exposes(owner, "superclass", model.superclass().map(ClassEntry::asInternalName).orElse(""), violations);
        for (ClassEntry type : model.interfaces()) {
            exposes(owner, "interface", type.asInternalName(), violations);
        }
        exposes(owner, "signature", signature(model.findAttribute(Attributes.signature())), violations);
        for (FieldModel field : model.fields()) {
            if (field.flags().has(AccessFlag.PUBLIC) || field.flags().has(AccessFlag.PROTECTED)) {
                String label = "field " + field.fieldName().stringValue();
                exposes(owner, label, field.fieldType().stringValue(), violations);
                exposes(owner, label, signature(field.findAttribute(Attributes.signature())), violations);
            }
        }
        for (MethodModel method : model.methods()) {
            if (method.flags().has(AccessFlag.PUBLIC) || method.flags().has(AccessFlag.PROTECTED)) {
                String label = "method " + method.methodName().stringValue();
                exposes(owner, label, method.methodType().stringValue(), violations);
                exposes(owner, label, signature(method.findAttribute(Attributes.signature())), violations);
                Optional<ExceptionsAttribute> exceptions = method.findAttribute(Attributes.exceptions());
                if (exceptions.isPresent()) {
                    for (ClassEntry exception : exceptions.get().exceptions()) {
                        exposes(owner, label + " throws", exception.asInternalName(), violations);
                    }
                }
            }
        }
    }

    private static String signature(Optional<SignatureAttribute> attribute) {
        return attribute.map(signature -> signature.signature().stringValue()).orElse("");
    }

    private static void exposes(String owner, String member, String descriptor, List<String> violations) {
        if (descriptor.contains(INTERNAL)) {
            violations.add(owner + " exposes an internal type through its " + member + ": " + descriptor);
        }
    }
}
