package art.arcane.wormholes.gametest;

import art.arcane.wormholes.modded.MinecraftGameTestPlayer;
import art.arcane.wormholes.modded.RuntimeBaselineEnvironment;
import art.arcane.wormholes.modded.WormholesGameTests;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.NetworkProtocol;
import net.minecraftforge.registries.RegisterEvent;

@Mod("wormholes")
public final class ForgeGameTests {
    public ForgeGameTests(FMLJavaModLoadingContext context) {
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, RuntimeBaselineEnvironment.ID, () -> RuntimeBaselineEnvironment.CODEC));
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
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.RECIPE_BOOK_RUNTIME, () -> WormholesGameTests::recipeBookRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.EFFECTS_RUNTIME, () -> WormholesGameTests::effectsRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.LOOK_LABEL_RUNTIME, () -> WormholesGameTests::lookLabelRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.RTP_RUNTIME, () -> WormholesGameTests::rtpRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.ENTITY_PROJECTION_RUNTIME, () -> WormholesGameTests::entityProjectionRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.MENU_PARITY_RUNTIME, () -> WormholesGameTests::menuParityRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.OCCLUSION_SKIN_RUNTIME, () -> WormholesGameTests::occlusionSkinRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_DIRT_RUNTIME, () -> WormholesGameTests::projectionDirtRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_GAZE_RUNTIME, () -> WormholesGameTests::projectionGazeRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_RETARGET_RUNTIME, () -> WormholesGameTests::projectionRetargetRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_SECTION_CACHE_RUNTIME, () -> WormholesGameTests::projectionSectionCacheRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.PROJECTION_PLATE_CAPTURE_RUNTIME, () -> WormholesGameTests::projectionPlateCaptureRuntime));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.CLIENTVIEW_NEGOTIATION, () -> WormholesGameTests::clientViewNegotiation));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.CLIENTVIEW_STREAM, () -> WormholesGameTests::clientViewStream));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.SEAMLESS_VALIDATION, () -> WormholesGameTests::seamlessValidation));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.REMOTE_VIEW, () -> WormholesGameTests::remoteView));
        RegisterEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.register(Registries.TEST_FUNCTION, WormholesGameTests.APERTURE_SHAPE_RUNTIME, () -> WormholesGameTests::apertureShapeRuntime));
        Channel<CustomPacketPayload> clientView = ChannelBuilder.named(ClientViewPayload.ID).optional().payloadChannel().any()
            .bidirectional().add(ClientViewPayload.TYPE, ClientViewPayload.CODEC, ForgeGameTests::clientViewPayload)
            .build();
        WormholesGameTests.RUNTIME.clientViews().packets(payload -> NetworkProtocol.PLAY.buildPacket(PacketFlow.CLIENTBOUND, clientView, payload));
        MinecraftGameTestPlayer.configureClientboundPackets(ForgeGameTests::receivedClientView);
        RegisterCommandsEvent.BUS.addListener(event -> WormholesGameTests.RUNTIME.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> WormholesGameTests.start(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> tick());
        ServerStoppingEvent.BUS.addListener(event -> WormholesGameTests.RUNTIME.stop());
    }

    private static void clientViewPayload(ClientViewPayload payload, CustomPayloadEvent.Context context) {
        context.setPacketHandled(true);
        WormholesGameTests.RUNTIME.clientViews().receive(context.getConnection(), payload.data());
    }

    private static Packet<?> receivedClientView(Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !(custom.payload() instanceof ForgePayload payload)
            || !payload.id().equals(ClientViewPayload.ID) || payload.data() == null) {
            return packet;
        }
        return new ClientboundCustomPayloadPacket(ClientViewPayload.CODEC.decode(payload.data()));
    }

    private void tick() {
        if (!ForgeGameTestHooks.isGametestEnabled()) {
            GameTestTicker.SINGLETON.tick();
        }
        WormholesGameTests.RUNTIME.tick();
    }
}
