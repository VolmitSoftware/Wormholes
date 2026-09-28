package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketResizeOutcome;
import art.arcane.wormholes.door.PocketSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MinecraftPocketMutationGameTest {
    private final Options options;
    private final MinecraftPocketService service;
    private final ServerLevel level;
    private final PocketLayout original;
    private final BlockPos stored;
    private final BlockPos displaced;

    private MinecraftPocketMutationGameTest(Options options) {
        this.options = options;
        service = options.runtime().doors().pocketOperations();
        level = options.player().level();
        original = new PocketLayout(options.pocket());
        stored = new BlockPos(original.minX() + 2, original.minY() + 1, original.minZ() + 2);
        displaced = new BlockPos(original.maxX() + 2, original.minY() + 1, original.minZ() + 3);
    }

    public static CompletableFuture<Boolean> verify(Options options) {
        return new MinecraftPocketMutationGameTest(options).run();
    }

    private CompletableFuture<Boolean> run() {
        chest(stored, Items.DIAMOND, 3);
        return service.snapshot(current(), "runtime-proof").thenComposeAsync(saved -> {
            level.setBlock(stored, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            return service.restore(saved, "runtime-proof");
        }, level.getServer()).thenComposeAsync(restored -> {
            assertStored();
            Path source = service.snapshots().file(restored.spaceId(), "runtime-proof");
            Path target = service.templates().file("runtime-template");
            return CompletableFuture.runAsync(() -> copy(source, target))
                .thenComposeAsync(ignored -> service.apply(current(), "runtime-template", true), level.getServer());
        }, level.getServer()).thenComposeAsync(applied -> {
            options.helper().assertTrue(applied.templateName().equals("runtime-template"), "Template selection did not persist");
            assertStored();
            return service.resize(applied, applied.shell().withSize(original.size() + 4), true);
        }, level.getServer()).thenComposeAsync(grown -> {
            options.helper().assertTrue(grown.status() == PocketResizeOutcome.Status.RESIZED, "Pocket did not grow");
            assertStored();
            chest(displaced, Items.EMERALD, 1);
            return service.resize(current(), options.pocket().shell(), true);
        }, level.getServer()).thenComposeAsync(refused -> {
            options.helper().assertTrue(refused.status() == PocketResizeOutcome.Status.NON_EMPTY_CONTAINERS,
                "Confirmed shrink destroyed a non-empty container");
            options.helper().assertTrue(level.getBlockEntity(displaced) instanceof Container container
                && container.getItem(0).is(Items.EMERALD), "Refused resize mutated container contents");
            Container container = (Container) level.getBlockEntity(displaced);
            container.clearContent();
            level.setBlock(displaced, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            return service.resize(current(), options.pocket().shell(), false);
        }, level.getServer()).thenComposeAsync(unconfirmed -> {
            options.helper().assertTrue(unconfirmed.status() == PocketResizeOutcome.Status.NEEDS_CONFIRMATION,
                "Destructive pocket shrink did not require confirmation");
            options.helper().assertTrue(level.getBlockState(displaced).is(Blocks.GOLD_BLOCK), "Unconfirmed shrink changed world blocks");
            return service.resize(current(), options.pocket().shell(), true);
        }, level.getServer()).thenApplyAsync(shrunk -> {
            options.helper().assertTrue(shrunk.status() == PocketResizeOutcome.Status.RESIZED
                && shrunk.space().shell().equals(options.pocket().shell()), "Confirmed shrink did not restore original shell");
            options.helper().assertTrue(level.getBlockState(displaced).isAir(), "Confirmed shrink left displaced blocks behind");
            assertStored();
            PocketLayout layout = new PocketLayout(shrunk.space());
            options.helper().assertTrue(options.runtime().doors().state().findEndpointByItem(layout.returnDoorIdentity().itemId())
                .filter(MinecraftPocketRooms.endpoint(layout)::equals).isPresent(), "Resize did not relocate the stored return door");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS pocket_mutations nbt_snapshot block_entity_restore template_apply grow preserve_builds container_guard shrink_confirmation return_door_relocation");
            return true;
        }, level.getServer());
    }

    private PocketSpace current() {
        return options.runtime().doors().state().findPocketById(options.pocket().spaceId()).orElseThrow();
    }

    private void chest(BlockPos position, Item item, int count) {
        level.setBlock(position, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
        options.helper().assertTrue(level.getBlockEntity(position) instanceof Container, "Pocket fixture chest has no inventory");
        Container container = (Container) level.getBlockEntity(position);
        container.setItem(0, new ItemStack(item, count));
        level.getBlockEntity(position).setChanged();
    }

    private void assertStored() {
        options.helper().assertTrue(level.getBlockState(stored).is(Blocks.CHEST), "Snapshot lost chest block state");
        options.helper().assertTrue(level.getBlockEntity(stored) instanceof Container container
            && container.getItem(0).is(Items.DIAMOND) && container.getItem(0).getCount() == 3,
            "Snapshot or resize lost chest inventory NBT");
    }

    private static void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    public record Options(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer player, PocketSpace pocket) {
    }
}
