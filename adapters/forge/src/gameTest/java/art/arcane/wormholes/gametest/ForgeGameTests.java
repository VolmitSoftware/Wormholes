package art.arcane.wormholes.gametest;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import art.arcane.wormholes.modded.WormholesGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.RegisterEvent;

@Mod("wormholes")
public final class ForgeGameTests {
    public ForgeGameTests(FMLJavaModLoadingContext context) {
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event -> MinecraftDoorRecipes.serializers().forEach((id, serializer) ->
            event.register(Registries.RECIPE_SERIALIZER, id, () -> serializer)));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PORTAL_RUNTIME, () -> WormholesGameTests::portalRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.HANDOFF_RUNTIME, () -> WormholesGameTests::handoffRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.COSTS_RUNTIME, () -> WormholesGameTests::costsRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.RULES_RUNTIME, () -> WormholesGameTests::rulesRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.LANGUAGE_RUNTIME, () -> WormholesGameTests::languageRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.ENTITY_TRANSFERS_RUNTIME, () -> WormholesGameTests::entityTransfersRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.NEXUS_RUNTIME, () -> WormholesGameTests::nexusRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.OPS_RUNTIME, () -> WormholesGameTests::opsRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.EFFECTS_RUNTIME, () -> WormholesGameTests::effectsRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.RTP_RUNTIME, () -> WormholesGameTests::rtpRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.ENTITY_PROJECTION_RUNTIME, () -> WormholesGameTests::entityProjectionRuntime));
        RegisterCommandsEvent.BUS.addListener(event -> WormholesGameTests.RUNTIME.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> WormholesGameTests.start(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> tick());
        ServerStoppingEvent.BUS.addListener(event -> WormholesGameTests.RUNTIME.stop());
    }

    private void tick() {
        if (!ForgeGameTestHooks.isGametestEnabled()) {
            GameTestTicker.SINGLETON.tick();
        }
        WormholesGameTests.RUNTIME.tick();
    }
}
