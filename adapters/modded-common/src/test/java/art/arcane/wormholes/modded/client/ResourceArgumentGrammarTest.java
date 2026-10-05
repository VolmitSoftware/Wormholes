package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.mixin.client.ResourceArgumentGrammarMixin;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.arguments.ResourceOrIdArgument;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.SnbtGrammar;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.parsing.packrat.commands.Grammar;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mockStatic;

public class ResourceArgumentGrammarTest {
    @Before
    public void clearGrammar() throws ReflectiveOperationException {
        Field grammar = ResourceArgumentGrammarMixin.class.getDeclaredField("wormholes$nbtGrammar");
        grammar.setAccessible(true);
        grammar.set(null, null);
    }

    @Test
    public void nativeSyntaxIsConstructedOnceButAlternateOpsAlwaysInvokeOriginal() throws ReflectiveOperationException, CommandSyntaxException {
        AtomicInteger nativeCalls = new AtomicInteger();
        Grammar<Tag> first = grammar(NbtOps.INSTANCE, nativeCalls);
        assertSame(first, grammar(NbtOps.INSTANCE, nativeCalls));
        assertEquals(1, nativeCalls.get());
        AtomicInteger alternateCalls = new AtomicInteger();
        Grammar<JsonElement> alternate = grammar(JsonOps.INSTANCE, alternateCalls);
        assertNotSame(alternate, grammar(JsonOps.INSTANCE, alternateCalls));
        assertEquals(2, alternateCalls.get());
        assertEquals(SnbtGrammar.createParser(JsonOps.INSTANCE).parseForCommands(new StringReader("[1,2]")),
            alternate.parseForCommands(new StringReader("[1,2]")));
    }

    @Test
    public void sharedSyntaxPreservesOutputsErrorsAndSuggestionsAfterPreviousParses() throws Exception {
        Grammar<Tag> shared = grammar(NbtOps.INSTANCE, new AtomicInteger());
        Grammar<Tag> fresh = SnbtGrammar.createParser(NbtOps.INSTANCE);
        for (String input : List.of("{}", "[[1,2],[3,4]]", "\"text\"", "true", "[0]")) {
            StringReader actual = new StringReader(input);
            StringReader expected = new StringReader(input);
            assertEquals(fresh.parseForCommands(expected), shared.parseForCommands(actual));
            assertEquals(expected.getCursor(), actual.getCursor());
        }
        for (String input : List.of("[", "\"unfinished", "{", "[1,")) {
            StringReader actual = new StringReader(input);
            StringReader expected = new StringReader(input);
            CommandSyntaxException expectedError = assertThrows(CommandSyntaxException.class, () -> fresh.parseForCommands(expected));
            CommandSyntaxException actualError = assertThrows(CommandSyntaxException.class, () -> shared.parseForCommands(actual));
            assertEquals(expectedError.getCursor(), actualError.getCursor());
            assertEquals(expectedError.getRawMessage().getString(), actualError.getRawMessage().getString());
            assertEquals(expected.getCursor(), actual.getCursor());
            Suggestions expectedSuggestions = fresh.parseForSuggestions(new SuggestionsBuilder(input, 0)).get();
            Suggestions actualSuggestions = shared.parseForSuggestions(new SuggestionsBuilder(input, 0)).get();
            assertEquals(expectedSuggestions.getRange(), actualSuggestions.getRange());
            assertEquals(expectedSuggestions.getList(), actualSuggestions.getList());
        }
        assertEquals(fresh.parseForCommands(new StringReader("[5,6]")), shared.parseForCommands(new StringReader("[5,6]")));
    }

