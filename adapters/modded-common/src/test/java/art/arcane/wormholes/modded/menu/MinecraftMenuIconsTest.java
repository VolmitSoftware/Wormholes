package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.modded.MinecraftPortalToolsTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftMenuIconsTest {
    private final List<String> warnings = new ArrayList<>();

    @BeforeClass
    public static void bootstrap() {
        MinecraftPortalToolsTest.bootstrap();
    }

    @Test
    public void namespacedIdsResolveThroughTheItemRegistry() {
        MinecraftMenuIcons icons = new MinecraftMenuIcons(warnings::add, reference -> Optional.empty());

        assertSame(Items.ENDER_EYE, icons.item("minecraft:ender_eye"));
        assertSame(Items.RESPAWN_ANCHOR, icons.item("minecraft:respawn_anchor"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void unknownIdsBecomeBarriersAndWarnOncePerId() {
        MinecraftMenuIcons icons = new MinecraftMenuIcons(warnings::add, reference -> Optional.empty());

        assertSame(Items.BARRIER, icons.item("wormholes:missing"));
        assertSame(Items.BARRIER, icons.item("wormholes:missing"));
        assertSame(Items.BARRIER, icons.item("minecraft:air"));
        assertSame(Items.BARRIER, icons.item("not a namespaced id"));

        assertEquals(3, warnings.size());
        assertTrue(warnings.getFirst().contains("wormholes:missing"));
    }

    @Test
    public void templatesResolveThroughTheSuppliedLookup() {
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        Map<String, ItemStack> templates = Map.of("portal", diamond);
        MinecraftMenuIcons icons = new MinecraftMenuIcons(warnings::add, reference -> Optional.ofNullable(templates.get(reference)));

        assertSame(diamond, icons.template("portal").orElseThrow());
        assertTrue(icons.template("other").isEmpty());
    }
}
