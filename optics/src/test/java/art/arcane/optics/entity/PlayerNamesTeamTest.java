package art.arcane.optics.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PlayerNamesTeamTest {
    @Test
    void namesAreReferenceCountedThroughTheTeamOutput() {
        RecordingEntityOutput output = new RecordingEntityOutput();
        PlayerNames<Object> names = new PlayerNames<>(output, "team");
        names.retain(output, "Alex");
        names.retain(output, "Alex");
        names.retain(output, "Steve");
        names.release(output, "Alex");
        names.release(output, "Alex");
        names.removeTeam(output);
        assertEquals(List.of("CREATE null", "ADD Alex", "ADD Steve", "REMOVE Alex", "REMOVE_TEAM null"), output.teams);
    }
}
