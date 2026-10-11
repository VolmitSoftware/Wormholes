package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.PacketEventsImplHelper;
import io.github.retrooper.packetevents.injector.handlers.PacketEventsEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

final class PacketEventsWriteCompletionTest {
    @Test
    void actualSuccessfulWriteReportsSuccessExactlyOnce() throws Exception {
        try (WriteFixture fixture = new WriteFixture(false)) {
            ChannelFuture future = fixture.write(false);
            assertTrue(future.isSuccess());
            assertEquals(1, fixture.outcomes.size());
            assertTrue(fixture.outcomes.getFirst().success());
            assertEquals(1, fixture.legacyTasks.get());
        }
    }

    @Test
    void actualFailedChannelPromiseReportsFailureAndCause() throws Exception {
        try (WriteFixture fixture = new WriteFixture(true)) {
            ChannelFuture future = fixture.write(false);
            assertFalse(future.isSuccess());
            assertEquals(1, fixture.outcomes.size());
            assertFalse(fixture.outcomes.getFirst().success());
            assertSame(fixture.failure, fixture.outcomes.getFirst().cause());
            assertEquals(1, fixture.legacyTasks.get());
        }
    }

    @Test
    void cancelledPacketDoesNotReportDeliveryDespiteSuccessfulPromise() throws Exception {
        try (WriteFixture fixture = new WriteFixture(false)) {
            when(fixture.event.isCancelled()).thenReturn(true);
            fixture.cancel = true;
            ChannelFuture future = fixture.write(false);
            assertTrue(future.isSuccess());
            assertEquals(1, fixture.outcomes.size());
            assertFalse(fixture.outcomes.getFirst().success());
            assertEquals(1, fixture.legacyTasks.get());
            assertEquals(0, fixture.writes.get());
        }
    }

    @Test
    void nativeVoidPromiseStillDeliversObservableCompletion() throws Exception {
        try (WriteFixture fixture = new WriteFixture(false)) {
            fixture.write(true);
            assertEquals(1, fixture.outcomes.size());
            assertTrue(fixture.outcomes.getFirst().success());
            assertEquals(1, fixture.legacyTasks.get());
        }
    }

    private record Outcome(boolean success, Throwable cause) {
    }

    private static final class WriteFixture implements AutoCloseable {
        private final PacketSendEvent event = mock(PacketSendEvent.class);
        private final User user = mock(User.class);
        private final List<Outcome> outcomes = new ArrayList<>();
        private final AtomicInteger legacyTasks = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();
        private final IOException failure = new IOException("injected transport failure");
        private final MockedStatic<PacketEventsImplHelper> helper;
        private final EmbeddedChannel channel;
        private boolean cancel;

        private WriteFixture(boolean failWrite) throws Exception {
            List<BiConsumer<Boolean, Throwable>> completions = new ArrayList<>();
            completions.add((success, cause) -> outcomes.add(new Outcome(success, cause)));
            when(event.hasTasksOnWriteCompletion()).thenReturn(true);
            when(event.getTasksOnWriteCompletion()).thenReturn(completions);
            when(event.hasTasksAfterSend()).thenReturn(true);
            when(event.getTasksAfterSend()).thenReturn(List.of(legacyTasks::incrementAndGet));
            helper = mockStatic(PacketEventsImplHelper.class, invocation -> {
                if (invocation.getMethod().getName().equals("handleClientBoundPacket")) {
                    if (cancel) {
                        invocation.getArgument(3, ByteBuf.class).clear();
                    }
                    return event;
                }
                return null;
            });
            try {
                channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        writes.incrementAndGet();
                        if (failWrite) {
                            ReferenceCountUtil.release(message);
                            promise.tryFailure(failure);
                        } else {
                            context.write(message, promise);
                        }
                    }
                }, new PacketEventsEncoder(user));
            } catch (RuntimeException | Error exception) {
                helper.close();
                throw exception;
            }
        }

        private ChannelFuture write(boolean voidPromise) {
            ByteBuf buffer = Unpooled.buffer().writeByte(1);
            ChannelFuture future = voidPromise
                ? channel.writeAndFlush(buffer, channel.voidPromise())
                : channel.writeAndFlush(buffer);
            channel.runPendingTasks();
            return future;
        }

        @Override
        public void close() {
            try {
                channel.finishAndReleaseAll();
            } finally {
                helper.close();
            }
        }
    }
}
