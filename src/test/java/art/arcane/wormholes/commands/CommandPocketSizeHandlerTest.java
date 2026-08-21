package art.arcane.wormholes.commands;

import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.wormholes.door.PocketShell;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPocketSizeHandlerTest {
    private final CommandPocket.SizeHandler handler = new CommandPocket.SizeHandler();

    @Test
    void anEmptyPrefixOffersAShortLadderRatherThanEverySupportedSize() {
        List<Integer> suggested = handler.getPossibilities();

        assertEquals(List.of(0, 16, 32, 64, 96, 128), suggested);
        assertEquals(suggested, handler.getPossibilities(""));
        assertEquals(suggested, handler.getPossibilities(null));
    }

    @Test
    void aTypedPrefixSearchesEverySupportedSizeAndNeverOffersAnUnsupportedOne() {
        List<Integer> matches = handler.getPossibilities("1");

        assertTrue(matches.contains(Integer.valueOf(16)));
        assertTrue(matches.contains(Integer.valueOf(128)));
        assertFalse(matches.contains(Integer.valueOf(32)));
        for (Integer match : matches) {
            assertTrue(PocketShell.isSupportedSize(match.intValue()), String.valueOf(match));
        }
        assertTrue(handler.getPossibilities("999").isEmpty());
    }

    @Test
    void sizesParseAndZeroSurvivesAsTheKeepCurrentSentinel() throws DirectorParsingException {
        assertEquals(Integer.valueOf(0), handler.parse("0", false));
        assertEquals(Integer.valueOf(64), handler.parse(" 64 ", false));
        assertEquals(Integer.valueOf(4_096), handler.parse("4096", false),
            "out-of-range sizes parse so the command can name the real limits");
    }

    @Test
    void garbageAndNegativeSizesAreRejectedAtParseTime() {
        assertThrows(DirectorParsingException.class, () -> handler.parse("wide", false));
        assertThrows(DirectorParsingException.class, () -> handler.parse("-1", false));
        assertThrows(DirectorParsingException.class, () -> handler.parse("", false));
    }

    /**
     * Director resolves custom handlers reflectively and swallows any failure
     * into "no completions at all", so the wiring is asserted here rather than
     * discovered in game.
     */
    @Test
    void everyResizeArgumentIsWiredToAnInstantiableCompletionHandler() throws Exception {
        Map<String, Class<? extends DirectorParameterHandler<?>>> expected = new LinkedHashMap<>();
        expected.put("size", CommandPocket.SizeHandler.class);
        expected.put("material", CommandPocket.ShellMaterialHandler.class);
        expected.put("door", CommandPocket.DoorMaterialHandler.class);

        for (String command : List.of("resize", "resizeAll")) {
            Method method = method(command);
            Map<String, Class<?>> handlers = new LinkedHashMap<>();
            for (Parameter parameter : method.getParameters()) {
                Param annotation = parameter.getAnnotation(Param.class);
                assertTrue(annotation != null, command + " parameter is missing @Param");
                handlers.put(annotation.name(), annotation.customHandler());
            }

            for (Map.Entry<String, Class<? extends DirectorParameterHandler<?>>> entry : expected.entrySet()) {
                Class<?> declared = handlers.get(entry.getKey());
                assertEquals(entry.getValue(), declared, command + " " + entry.getKey());
                Object instance = declared.getConstructor().newInstance();
                assertTrue(instance instanceof DirectorParameterHandler<?>, declared.getName());
            }
            // Booleans are completed by the engine itself, so confirm stays unhandled.
            assertTrue(handlers.containsKey("confirm"), command + " is missing confirm");
        }
    }

    @Test
    void theHandlerOnlyClaimsIntegerParameters() {
        assertTrue(handler.supports(int.class));
        assertTrue(handler.supports(Integer.class));
        assertFalse(handler.supports(String.class));
        assertEquals("32", handler.toString(Integer.valueOf(32)));
    }

    private static Method method(String name) {
        for (Method candidate : CommandPocket.class.getDeclaredMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("CommandPocket has no " + name + " command");
    }
}
