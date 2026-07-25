package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.service.WormholesTelemetry;

public final class ProjectionClaimArbiterBlockMappingFailureTest {
    private final List<LogRecord> records = new ArrayList<LogRecord>();

    private Logger logger;
    private Handler handler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;

    @BeforeEach
    public void setUp() {
        WormholesTelemetry.clear();
        records.clear();
        logger = Logger.getLogger("Wormholes");
        previousLevel = logger.getLevel();
        previousUseParentHandlers = logger.getUseParentHandlers();
        handler = new Handler() {
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
        };
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
    }

    @AfterEach
    public void tearDown() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
        logger.setUseParentHandlers(previousUseParentHandlers);
        WormholesTelemetry.clear();
    }

    @Test
    public void aBlockMappingFailureReachesThePluginLoggerWithItsStackTrace() {
        RuntimeException cause = new IllegalStateException("no palette entry");

        ProjectionClaimArbiter.noteBlockMappingFailure("minecraft:crying_obsidian", cause);

        assertEquals(1, records.size(), "the world lifecycle path must report exactly one log record");
        LogRecord record = records.get(0);
        assertEquals(Level.WARNING, record.getLevel(), "a permanent render degradation must be at least a warning");
        assertSame(cause, record.getThrown(),
            "the throwable must travel with the log record instead of going to printStackTrace");
        assertTrue(record.getMessage().contains("minecraft:crying_obsidian"),
            "the record must name the block state that could not be mapped");
        assertTrue(record.getMessage().contains("falling back"),
            "the record must say what the renderer does now that mapping is off");
    }

    @Test
    public void aBlockMappingFailureIncrementsTheSharedTerminalFailureCounter() {
        ProjectionClaimArbiter.noteBlockMappingFailure("minecraft:stone", new IllegalStateException("boom"));

        assertEquals(1L, WormholesTelemetry.failures(), "a terminal render failure must increment the global counter");
        assertEquals(Map.of(ProjectionClaimArbiter.BLOCK_MAPPING_FAILURE_REASON, Long.valueOf(1L)),
            WormholesTelemetry.failureBreakdown(),
            "the failure must be attributed to a stable, greppable reason");
    }

    @Test
    public void theBlockMappingFailureReasonIsAStableSubsystemPrefixedToken() {
        assertEquals("RENDER_CLAIM_BLOCK_MAPPING_FAILED", ProjectionClaimArbiter.BLOCK_MAPPING_FAILURE_REASON);
    }
}
