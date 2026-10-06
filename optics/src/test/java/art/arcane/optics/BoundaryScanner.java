package art.arcane.optics;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BoundaryScanner {
    private static final List<String> FORBIDDEN_PACKAGES = List.of("art/arcane/wormholes", "net/minecraft", "org/bukkit",
        "io/papermc", "com/github/retrooper", "io/github/retrooper", "net/kyori", "art/arcane/volmlib");
    private static final List<String> FORBIDDEN_NAMES = List.of("Wormhole", "wormhole", "Rtp", "Door", "Pocket", "Nexus",
        "Venticular", "PanOptic");
    private static final List<String> FORBIDDEN_CALLS = List.of("java/lang/Class.forName", "java/lang/System.getProperty",
        "java/lang/System.getProperties", "java/lang/System.getenv", "java/lang/Integer.getInteger", "java/lang/Long.getLong",
        "java/lang/Boolean.getBoolean");
    private static final String SERVICE_LOADER = "java/util/ServiceLoader";
    private static final String INTERNAL = "art/arcane/optics/internal/";

    private BoundaryScanner() {
    }

    static List<String> violations(byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        String owner = model.thisClass().asInternalName();
        List<String> violations = new ArrayList<String>();
        String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
        for (String name : FORBIDDEN_NAMES) {
            if (simpleName.contains(name)) {
                violations.add(owner + " has a product name in its simple name: " + name);
            }
        }
        for (PoolEntry entry : model.constantPool()) {
            inspect(owner, entry, violations);
        }
        for (FieldModel field : model.fields()) {
            if (field.flags().has(AccessFlag.STATIC) && !field.flags().has(AccessFlag.FINAL)) {
                violations.add(owner + " holds static mutable field " + field.fieldName().stringValue());
            }
        }
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
            String value = string.stringValue();
            for (String name : FORBIDDEN_NAMES) {
                if (value.contains(name)) {
                    violations.add(owner + " carries a product name in string constant \"" + value + "\"");
                }
            }
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
