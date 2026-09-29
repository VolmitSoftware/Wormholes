package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalLookup;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RuleValidationException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MinecraftRulesCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final String PERMISSION = "wormholes.admin.rules";
    private final WormholesModRuntime runtime;

    public MinecraftRulesCommands(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> rules = Commands.literal("rules")
            .requires(source -> runtime.access().permission(source, PERMISSION));
        rules.then(Commands.literal("list").executes(context -> list(context.getSource())));
        for (String operation : List.of("export", "import", "apply")) {
            rules.then(Commands.literal(operation).then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> template(context.getSource(), operation, StringArgumentType.getString(context, "name"), ""))
                .then(Commands.argument("portal", StringArgumentType.greedyString())
                    .executes(context -> template(context.getSource(), operation, StringArgumentType.getString(context, "name"),
                        StringArgumentType.getString(context, "portal"))))));
        }
        rules.then(Commands.literal("key").then(Commands.argument("portal", StringArgumentType.string())
            .executes(context -> key(context.getSource(), StringArgumentType.getString(context, "portal"), 0))
            .then(Commands.argument("uses", IntegerArgumentType.integer(0))
                .executes(context -> key(context.getSource(), StringArgumentType.getString(context, "portal"),
                    IntegerArgumentType.getInteger(context, "uses"))))));
        dispatcher.register(Commands.literal("wormholes").then(rules));
    }

    private int list(CommandSourceStack source) {
        RuleTemplates templates = templates();
        asynchronous(source, templates::list, names -> send(source, names.isEmpty()
            ? RulesMessages.COMMAND_NO_TEMPLATES : RulesMessages.COMMAND_TEMPLATES,
            names.isEmpty() ? Map.of() : Map.of("value", String.join(", ", names))));
        return 1;
    }

    private int template(CommandSourceStack source, String operation, String name, String token) {
        MinecraftPortal portal = portal(source, token);
        if (portal == null) {
            return 0;
        }
        RuleTemplates templates = templates();
        if (operation.equals("export")) {
            RuleDocument document = runtime.rules().document(portal);
            asynchronous(source, () -> {
                try {
                    templates.save(name, document);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
                return name;
            }, ignored -> send(source, RulesMessages.COMMAND_EXPORTED, Map.of("portal", portal.getName(), "name", name)));
            return 1;
        }
        asynchronous(source, () -> templates.load(name), document -> {
            if (runtime.portals().get(portal.getId()) != portal) {
                return;
            }
            if (document == null) {
                send(source, RulesMessages.COMMAND_TEMPLATE_MISSING, Map.of("name", name));
                return;
            }
            RuleDocument authored = source.getEntity() instanceof ServerPlayer player
                ? runtime.rules().authorDocument(player, document) : document;
            runtime.rules().setDocument(portal, authored);
            send(source, RulesMessages.COMMAND_IMPORTED, Map.of("portal", portal.getName(), "name", name));
        });
        return 1;
    }

    private int key(CommandSourceStack source, String token, int uses) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            send(source, WormholesMessages.COMMAND_ONLY_PLAYERS, Map.of());
            return 0;
        }
        MinecraftPortal portal = portal(source, token);
        if (portal == null) {
            return 0;
        }
        ItemStack key = MinecraftRuleCostSubject.mintKey(portal.getId(), uses);
        player.getInventory().add(key);
        if (!key.isEmpty()) {
            player.drop(key, false, Prediction.SERVER_ONLY);
        }
        send(source, RulesMessages.COMMAND_KEY_GIVEN, Map.of("portal", portal.getName()));
        return 1;
    }

    private MinecraftPortal portal(CommandSourceStack source, String token) {
        if (token.isBlank()) {
            MinecraftPortal portal = runtime.portals().at(source.getLevel(), BlockPos.containing(source.getPosition()));
            if (portal == null) {
                send(source, RulesMessages.COMMAND_NOT_IN_PORTAL, Map.of());
            }
            return portal;
        }
        String name = token.startsWith("portal=") ? token.substring("portal=".length()) : token;
        PortalLookup.Match<MinecraftPortal> match = PortalLookup.find(name, runtime.portals().snapshot(),
            MinecraftPortal::getId, MinecraftPortal::getName);
        if (match.portal() == null) {
            send(source, match.matches() > 1 ? RulesMessages.COMMAND_PORTAL_AMBIGUOUS : RulesMessages.COMMAND_PORTAL_MISSING,
                match.matches() > 1 ? Map.of("name", name, "count", match.matches()) : Map.of("name", name));
        }
        return match.portal();
    }

    private <T> void asynchronous(CommandSourceStack source, Supplier<T> operation, Consumer<T> success) {
        MinecraftServer server = runtime.server();
        CompletableFuture.supplyAsync(operation).whenCompleteAsync((result, failure) -> {
            if (!runtime.running() || runtime.server() != server
                || source.getEntity() instanceof ServerPlayer player && player.hasDisconnected()
                || !runtime.access().permission(source, PERMISSION)) {
                return;
            }
            if (failure != null) {
                failed(source, failure);
                return;
            }
            try {
                success.accept(result);
            } catch (IllegalArgumentException | RuleValidationException invalid) {
                failed(source, invalid);
            }
        }, server);
    }

    private void failed(CommandSourceStack source, Throwable failure) {
        LOGGER.error("Could not complete traversal rules command", failure);
        send(source, RulesMessages.COMMAND_FAILED, Map.of("reason", String.valueOf(failure.getMessage())));
    }

    private RuleTemplates templates() {
        return new RuleTemplates(runtime.server().getServerDirectory().resolve("config/wormholes"),
            runtime.configuration().settings().getRules());
    }

    private void send(CommandSourceStack source, TextKey key, Map<String, ?> values) {
        source.sendSystemMessage(MinecraftMenuText.text(runtime.localization().snapshot(source.getPlayer()), key, values));
    }
}
