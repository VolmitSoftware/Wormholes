package art.arcane.wormholes.network;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.math.Angles;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

final class MinecraftEntitySnapshots {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private MinecraftEntitySnapshots() {
    }

    static byte[] capture(Entity entity) {
        return capture(entity, false);
    }

    static byte[] captureMember(Entity entity) {
        return capture(entity, true);
    }

    private static byte[] capture(Entity entity, boolean member) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
            if (!entity.saveAsPassenger(output)) {
                return null;
            }
            CompoundTag tag = output.buildResult();
            normalizeGravity(tag);
            removeIdentity(tag);
            if (member) {
                tag.remove("Passengers");
                tag.remove("leash");
            }
            return tag.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static Entity create(byte[] snapshot, ServerLevel level) {
        try {
            CompoundTag tag = TagParser.parseCompoundFully(new String(snapshot, StandardCharsets.UTF_8));
            removeIdentity(tag);
            return EntityType.loadEntityRecursive(tag, level, new EntitySpawnRequest(EntitySpawnReason.LOAD, false), EntityProcessor.NOP);
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Invalid entity transfer snapshot", error);
        }
    }

    static void removeIdentity(CompoundTag tag) {
        tag.remove("UUID");
        for (Tag passenger : tag.getListOrEmpty("Passengers")) {
            if (passenger instanceof CompoundTag child) {
                removeIdentity(child);
            }
        }
    }

    static void normalizeGravity(CompoundTag tag) {
        GravityFrame frame = MinecraftGravity.fromIndex(tag.getIntOr("wormholesGravity", GravityFrame.WORLD.orientation().index()));
        ListTag rotation = tag.getListOrEmpty("Rotation");
        if (!frame.equals(GravityFrame.WORLD) && rotation.size() == 2) {
            Angles.Look world = frame.look(rotation.getFloatOr(0, 0.0F), rotation.getFloatOr(1, 0.0F)).look();
            rotation.set(0, FloatTag.valueOf(world.yaw()));
            rotation.set(1, FloatTag.valueOf(world.pitch()));
        }
        tag.remove("wormholesGravity");
        for (Tag passenger : tag.getListOrEmpty("Passengers")) {
            if (passenger instanceof CompoundTag child) {
                normalizeGravity(child);
            }
        }
    }
}
