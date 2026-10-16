package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.CapturedItem;
import art.arcane.wormholes.menu.MenuCaptures;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.modded.MinecraftLegacyText;
import art.arcane.wormholes.modded.MinecraftPortalText;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinecraftMenuCaptures implements MenuCaptures, AutoCloseable {
    private final WormholesModRuntime runtime;
    private final Map<UUID, DropCapture> drops = new HashMap<>();
    private final Map<UUID, PromptInput> prompts = new HashMap<>();

    public MinecraftMenuCaptures(WormholesModRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static boolean dropped(ServerPlayer player) {
        WormholesModRuntime runtime = WormholesModRuntime.forServer(player.level().getServer());
        return runtime != null && runtime.menuCaptures().capture(player);
    }

    @Override
    public void close() {
        drops.clear();
        prompts.clear();
    }

    @Override
    public void prompt(MenuViewer viewer, List<String> promptLines, Consumer<String> submitted, Runnable cancelled) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        send(player, promptLines);
        PromptInput input = new PromptInput(player, submitted, cancelled);
        prompts.put(player.getUUID(), input);
        runtime.chatInput().await(player, input);
    }

    @Override
    public void dropItem(MenuViewer viewer, List<String> promptLines, Consumer<CapturedItem> captured, Runnable cancelled) {
        ServerPlayer player = MinecraftMenuViewer.player(viewer);
        drops.put(player.getUUID(), new DropCapture(captured, cancelled));
        send(player, promptLines);
    }

    @Override
    public Optional<CapturedItem> heldItem(MenuViewer viewer) {
        ItemStack held = MinecraftMenuViewer.player(viewer).getMainHandItem();
        if (held.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MinecraftCapturedItem(held));
    }

    public boolean capture(ServerPlayer player) {
        runtime.requireServerThread();
        DropCapture capture = drops.remove(player.getUUID());
        if (capture == null) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            capture.cancelled().run();
        } else {
            capture.captured().accept(new MinecraftCapturedItem(held));
        }
        return true;
    }

    public void cancel(ServerPlayer player) {
        UUID playerId = player.getUUID();
        drops.remove(playerId);
        PromptInput input = prompts.remove(playerId);
        if (input != null) {
            runtime.chatInput().cancel(player, input);
        }
    }

    public void disconnected(ServerPlayer player) {
        drops.remove(player.getUUID());
        prompts.remove(player.getUUID());
    }

    private static void send(ServerPlayer player, List<String> lines) {
        for (String line : lines) {
            player.sendSystemMessage(MinecraftLegacyText.component(line));
        }
    }

    private record DropCapture(Consumer<CapturedItem> captured, Runnable cancelled) {
    }

    private final class PromptInput implements Consumer<String> {
        private final ServerPlayer player;
        private final Consumer<String> submitted;
        private final Runnable cancelled;

        private PromptInput(ServerPlayer player, Consumer<String> submitted, Runnable cancelled) {
            this.player = player;
            this.submitted = submitted;
            this.cancelled = cancelled;
        }

        @Override
        public void accept(String input) {
            prompts.remove(player.getUUID(), this);
            if (input == null || MinecraftPortalText.isCancelInput(player, input)) {
                cancelled.run();
                return;
            }
            submitted.accept(input);
        }
    }
}
