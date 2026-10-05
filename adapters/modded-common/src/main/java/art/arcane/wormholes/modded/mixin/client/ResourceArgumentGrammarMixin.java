package art.arcane.wormholes.modded.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.StringReader;
import com.mojang.serialization.DynamicOps;
import net.minecraft.commands.arguments.ResourceOrIdArgument;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.parsing.packrat.Dictionary;
import net.minecraft.util.parsing.packrat.NamedRule;
import net.minecraft.util.parsing.packrat.Rule;
import net.minecraft.util.parsing.packrat.commands.Grammar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ResourceOrIdArgument.class)
public abstract class ResourceArgumentGrammarMixin {
    @Unique
    private static final Object wormholes$grammarLock = new Object();
    @Unique
    private static volatile Grammar<Tag> wormholes$nbtGrammar;

    @SuppressWarnings("unchecked")
    @WrapOperation(method = "createGrammar", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/nbt/SnbtGrammar;createParser(Lcom/mojang/serialization/DynamicOps;)Lnet/minecraft/util/parsing/packrat/commands/Grammar;"))
    private static <T> Grammar<T> wormholes$reuseSyntax(DynamicOps<T> ops, Operation<Grammar<T>> original) {
        if (ops != (Object) NbtOps.INSTANCE) {
            return original.call(ops);
        }
        Grammar<Tag> grammar = wormholes$nbtGrammar;
        if (grammar == null) {
            synchronized (wormholes$grammarLock) {
                grammar = wormholes$nbtGrammar;
                if (grammar == null) {
                    grammar = wormholes$ownedSyntax((Grammar<Tag>) original.call(ops));
                    wormholes$nbtGrammar = grammar;
                }
            }
        }
        return (Grammar<T>) grammar;
    }

    @Unique
    private static Grammar<Tag> wormholes$ownedSyntax(Grammar<Tag> grammar) {
        Dictionary<StringReader> rules = new Dictionary<>();
        Rule<StringReader, Tag> syntax = grammar.top().value();
        NamedRule<StringReader, Tag> top = rules.put(grammar.top().name(), state -> {
            Tag parsed = syntax.parse(state);
            return parsed == null ? null : parsed.copy();
        });
        return new Grammar<>(rules, top);
    }
}
