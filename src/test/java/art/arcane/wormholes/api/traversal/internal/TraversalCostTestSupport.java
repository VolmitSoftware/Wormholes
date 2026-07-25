package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalDestination;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.TraversalReceipt;
import art.arcane.wormholes.api.traversal.TraversalRefundReason;
import art.arcane.wormholes.api.traversal.TraversalReservation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class TraversalCostTestSupport {
    private TraversalCostTestSupport() {
    }

    static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(TraversalCostTestSupport.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "tester-" + id;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "player(" + id + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    static TraversalContext localContext(Player traveler) {
        return TraversalContext.local(traveler, UUID.randomUUID(), "Alpha", new Location(null, 0.0D, 64.0D, 0.0D),
            TraversalDestination.portal(UUID.randomUUID(), "Beta", new Location(null, 128.0D, 64.0D, 128.0D)));
    }

    static TraversalCostRegistration registration(TraversalCostProvider provider, String pluginName) {
        return registration(provider, pluginName, ServicePriority.Normal, true);
    }

    static TraversalCostRegistration registration(TraversalCostProvider provider, String pluginName,
                                                  ServicePriority priority, boolean ownerEnabled) {
        return new TraversalCostRegistration(provider, provider.providerId(), pluginName, priority,
            () -> ownerEnabled);
    }

    static CapturingLogger logger() {
        return new CapturingLogger();
    }

    static final class CapturingLogger {
        private final List<LogRecord> records = new CopyOnWriteArrayList<>();
        private final Logger logger = Logger.getAnonymousLogger();

        CapturingLogger() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
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
        }

        Logger logger() {
            return logger;
        }

        List<String> messages() {
            return records.stream().map(LogRecord::getMessage).toList();
        }

        List<String> messagesAt(Level level) {
            return records.stream().filter(record -> record.getLevel().equals(level)).map(LogRecord::getMessage)
                .toList();
        }
    }

    static final class RecordingEventSink implements TraversalEventSink {
        final List<Event> immediate = new CopyOnWriteArrayList<>();
        final List<Event> entity = new CopyOnWriteArrayList<>();
        Consumer<Event> onImmediate = event -> {
        };

        @Override
        public void fireImmediate(Event event) {
            immediate.add(event);
            onImmediate.accept(event);
        }

        @Override
        public void fireOnEntity(Player traveler, Event event) {
            entity.add(event);
        }
    }

    static final class TestProvider implements TraversalCostProvider {
        final AtomicInteger quoteCalls = new AtomicInteger();
        final AtomicInteger reserveCalls = new AtomicInteger();
        final AtomicInteger commitCalls = new AtomicInteger();
        final List<TraversalRefundReason> refunds = new CopyOnWriteArrayList<>();

        Function<TraversalContext, TraversalQuote> onQuote;
        BiFunction<TraversalContext, TraversalQuote, TraversalReservation> onReserve;
        Consumer<TraversalReceipt> onCommit = receipt -> {
        };
        BiConsumer<TraversalReceipt, TraversalRefundReason> onRefund = (receipt, reason) -> {
        };

        private final String id;

        TestProvider(String id) {
            this.id = id;
            onQuote = context -> TraversalQuote.pass();
            onReserve = (context, quote) -> TraversalReservation.reserved(TraversalReceipt.of(id));
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            quoteCalls.incrementAndGet();
            return onQuote.apply(context);
        }

        @Override
        public TraversalReservation reserve(TraversalContext context, TraversalQuote quote) {
            reserveCalls.incrementAndGet();
            return onReserve.apply(context, quote);
        }

        @Override
        public void commit(TraversalReceipt receipt) {
            commitCalls.incrementAndGet();
            onCommit.accept(receipt);
        }

        @Override
        public void refund(TraversalReceipt receipt, TraversalRefundReason reason) {
            refunds.add(reason);
            onRefund.accept(receipt, reason);
        }

        TestProvider charging(String description) {
            onQuote = context -> TraversalQuote.payable(description);
            return this;
        }
    }
}
