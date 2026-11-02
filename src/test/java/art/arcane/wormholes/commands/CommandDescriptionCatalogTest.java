package art.arcane.wormholes.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.WormholesMessages;

final class CommandDescriptionCatalogTest {
    @Test
    void everyCommandDescriptionKeyIsATranslatableCatalogEntry() throws IOException, ClassNotFoundException {
        MessageCatalog catalog = WormholesMessages.catalog();
        List<Class<?>> commandTypes = commandTypes();
        List<String> problems = new ArrayList<>();
        int checked = 0;

        assertFalse(commandTypes.isEmpty());
        for (Class<?> commandType : commandTypes) {
            checked += checkCommandType(commandType, catalog, problems);
        }
        assertTrue(checked > 0);
        assertEquals(List.of(), problems);
    }

    private static List<Class<?>> commandTypes() throws IOException, ClassNotFoundException {
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/art/arcane/wormholes/commands"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                collect(Class.forName("art.arcane.wormholes.commands." + name.substring(0, name.length() - 5)), types);
            }
        }
        return types;
    }

    private static void collect(Class<?> type, List<Class<?>> types) {
        types.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collect(nested, types);
        }
    }

    private static int checkCommandType(Class<?> commandType, MessageCatalog catalog, List<String> problems) {
        int checked = 0;
        Director type = commandType.getAnnotation(Director.class);
        if (type != null) {
            checked += checkDescription(commandType.getName(), type.description(), type.descriptionKey(), catalog, problems);
        }
        for (Method method : commandType.getDeclaredMethods()) {
            Director director = method.getAnnotation(Director.class);
            if (director == null) {
                continue;
            }
            String owner = commandType.getName() + "#" + method.getName();
            checked += checkDescription(owner, director.description(), director.descriptionKey(), catalog, problems);
            for (Parameter parameter : method.getParameters()) {
                Param param = parameter.getAnnotation(Param.class);
                if (param != null) {
                    checked += checkDescription(owner + "(" + param.name() + ")", param.description(),
                            param.descriptionKey(), catalog, problems);
                }
            }
        }
        return checked;
    }

    private static int checkDescription(String owner, String description, String descriptionKey,
                                        MessageCatalog catalog, List<String> problems) {
        if (descriptionKey.isBlank()) {
            return 0;
        }
        MessageKey key = catalog.key(descriptionKey);
        if (!(key instanceof TextKey text)) {
            problems.add(owner + ": " + descriptionKey + " is not a catalog text entry");
        } else if (!description.isBlank() && !text.english().equals(description)) {
            problems.add(owner + ": " + descriptionKey + " catalog \"" + text.english()
                    + "\" differs from \"" + description + "\"");
        }
        return 1;
    }
}
