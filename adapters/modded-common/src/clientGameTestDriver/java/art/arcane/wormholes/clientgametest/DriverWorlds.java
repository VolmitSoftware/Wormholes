package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

final class DriverWorlds {
    private static final int LOAD_TIMEOUT_TICKS = 1200;

    private final DriverClient client;

    DriverWorlds(DriverClient client) {
        this.client = client;
    }

    void singleplayer(Runnable body) {
        client.runOnClient(DriverWorlds::openCreateWorld);
        client.clickButton("selectWorld.create");
        awaitWorld();
        body.run();
        IntegratedServer server = client.computeOnClient(Minecraft::getSingleplayerServer);
        client.runOnClient(minecraft -> {
            minecraft.level.disconnect(Component.translatable("menu.savingLevel"));
            minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
        });
        client.waitFor(minecraft -> minecraft.level == null && !server.getRunningThread().isAlive(), LOAD_TIMEOUT_TICKS);
        returnToTitle();
    }

    void dedicated(String address, Runnable body) {
        try (ExternalServerSession session = ExternalServerSession.connect(client, address)) {
            body.run();
        }
    }

    private void awaitWorld() {
        for (int tick = 0; tick < LOAD_TIMEOUT_TICKS; tick++) {
            if (client.computeOnClient(DriverWorlds::experimentalWarning)) {
                client.clickButton("gui.yes");
            }
            String disconnected = client.computeOnClient(minecraft -> ClientGui.screen(minecraft) instanceof DisconnectedScreen screen
                ? screen.getNarrationMessage().getString() : null);
            if (disconnected != null) {
                throw new AssertionError("disconnected while joining: " + disconnected);
            }
            if (client.computeOnClient(minecraft -> ClientGui.screen(minecraft) instanceof BackupConfirmScreen)) {
                client.clickButton("selectWorld.backupJoinSkipButton");
            }
            if (client.computeOnClient(minecraft -> minecraft.level != null && !(ClientGui.screen(minecraft) instanceof LevelLoadingScreen))) {
                return;
            }
            client.waitTicks(1);
        }
        throw new AssertionError("the world did not load within " + LOAD_TIMEOUT_TICKS + " ticks");
    }

    private void returnToTitle() {
        client.waitTicks(2);
        client.runOnClient(minecraft -> ClientGui.setScreen(minecraft, new TitleScreen()));
    }

    private static void openCreateWorld(Minecraft minecraft) {
        CreateWorldScreen.openFresh(minecraft, () -> ClientGui.setScreen(minecraft, new TitleScreen()));
        if (!(ClientGui.screen(minecraft) instanceof CreateWorldScreen screen)) {
            throw new AssertionError("the create world screen did not open");
        }
        WorldCreationUiState state = screen.getUiState();
        Holder<WorldPreset> flat = state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT);
        state.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
        state.setSeed("1");
        state.setGenerateStructures(false);
        state.setAllowCommands(true);
        state.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
        state.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
        state.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
        state.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, null);
    }

    private static boolean experimentalWarning(Minecraft minecraft) {
        return ClientGui.screen(minecraft) instanceof ConfirmScreen screen && screen.getTitle().getContents() instanceof TranslatableContents contents
            && "selectWorld.warning.experimental.title".equals(contents.getKey());
    }
}
