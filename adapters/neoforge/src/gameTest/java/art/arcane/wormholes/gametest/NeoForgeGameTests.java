package art.arcane.wormholes.gametest;

import art.arcane.wormholes.modded.MinecraftDoorRecipes;
import art.arcane.wormholes.modded.MinecraftProxyPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import art.arcane.wormholes.modded.WormholesGameTests;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestTicker;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod("wormholes")
public final class NeoForgeGameTests {
    public NeoForgeGameTests(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional()
            .playToClient(MinecraftProxyPayload.TYPE, MinecraftProxyPayload.CODEC, (payload, context) -> { }));
        MinecraftGameTestPlayer.configureConnections(NetworkRegistry::configureMockConnection);
        bus.addListener((RegisterEvent event) -> MinecraftDoorRecipes.serializers().forEach((id, serializer) ->
            event.register(Registries.RECIPE_SERIALIZER, id, () -> serializer)));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.PORTAL_RUNTIME, () -> WormholesGameTests::portalRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.HANDOFF_RUNTIME, () -> WormholesGameTests::handoffRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.COSTS_RUNTIME, () -> WormholesGameTests::costsRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.RULES_RUNTIME, () -> WormholesGameTests::rulesRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.ENTITY_TRANSFERS_RUNTIME, () -> WormholesGameTests::entityTransfersRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION, WormholesGameTests.NEXUS_RUNTIME, () -> WormholesGameTests::nexusRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION, WormholesGameTests.OPS_RUNTIME, () -> WormholesGameTests::opsRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION, WormholesGameTests.EFFECTS_RUNTIME, () -> WormholesGameTests::effectsRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.RTP_RUNTIME, () -> WormholesGameTests::rtpRuntime));
        bus.addListener((RegisterEvent event) -> event.register(Registries.TEST_FUNCTION,
            WormholesGameTests.ENTITY_PROJECTION_RUNTIME, () -> WormholesGameTests::entityProjectionRuntime));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> WormholesGameTests.RUNTIME.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> WormholesGameTests.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WormholesGameTests.RUNTIME.stop());
    }

    private void tick() {
        if (!GameTestHooks.isGametestEnabled()) {
            GameTestTicker.SINGLETON.tick();
        }
        WormholesGameTests.RUNTIME.tick();
    }
}
