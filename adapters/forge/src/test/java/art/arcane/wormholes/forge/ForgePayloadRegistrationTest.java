package art.arcane.wormholes.forge;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.protocol.MenuCodec;
import art.arcane.wormholes.menu.protocol.MenuProtocolException;
import art.arcane.wormholes.menu.protocol.MenuServerInfo;
import art.arcane.wormholes.menu.protocol.MenuServerMessage;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.menu.ClientMenus;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import art.arcane.wormholes.modded.menu.MenuPayload;
import art.arcane.wormholes.modded.menu.MinecraftMenuTransport;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.ForgePayload;
import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.payload.PayloadConnection;
import net.minecraftforge.network.payload.PayloadFlow;
import net.minecraftforge.network.payload.PayloadProtocol;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ForgePayloadRegistrationTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void menuChannelIsAnOptionalPlayChannel() {
        try (Registration registration = Registration.start(mock(MinecraftMenuTransport.class))) {
            verify(registration.menus().builder()).optional();
            verify(registration.menus().connection()).play();
            verify(registration.menus().connection(), never()).any();
            verify(registration.menus().protocol()).bidirectional();
        }
    }

    @Test
    public void serverboundMenuFramesRouteToTheTransport() {
        MinecraftMenuTransport transport = mock(MinecraftMenuTransport.class);
        CustomPayloadEvent.Context context = mock(CustomPayloadEvent.Context.class);
        ServerPlayer player = mock(ServerPlayer.class);
        byte[] frame = {32, 1};
        when(context.getSender()).thenReturn(player);
        try (Registration registration = Registration.start(transport)) {
            registration.menuHandler().accept(new MenuPayload(frame), context);

            verify(transport).receive(player, frame);
            verify(context).setPacketHandled(true);
        }
    }

    @Test
    public void clientboundMenuFramesRouteToTheMenuSession() throws MenuProtocolException {
        CustomPayloadEvent.Context context = mock(CustomPayloadEvent.Context.class);
        when(context.isClientSide()).thenReturn(true);
        try (Registration registration = Registration.start(mock(MinecraftMenuTransport.class))) {
            ClientMenus.initialize(bytes -> { }, () -> true);
            ClientMenus.connected();

            registration.menuHandler().accept(new MenuPayload(MenuCodec.encodeServer(new MenuServerMessage.Accept(1, new MenuServerInfo("test-version", "Test 1.0", true)))), context);

            assertEquals(1, ClientMenus.session().version());
            verify(context).setPacketHandled(true);
            ClientMenus.disconnected();
        }
    }

    @Test
    public void forgeCapsServerboundMenuBodiesBeforeTheMenuCodecCopiesThem() {
        try (MockedStatic<NetworkRegistry> registry = mockStatic(NetworkRegistry.class)) {
            registry.when(() -> NetworkRegistry.findTarget(MenuPayload.ID)).thenReturn(mock(NetworkInstance.class));

            assertThrows(IllegalArgumentException.class, () -> decodeServerbound(MenuLimits.MAX_C2S_BYTES + 1));
            ForgePayload limit = (ForgePayload) decodeServerbound(MenuLimits.MAX_C2S_BYTES).payload();
            assertEquals(MenuLimits.MAX_C2S_BYTES, MenuPayload.CODEC.decode(limit.data()).data().length);
        }
    }

    private static ServerboundCustomPayloadPacket decodeServerbound(int bodyBytes) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeIdentifier(MenuPayload.ID);
            buffer.writeZero(bodyBytes);
            return ServerboundCustomPayloadPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private record Chain(ChannelBuilder builder, PayloadConnection<CustomPacketPayload> connection, PayloadProtocol<?, CustomPacketPayload> protocol,
                         PayloadFlow<?, CustomPacketPayload> flow) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Chain create() {
            ChannelBuilder builder = mock(ChannelBuilder.class);
            PayloadConnection connection = mock(PayloadConnection.class);
            PayloadProtocol protocol = mock(PayloadProtocol.class);
            PayloadFlow flow = mock(PayloadFlow.class);
            when(builder.optional()).thenReturn(builder);
            when(builder.payloadChannel()).thenReturn(connection);
            when(connection.play()).thenReturn(protocol);
            when(connection.any()).thenReturn(protocol);
            when(protocol.bidirectional()).thenReturn(flow);
            when(flow.add(any(), any(), any())).thenReturn(flow);
            when(flow.build()).thenReturn(mock(Channel.class));
            return new Chain(builder, connection, protocol, flow);
        }
    }

    private record Registration(MockedStatic<ChannelBuilder> builders, MockedConstruction<WormholesModRuntime> runtimes, Chain menus)
        implements AutoCloseable {
        private static Registration start(MinecraftMenuTransport transport) {
            Chain clientView = Chain.create();
            Chain menus = Chain.create();
            MockedStatic<ChannelBuilder> builders = mockStatic(ChannelBuilder.class);
            builders.when(() -> ChannelBuilder.named(ClientViewPayload.ID)).thenReturn(clientView.builder());
            builders.when(() -> ChannelBuilder.named(MenuPayload.ID)).thenReturn(menus.builder());
            MockedConstruction<WormholesModRuntime> runtimes = mockConstruction(WormholesModRuntime.class, (runtime, construction) -> {
                when(runtime.menuTransport()).thenReturn(transport);
                when(runtime.clientViews()).thenReturn(mock(MinecraftClientViewService.class));
            });
            try {
                new WormholesForge(mock(FMLJavaModLoadingContext.class));
            } catch (RuntimeException exception) {
                runtimes.close();
                builders.close();
                throw exception;
            }
            return new Registration(builders, runtimes, menus);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private BiConsumer<MenuPayload, CustomPayloadEvent.Context> menuHandler() {
            ArgumentCaptor<BiConsumer> handlers = ArgumentCaptor.forClass(BiConsumer.class);
            verify((PayloadFlow) menus.flow()).add(eq(MenuPayload.TYPE), eq(MenuPayload.CODEC), handlers.capture());
            return handlers.getValue();
        }

        @Override
        public void close() {
            runtimes.close();
            builders.close();
        }
    }
}
