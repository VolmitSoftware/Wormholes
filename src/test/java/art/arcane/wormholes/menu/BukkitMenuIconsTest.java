package art.arcane.wormholes.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class BukkitMenuIconsTest {
    @Test
    void namespacedItemIdsResolveToTheirMaterial() {
        BukkitMenuIcons icons = new BukkitMenuIcons(logger(new ArrayList<>()), ref -> Optional.empty(), material -> true);

        assertEquals(Material.ENDER_EYE, icons.material("minecraft:ender_eye"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, icons.material("minecraft:gray_stained_glass_pane"));
        assertEquals(Material.COPPER_TORCH, icons.material("minecraft:copper_torch"));
    }

    @Test
    void unknownItemIdsRenderAsBarrierAndWarnOncePerId() {
        List<LogRecord> records = new ArrayList<>();
        BukkitMenuIcons icons = new BukkitMenuIcons(logger(records), ref -> Optional.empty(), material -> true);

        assertEquals(Material.BARRIER, icons.material("examplemod:widget"));
        assertEquals(Material.BARRIER, icons.material("examplemod:widget"));
        assertEquals(Material.BARRIER, icons.material("minecraft:not_an_item"));

        assertEquals(2, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
    }

    @Test
    void materialsWithoutAnItemFormRenderAsBarrierAndWarn() {
        List<LogRecord> records = new ArrayList<>();
        BukkitMenuIcons icons = new BukkitMenuIcons(logger(records), ref -> Optional.empty(), material -> material != Material.WATER);

        assertEquals(Material.BARRIER, icons.material("minecraft:water"));
        assertEquals(Material.WATER_BUCKET, icons.material("minecraft:water_bucket"));

        assertEquals(1, records.size());
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
