package art.arcane.wormholes;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RuntimePlatformAbiTest
{
	private static final String RUNTIME_JAR_PROPERTY = "wormholes.runtimeJar";
	private static final String PLUGIN_CLASSES = "art/arcane/wormholes/";
	private static final String RELOCATED_ADVENTURE = "Lart/arcane/wormholes/libs/kyori/";

	@Test
	void shadedRuntimeKeepsRelocatedAdventureOutOfPlatformMethodDescriptors() throws IOException
	{
		String runtimeJarPath = Objects.requireNonNull(
			System.getProperty(RUNTIME_JAR_PROPERTY),
			RUNTIME_JAR_PROPERTY + " is not configured");
		Path runtimeJar = Path.of(runtimeJarPath);
		List<String> invalidInvocations = new ArrayList<>();

		try(JarFile jar = new JarFile(runtimeJar.toFile()))
		{
			Enumeration<JarEntry> entries = jar.entries();
			while(entries.hasMoreElements())
			{
				JarEntry entry = entries.nextElement();
				if(!entry.getName().startsWith(PLUGIN_CLASSES) || !entry.getName().endsWith(".class"))
				{
					continue;
				}
				try(InputStream input = jar.getInputStream(entry))
				{
					collectInvalidInvocations(ClassFile.of().parse(input.readAllBytes()), invalidInvocations);
				}
			}
		}

		assertEquals(List.of(), invalidInvocations,
			"Shading must not rewrite Adventure types inside Bukkit or Paper method descriptors");
	}

	private static void collectInvalidInvocations(ClassModel model, List<String> invalidInvocations)
	{
		for(MethodModel method : model.methods())
		{
			Optional<CodeModel> code = method.code();
			if(code.isEmpty())
			{
				continue;
			}
			for(CodeElement element : code.get().elementList())
			{
				if(!(element instanceof InvokeInstruction invocation))
				{
					continue;
				}
				String owner = invocation.owner().asInternalName();
				if(isPlatformOwner(owner) && invocation.type().stringValue().contains(RELOCATED_ADVENTURE))
				{
					invalidInvocations.add(
						model.thisClass().asInternalName() + "." + method.methodName().stringValue()
							+ " -> " + owner + "." + invocation.name().stringValue()
							+ invocation.type().stringValue());
				}
			}
		}
	}

	private static boolean isPlatformOwner(String owner)
	{
		return owner.startsWith("org/bukkit/")
			|| owner.startsWith("io/papermc/")
			|| owner.startsWith("com/destroystokyo/")
			|| owner.startsWith("net/md_5/");
	}
}
