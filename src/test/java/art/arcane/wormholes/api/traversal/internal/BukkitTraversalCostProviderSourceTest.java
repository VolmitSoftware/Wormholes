package art.arcane.wormholes.api.traversal.internal;

import art.arcane.wormholes.api.traversal.TraversalContext;
import art.arcane.wormholes.api.traversal.TraversalCostProvider;
import art.arcane.wormholes.api.traversal.TraversalQuote;
import art.arcane.wormholes.api.traversal.internal.TraversalCostTestSupport.CapturingLogger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitTraversalCostProviderSourceTest {
    private final CapturingLogger log = TraversalCostTestSupport.logger();
    private final BukkitTraversalCostProviderSource source = new BukkitTraversalCostProviderSource(log.logger());

    @Test
    void aLambdaProviderThatNeverOverridesProviderIdIsWarnedAboutOnceNamingItsPlugin() {
        TraversalCostProvider veto = context -> TraversalQuote.pass();
        TraversalCostProvider other = context -> TraversalQuote.denied("no");

        assertEquals(veto.getClass().getName(), source.resolveProviderId(veto, "ClaimsPlugin"));
        assertEquals(veto.getClass().getName(), source.resolveProviderId(veto, "ClaimsPlugin"));
        assertEquals(other.getClass().getName(), source.resolveProviderId(other, "ClaimsPlugin"));

        List<String> warnings = generatedIdWarnings();
        assertEquals(1, warnings.size(),
            "one warning per plugin per run; a rebuild loop must not grow a set or spam the console");
        assertTrue(warnings.get(0).contains("ClaimsPlugin"));
        assertTrue(warnings.get(0).contains("Override providerId()"));
    }

    @Test
    void aProviderThatOverridesProviderIdIsNeverWarnedAbout() {
        assertEquals("example-mana", source.resolveProviderId(new NamedProvider(), "ManaPlugin"));

        assertTrue(generatedIdWarnings().isEmpty());
    }

    @Test
    void aBlankOrThrowingProviderIdDropsTheRegistrationWithAWarning() {
        assertNull(source.resolveProviderId(new BlankProvider(), "BlankPlugin"));
        assertNull(source.resolveProviderId(new ThrowingProvider(), "BrokenPlugin"));

        assertTrue(log.messages().stream().anyMatch(message -> message.contains("BlankPlugin")
            && message.contains("blank value")));
        assertTrue(log.messages().stream().anyMatch(message -> message.contains("BrokenPlugin")
            && message.contains("providerId() threw")));
    }

    private List<String> generatedIdWarnings() {
        return log.messages().stream().filter(message -> message.contains("did not override providerId()")).toList();
    }

    private static final class NamedProvider implements TraversalCostProvider {
        @Override
        public String providerId() {
            return "example-mana";
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.pass();
        }
    }

    private static final class BlankProvider implements TraversalCostProvider {
        @Override
        public String providerId() {
            return "   ";
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.pass();
        }
    }

    private static final class ThrowingProvider implements TraversalCostProvider {
        @Override
        public String providerId() {
            throw new IllegalStateException("boom");
        }

        @Override
        public TraversalQuote quote(TraversalContext context) {
            return TraversalQuote.pass();
        }
    }
}
