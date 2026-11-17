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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

final class MinecraftEntitySnapshots {
    private MinecraftEntitySnapshots() {
    }

    static byte[] capture(Entity entity) {
        return capture(entity, false);
    }

    static byte[] captureMember(Entity entity) {
        return capture(entity, true);
    }

    private static byte[] capture(Entity entity, boolean member) {
        CompoundTag tag = new CompoundTag();
        if (!entity.saveAsPassenger(tag)) {
            return null;
        }
        normalizeGravity(tag);
        removeIdentity(tag);
        if (member) {
            tag.remove("Passengers");
            tag.remove("Leash");
        }
        return tag.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Entity create(byte[] snapshot, ServerLevel level) {
        try {
            CompoundTag tag = TagParser.parseTag(new String(snapshot, StandardCharsets.UTF_8));
            removeIdentity(tag);
            return EntityType.loadEntityRecursive(tag, level, Function.identity());
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Invalid entity transfer snapshot", error);
        }
    }

    static void removeIdentity(CompoundTag tag) {
        tag.remove("UUID");
        for (Tag passenger : tag.getList("Passengers", Tag.TAG_COMPOUND)) {
            if (passenger instanceof CompoundTag child) {
                removeIdentity(child);
            }
        }
    }

    static void normalizeGravity(CompoundTag tag) {
        GravityFrame frame = MinecraftGravity.fromIndex(tag.contains("wormholesGravity", Tag.TAG_INT) ? tag.getInt("wormholesGravity") : GravityFrame.WORLD.orientation().index());
        ListTag rotation = tag.getList("Rotation", Tag.TAG_FLOAT);
        if (!frame.equals(GravityFrame.WORLD) && rotation.size() == 2) {
            Angles.Look world = frame.look(rotation.getFloat(0), rotation.getFloat(1)).look();
            rotation.set(0, FloatTag.valueOf(world.yaw()));
            rotation.set(1, FloatTag.valueOf(world.pitch()));
        }
        tag.remove("wormholesGravity");
        for (Tag passenger : tag.getList("Passengers", Tag.TAG_COMPOUND)) {
            if (passenger instanceof CompoundTag child) {
                normalizeGravity(child);
            }
        }
    }
}
