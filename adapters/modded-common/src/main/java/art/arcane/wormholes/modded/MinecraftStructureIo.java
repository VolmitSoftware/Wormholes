package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.StructureIo;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public final class MinecraftStructureIo implements StructureIo<StructureTemplate> {
    private final HolderGetter<Block> blocks;

    public MinecraftStructureIo(HolderGetter<Block> blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

    @Override
    public Optional<StructureTemplate> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        CompoundTag contents = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        StructureTemplate structure = new StructureTemplate();
        structure.load(blocks, contents);
        return Optional.of(structure);
    }

    @Override
    public void save(StructureTemplate structure, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            NbtIo.writeCompressed(structure.save(new CompoundTag()), temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
