package art.arcane.wormholes.render.blockentity;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Bell;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.EntityType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/**
 * Builds the client-facing tag of the whitelisted block entities from the Bukkit block-state API.
 * Used when no vanilla snapshot accessor resolves on the running platform.
 */
public final class BlockEntityNbtAssembler {
    private static final DecoratedPot.Side[] SHERD_ORDER = {
        DecoratedPot.Side.BACK, DecoratedPot.Side.LEFT, DecoratedPot.Side.RIGHT, DecoratedPot.Side.FRONT
    };

    private BlockEntityNbtAssembler() {
    }

    public static NBTCompound assemble(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state instanceof Sign sign) {
            return sign(sign);
        }
        if (state instanceof Skull skull) {
            return skull(skull);
        }
        if (state instanceof Banner banner) {
            return banner(banner);
        }
        if (state instanceof CreatureSpawner spawner) {
            return spawner(spawner);
        }
        if (state instanceof DecoratedPot pot) {
            return pot(pot);
        }
        if (state instanceof Bell) {
            return new NBTCompound();
        }
        return null;
    }

    private static NBTCompound sign(Sign sign) {
        NBTCompound tag = new NBTCompound();
        tag.setTag("front_text", signSide(sign.getSide(Side.FRONT)));
        tag.setTag("back_text", signSide(sign.getSide(Side.BACK)));
        tag.setTag("is_waxed", new NBTByte(sign.isWaxed() ? (byte) 1 : (byte) 0));
        return tag;
    }

    private static NBTCompound signSide(SignSide side) {
        NBTCompound tag = new NBTCompound();
        NBTList<NBTString> messages = NBTList.createStringList();
        String[] lines = side == null ? new String[0] : side.getLines();
        for (int index = 0; index < 4; index++) {
            String line = lines != null && index < lines.length && lines[index] != null ? lines[index] : "";
            messages.addTag(new NBTString(jsonText(line)));
        }
        tag.setTag("messages", messages);
        DyeColor color = side == null ? null : side.getColor();
        tag.setTag("color", new NBTString(color == null ? "black" : color.name().toLowerCase(Locale.ROOT)));
        tag.setTag("has_glow_text", new NBTByte(side != null && side.isGlowingText() ? (byte) 1 : (byte) 0));
        return tag;
    }

    private static NBTCompound skull(Skull skull) {
        NBTCompound tag = new NBTCompound();
        PlayerProfile profile = skull.getOwnerProfile();
        if (profile == null) {
            return tag;
        }
        NBTCompound profileTag = new NBTCompound();
        String name = profile.getName();
        if (name != null && !name.isEmpty()) {
            profileTag.setTag("name", new NBTString(name));
        }
        UUID id = profile.getUniqueId();
        if (id != null) {
            profileTag.setTag("id", new NBTIntArray(new int[] {
                (int) (id.getMostSignificantBits() >> 32), (int) id.getMostSignificantBits(),
                (int) (id.getLeastSignificantBits() >> 32), (int) id.getLeastSignificantBits()}));
        }
        PlayerTextures textures = profile.getTextures();
        URL skin = textures == null ? null : textures.getSkin();
        if (skin != null) {
            NBTList<NBTCompound> properties = NBTList.createCompoundList();
            NBTCompound property = new NBTCompound();
            property.setTag("name", new NBTString("textures"));
            String payload = "{\"textures\":{\"SKIN\":{\"url\":\"" + escape(skin.toString()) + "\"}}}";
            property.setTag("value", new NBTString(Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8))));
            properties.addTag(property);
            profileTag.setTag("properties", properties);
        }
        tag.setTag("profile", profileTag);
        return tag;
    }

    private static NBTCompound banner(Banner banner) {
        NBTCompound tag = new NBTCompound();
        NBTList<NBTCompound> patterns = NBTList.createCompoundList();
        List<Pattern> source = banner.getPatterns();
        if (source != null) {
            for (Pattern pattern : source) {
                if (pattern == null || pattern.getPattern() == null || pattern.getColor() == null) {
                    continue;
                }
                NBTCompound entry = new NBTCompound();
                entry.setTag("color", new NBTString(pattern.getColor().name().toLowerCase(Locale.ROOT)));
                entry.setTag("pattern", new NBTString(pattern.getPattern().getKey().toString()));
                patterns.addTag(entry);
            }
        }
        tag.setTag("patterns", patterns);
        return tag;
    }

    private static NBTCompound spawner(CreatureSpawner spawner) {
        NBTCompound tag = new NBTCompound();
        EntityType type = spawner.getSpawnedType();
        if (type == null) {
            return tag;
        }
        NBTCompound entity = new NBTCompound();
        entity.setTag("id", new NBTString(type.getKey().toString()));
        NBTCompound spawnData = new NBTCompound();
        spawnData.setTag("entity", entity);
        tag.setTag("SpawnData", spawnData);
        return tag;
    }

    private static NBTCompound pot(DecoratedPot pot) {
        NBTCompound tag = new NBTCompound();
        NBTList<NBTString> sherds = NBTList.createStringList();
        Map<DecoratedPot.Side, Material> source = pot.getSherds();
        for (DecoratedPot.Side side : SHERD_ORDER) {
            Material sherd = source == null ? null : source.get(side);
            sherds.addTag(new NBTString(sherd == null ? "minecraft:brick" : sherd.getKey().toString()));
        }
        tag.setTag("sherds", sherds);
        return tag;
    }

    static String jsonText(String text) {
        return "{\"text\":\"" + escape(text) + "\"}";
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }
}