    @Test
    public void mutableEmptyAndNestedResultsNeverAliasLaterParsesOrSiblingValues() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Grammar<Tag> shared = grammar(NbtOps.INSTANCE, calls);
        CompoundTag empty = (CompoundTag) shared.parseForCommands(new StringReader("{}"));
        empty.putInt("marker", 42);
        CompoundTag later = (CompoundTag) grammar(NbtOps.INSTANCE, calls).parseForCommands(new StringReader("{}"));
        assertNotSame(empty, later);
        assertFalse(later.contains("marker"));
        ListTag list = (ListTag) shared.parseForCommands(new StringReader("[]"));
        list.add(IntTag.valueOf(7));
        assertEquals(0, ((ListTag) shared.parseForCommands(new StringReader("[]"))).size());
        ListTag compounds = (ListTag) shared.parseForCommands(new StringReader("[{},{}]"));
        CompoundTag first = (CompoundTag) compounds.get(0);
        CompoundTag second = (CompoundTag) compounds.get(1);
        assertNotSame(first, second);
        first.putInt("nested", 1);
        assertFalse(second.contains("nested"));
        ListTag laterCompounds = (ListTag) shared.parseForCommands(new StringReader("[{},{}]"));
        assertFalse(((CompoundTag) laterCompounds.get(0)).contains("nested"));
        ListTag lists = (ListTag) shared.parseForCommands(new StringReader("[[],[]]"));
        ListTag firstList = (ListTag) lists.get(0);
        ListTag secondList = (ListTag) lists.get(1);
        assertNotSame(firstList, secondList);
        firstList.add(IntTag.valueOf(9));
        assertEquals(0, secondList.size());
        assertEquals(0, ((ListTag) ((ListTag) shared.parseForCommands(new StringReader("[[],[]]"))).get(0)).size());
        assertEquals(1, calls.get());
    }

    @Test
    public void concurrentFactoriesAndParsesOwnTheirReadersAndMemoization() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<Tag>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                int value = index;
                results.add(executor.submit(() -> {
                    Grammar<Tag> shared = grammar(NbtOps.INSTANCE, calls);
                    assertThrows(CommandSyntaxException.class, () -> shared.parseForCommands(new StringReader("[")));
                    return shared.parseForCommands(new StringReader("[" + value + "," + value + "]"));
                }));
            }
            for (int index = 0; index < results.size(); index++) {
                assertEquals(SnbtGrammar.createParser(NbtOps.INSTANCE).parseForCommands(new StringReader("[" + index + "," + index + "]")),
                    results.get(index).get());
            }
            assertEquals(1, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void outerResourceGrammarsKeepDistinctRegistryKeysAndDoNotMutateSharedSyntax() throws Exception {
        Grammar<Tag> shared = grammar(NbtOps.INSTANCE, new AtomicInteger());
        Tag expected = shared.parseForCommands(new StringReader("[1,2]"));
        ResourceKey<Registry<Object>> firstKey = ResourceKey.createRegistryKey(Identifier.parse("test:first"));
        ResourceKey<Registry<Object>> secondKey = ResourceKey.createRegistryKey(Identifier.parse("test:second"));
        try (MockedStatic<SnbtGrammar> parsers = mockStatic(SnbtGrammar.class)) {
            parsers.when(() -> SnbtGrammar.createParser(NbtOps.INSTANCE)).thenReturn(shared);
            Grammar<ResourceOrIdArgument.Result<Object, Tag>> first = ResourceOrIdArgument.createGrammar(firstKey, NbtOps.INSTANCE);
            Grammar<ResourceOrIdArgument.Result<Object, Tag>> second = ResourceOrIdArgument.createGrammar(secondKey, NbtOps.INSTANCE);
            assertNotSame(first, second);
            assertNotSame(first.rules(), second.rules());
            assertNotEquals(first.parseForCommands(new StringReader("minecraft:example")),
                second.parseForCommands(new StringReader("minecraft:example")));
            assertEquals(first.parseForCommands(new StringReader("[1,2]")), second.parseForCommands(new StringReader("[1,2]")));
            assertEquals(expected, shared.parseForCommands(new StringReader("[1,2]")));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Grammar<T> grammar(DynamicOps<T> ops, AtomicInteger calls) throws ReflectiveOperationException {
        Method reuse = ResourceArgumentGrammarMixin.class.getDeclaredMethod("wormholes$reuseSyntax", DynamicOps.class, Operation.class);
        reuse.setAccessible(true);
        Operation<Grammar<T>> original = arguments -> {
            assertSame(ops, arguments[0]);
            calls.incrementAndGet();
            return SnbtGrammar.createParser(ops);
        };
        return (Grammar<T>) reuse.invoke(null, ops, original);
    }
}
