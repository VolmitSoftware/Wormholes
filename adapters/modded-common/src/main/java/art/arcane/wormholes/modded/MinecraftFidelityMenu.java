package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.lod.LodProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;
import java.util.function.Consumer;

public final class MinecraftFidelityMenu {
    private final WormholesModRuntime runtime;
    private final MinecraftPortal portal;

    public MinecraftFidelityMenu(WormholesModRuntime runtime, MinecraftPortal portal) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.portal = Objects.requireNonNull(portal, "portal");
    }

    public void open(ServerPlayer viewer) {
        AtmosphereMode atmosphere = atmosphereMode(portal);
        AcousticsProfile acoustics = acousticsProfile(portal);
        LodProfile lod = lodProfile(portal);
        Boolean blockEntities = blockEntities(portal);
        MinecraftWindow window = new MinecraftWindow(runtime, viewer);
        window.setTitle(MinecraftPortalText.router(runtime, portal, true));
        window.setViewportHeight(3);
        window.setDecorator(Items.STAINED_GLASS_PANE.cyan());
        window.setElement(0, 0, placard(viewer));
        window.setElement(-3, 1, cycler("fidelity-atmosphere", Items.STAINED_GLASS.lightBlue(), FidelityMessages.MENU_ATMOSPHERE, "mode",
            atmosphere == null ? null : atmosphere.configName(),
            target -> target.setAtmosphereMode(effectiveAtmosphereMode(target).next()),
            target -> target.setAtmosphereMode(null), viewer));
        window.setElement(-1, 1, cycler("fidelity-acoustics", Items.NOTE_BLOCK, FidelityMessages.MENU_ACOUSTICS, "mode",
            acoustics == null ? null : acoustics.configName(),
            target -> target.setAcousticsProfile(effectiveAcousticsProfile(target).next()),
            target -> target.setAcousticsProfile(null), viewer));
        window.setElement(1, 1, cycler("fidelity-lod", Items.SPYGLASS, FidelityMessages.MENU_LOD, "mode",
            lod == null ? null : lod.configName(),
            target -> target.setLodProfile(effectiveLodProfile(target).next()),
            target -> target.setLodProfile(null), viewer));
        window.setElement(3, 1, cycler("fidelity-block-entities", Items.OAK_SIGN, FidelityMessages.MENU_BLOCK_ENTITIES, "state",
            blockEntities == null ? null : blockEntities.toString(),
            target -> target.setBlockEntities(!effectiveBlockEntities(target)),
            target -> target.setBlockEntities(null), viewer));
        window.setElement(0, 2, back(viewer));
        window.setVisible(true);
    }

    static AtmosphereMode atmosphereMode(MinecraftPortal portal) {
        return AtmosphereMode.parse(string(portal, "fidelity.atmosphere"), null);
    }

    static AcousticsProfile acousticsProfile(MinecraftPortal portal) {
        return AcousticsProfile.parse(string(portal, "fidelity.acoustics"), null);
    }

    static LodProfile lodProfile(MinecraftPortal portal) {
        return LodProfile.parse(string(portal, "fidelity.lod"), null);
    }

    static Boolean blockEntities(MinecraftPortal portal) {
        return portal.setting("fidelity.block_entities") instanceof Boolean enabled ? enabled : null;
    }

    private MinecraftElement placard(ServerPlayer viewer) {
        MinecraftElement element = element(viewer, "fidelity-placard", FidelityMessages.MENU_PLACARD, MessageArgs.empty(), Items.COMPARATOR);
        int bedrockViewers = MinecraftClientProfiles.bedrockViewers();
        if (bedrockViewers > 0) {
            element.addLore(MinecraftLegacyText.text(viewer, FidelityMessages.BEDROCK_PROFILE,
                MinecraftPortalText.arguments("count", bedrockViewers)));
        }
        return element;
    }

    private MinecraftElement cycler(String id, Item icon, TextKey name, String placeholder, String value,
                                    Consumer<MinecraftPortal> cycle, Consumer<MinecraftPortal> reset, ServerPlayer viewer) {
        MinecraftElement element = element(viewer, id, FidelityMessages.MENU_HINT, MessageArgs.empty(), icon);
        element.setName(MinecraftLegacyText.text(viewer, name, MinecraftPortalText.arguments(placeholder,
            value == null ? MinecraftMenuText.text(viewer, FidelityMessages.MENU_DEFAULT, MessageArgs.empty()).getString() : value)));
        element.setEnchanted(value != null);
        element.onLeftClick(event -> apply(cycle, viewer));
        element.onShiftLeftClick(event -> apply(reset, viewer));
        return element;
    }

    private MinecraftElement back(ServerPlayer viewer) {
        MinecraftElement element = element(viewer, "fidelity-back", FidelityMessages.MENU_BACK, MessageArgs.empty(), Items.ARROW);
        element.onLeftClick(event -> runtime.schedule(() -> runtime.menus().open(viewer, portal.getId()), 1L));
        return element;
    }

    private void apply(Consumer<MinecraftPortal> change, ServerPlayer viewer) {
        runtime.portals().update(viewer, portal.getId(), change);
        runtime.schedule(() -> open(viewer), 1L);
    }

    private static AtmosphereMode effectiveAtmosphereMode(MinecraftPortal portal) {
        AtmosphereMode mode = atmosphereMode(portal);
        return mode == null ? FidelitySettings.atmosphereModeDefault : mode;
    }

    private static AcousticsProfile effectiveAcousticsProfile(MinecraftPortal portal) {
        AcousticsProfile profile = acousticsProfile(portal);
        return profile == null ? FidelitySettings.acousticsProfileDefault : profile;
    }

    private static LodProfile effectiveLodProfile(MinecraftPortal portal) {
        LodProfile profile = lodProfile(portal);
        return profile == null ? LodProfile.BALANCED : profile;
    }

    private static boolean effectiveBlockEntities(MinecraftPortal portal) {
        Boolean enabled = blockEntities(portal);
        return enabled == null ? FidelitySettings.blockEntities : enabled;
    }

    private static String string(MinecraftPortal portal, String key) {
        return portal.setting(key) instanceof String value ? value : "";
    }

    private static MinecraftElement element(ServerPlayer viewer, String id, LinesKey key, MessageArgs arguments, Item material) {
        MinecraftElement element = new MinecraftElement(id);
        element.setMaterial(material);
        MinecraftLegacyText.apply(viewer, element, key, arguments);
        return element;
    }
}
