package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ClientPalette {
    public static final int LOCAL_BASE = ViewStreamLimits.MAX_SESSION_PALETTE_SIZE;
    private static final int INITIAL_CAPACITY = 256;

    private final HolderLookup<Block> blocks;
    private final BlockState air;
    private final ObjectArrayList<BlockState> localStates;
    private final Reference2IntOpenHashMap<BlockState> localIds;
    private BlockState[] states;
    private int size;
    private int unknownStates;

    public ClientPalette(HolderLookup<Block> blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.air = Blocks.AIR.defaultBlockState();
        this.localStates = new ObjectArrayList<>();
        this.localIds = new Reference2IntOpenHashMap<>();
        this.localIds.defaultReturnValue(-1);
        this.states = new BlockState[INITIAL_CAPACITY];
        reset();
    }

    public static BlockState parse(HolderLookup<Block> blocks, String state) {
        try {
            return BlockStateParser.parseForBlock(blocks, state, false).blockState();
        } catch (CommandSyntaxException | RuntimeException failure) {
            return null;
        }
    }

    public int apply(ViewStreamMessage.Palette palette) throws ViewStreamProtocolException {
        List<ViewStreamMessage.PaletteEntry> entries = palette.entries();
        int applied = 0;
        for (int index = 0; index < entries.size(); index++) {
            ViewStreamMessage.PaletteEntry entry = entries.get(index);
            int id = entry.id();
            if (id < ViewStreamLimits.RESERVED_PALETTE_IDS || id >= ViewStreamLimits.MAX_SESSION_PALETTE_SIZE) {
                throw new ViewStreamProtocolException("palette id " + id + " is reserved or out of range");
            }
            BlockState resolved = parse(blocks, entry.state());
            if (resolved == null) {
                unknownStates++;
                resolved = air;
            }
            ensureCapacity(id + 1);
            states[id] = resolved;
            if (id >= size) {
                size = id + 1;
            }
            applied++;
        }
        return applied;
    }

    public BlockState state(int id) {
        if (id >= LOCAL_BASE) {
            int local = id - LOCAL_BASE;
            return local < localStates.size() ? localStates.get(local) : air;
        }
        if (id < 0 || id >= size) {
            return air;
        }
        BlockState state = states[id];
        return state == null ? air : state;
    }

    public boolean known(int id) {
        if (id >= LOCAL_BASE) {
            return id - LOCAL_BASE < localStates.size();
        }
        return id >= 0 && id < size && states[id] != null;
    }

    public boolean sentinel(int id) {
        return id == ViewStreamLimits.PALETTE_OCCLUDED || id == ViewStreamLimits.PALETTE_BACKING;
    }

    public int localId(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.isAir()) {
            return ViewStreamLimits.PALETTE_AIR;
        }
        int known = localIds.getInt(state);
        if (known >= 0) {
            return known;
        }
        int id = LOCAL_BASE + localStates.size();
        localStates.add(state);
        localIds.put(state, id);
        return id;
    }

    public BlockState air() {
        return air;
    }

    public int size() {
        return size;
    }

    public int localSize() {
        return localStates.size();
    }

    public int unknownStates() {
        return unknownStates;
    }

    public void reset() {
        Arrays.fill(states, null);
        states[ViewStreamLimits.PALETTE_AIR] = air;
        size = ViewStreamLimits.RESERVED_PALETTE_IDS;
        unknownStates = 0;
        localStates.clear();
        localIds.clear();
    }

    private void ensureCapacity(int required) {
        if (required <= states.length) {
            return;
        }
        int next = states.length;
        while (next < required) {
            next <<= 1;
        }
        states = Arrays.copyOf(states, next);
    }
}
