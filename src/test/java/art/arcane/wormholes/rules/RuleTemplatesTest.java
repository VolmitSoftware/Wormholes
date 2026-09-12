package art.arcane.wormholes.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuleTemplatesTest {
    @TempDir
    Path tempDir;

    private static final UUID KEY_ID = UUID.fromString("6d4d4b0d-0b0a-4f2a-9e8f-1c3b5a7d9e11");

    @Test
    void aDocumentSurvivesTheTomlRoundTrip() throws Exception {
        RuleTemplates templates = new RuleTemplates(tempDir);
        RuleDocument document = new RuleDocument(List.of(
            new Rule("vip", List.of(new Condition.Permission("group.vip"),
                new Condition.EntityClass(Set.of(TravelerClass.PLAYER), false),
                new Condition.KeyItem(KEY_ID)),
                RuleOutcome.allow(),
                List.of(new Cost.Vault(new BigDecimal("12.50")), new Cost.Item(new ItemMatcher("DIAMOND", null, false), 2)),
                List.of(new Effect.Velocity(1.5D), new Effect.Sound("minecraft:block.beacon.activate", 0.5F, 1.25F))),
            new Rule("mobs", List.of(new Condition.EntityClass(Set.of(TravelerClass.MOB), false)),
                RuleOutcome.deny("rules.denied.entity_class"), List.of(), List.of())),
            RuleOutcome.deny("rules.denied.default"),
            new TraversalProfile(5000L, "hub", 3000L, 0.5D, 0.25D, 8, 2), 7L);

        templates.save("hub", document);

        assertEquals(document.withRevision(0L), templates.load("hub").withRevision(0L));
    }

    @Test
    void savedTemplatesAreListedByName() throws Exception {
        RuleTemplates templates = new RuleTemplates(tempDir);
        templates.save("beta", RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny("rules.denied.default")));
        templates.save("alpha", RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny("rules.denied.time")));

        assertEquals(List.of("alpha", "beta"), templates.list());
        assertTrue(Files.exists(tempDir.resolve("rules").resolve("templates").resolve("alpha.toml")));
    }

    @Test
    void aMissingTemplateReadsAsNull() {
        assertNull(new RuleTemplates(tempDir).load("nothing"));
    }

    @Test
    void anInvalidTemplateIsRejectedWithEveryProblem() throws Exception {
        RuleTemplates templates = new RuleTemplates(tempDir);
        templates.save("broken", RuleDocument.EMPTY);
        Path file = tempDir.resolve("rules").resolve("templates").resolve("broken.toml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
            .replace("warmup-millis = 0", "warmup-millis = -1")
            .replace("cooldown-millis = 0", "cooldown-millis = 99999999"), StandardCharsets.UTF_8);

        RuleValidationException failure = assertThrows(RuleValidationException.class, () -> templates.load("broken"));

        assertEquals(2, failure.problems().size());
    }

    @Test
    void templateNamesAreConfinedToTheTemplateFolder() {
        RuleTemplates templates = new RuleTemplates(tempDir);

        assertThrows(IllegalArgumentException.class, () -> templates.save("../escape", RuleDocument.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> templates.load("a/b"));
        assertThrows(IllegalArgumentException.class, () -> templates.save("", RuleDocument.EMPTY));
    }
}
