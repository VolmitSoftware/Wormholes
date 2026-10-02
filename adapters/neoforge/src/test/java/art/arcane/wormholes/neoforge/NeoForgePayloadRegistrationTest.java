package art.arcane.wormholes.neoforge;

import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

public class NeoForgePayloadRegistrationTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void clientLifecycleRegistersTheNativeHandlerAndPreservesReplies() {
        IEventBus bus = mock(IEventBus.class);
        WormholesClient client = mock(WormholesClient.class);
        RegisterClientPayloadHandlersEvent event = mock(RegisterClientPayloadHandlersEvent.class);
        IPayloadContext context = mock(IPayloadContext.class);
        byte[] incoming = {1, 2, 3};
        byte[] outgoing = {4, 5, 6};
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(() -> WormholesClient.initialize(any(), any())).thenReturn(client);
            new WormholesNeoForgeClient(bus);
            ArgumentCaptor<Consumer<RegisterClientPayloadHandlersEvent>> registrations = ArgumentCaptor.captor();
            verify(bus).addListener(registrations.capture());
            registrations.getValue().accept(event);
            ArgumentCaptor<IPayloadHandler<ClientViewPayload>> handlers = ArgumentCaptor.captor();
            verify(event).register(eq(ClientViewPayload.TYPE), handlers.capture());
            handlers.getValue().handle(new ClientViewPayload(incoming), context);
            ArgumentCaptor<Consumer<byte[]>> replies = ArgumentCaptor.captor();
            verify(client).receive(eq(incoming), replies.capture());
            replies.getValue().accept(outgoing);
            ArgumentCaptor<ClientViewPayload> payloads = ArgumentCaptor.captor();
            verify(context).reply(payloads.capture());
            assertArrayEquals(outgoing, payloads.getValue().data());
        }
    }

    @Test
    public void commonLifecycleKeepsNativePayloadRegisteredForBothPhases() {
        IEventBus bus = mock(IEventBus.class);
        RegisterPayloadHandlersEvent event = mock(RegisterPayloadHandlersEvent.class);
        PayloadRegistrar registrar = mock(PayloadRegistrar.class);
        when(event.registrar("1")).thenReturn(registrar);
        when(registrar.optional()).thenReturn(registrar);
        when(registrar.playToClient(any(), any(), any())).thenReturn(registrar);
        try (MockedConstruction<WormholesModRuntime> runtimes = mockConstruction(WormholesModRuntime.class)) {
            new WormholesNeoForge(bus);
            ArgumentCaptor<Consumer<RegisterPayloadHandlersEvent>> registrations = ArgumentCaptor.captor();
            verify(bus, times(2)).addListener(registrations.capture());
            registrations.getAllValues().getFirst().accept(event);
            verify(registrar).commonBidirectional(eq(ClientViewPayload.TYPE), eq(ClientViewPayload.CODEC), any());
        }
    }
}
