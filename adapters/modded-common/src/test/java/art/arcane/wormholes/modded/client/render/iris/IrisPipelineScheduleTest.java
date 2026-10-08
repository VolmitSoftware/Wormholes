package art.arcane.wormholes.modded.client.render.iris;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class IrisPipelineScheduleTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    @Test
    public void currentDimensionComesFirstThenOverworldNetherEnd() {
        assertEquals(List.of(NETHER, OVERWORLD, END), IrisPipelineSchedule.order(NETHER, List.of(END, NETHER, OVERWORLD)));
        assertEquals(List.of(OVERWORLD, NETHER, END), IrisPipelineSchedule.order(OVERWORLD, List.of(END, OVERWORLD, NETHER)));
    }

    @Test
    public void otherDimensionsFollowInNameOrderWithoutDuplicates() {
        List<String> order = IrisPipelineSchedule.order("custom:mines", List.of("custom:sky", END, "custom:mines", OVERWORLD,
            "aether:the_aether", OVERWORLD, "custom:sky"));

        assertEquals(List.of("custom:mines", OVERWORLD, END, "aether:the_aether", "custom:sky"), order);
    }

    @Test
    public void currentDimensionIsScheduledEvenWhenTheServerListDoesNotNameIt() {
        assertEquals(List.of(END, OVERWORLD), IrisPipelineSchedule.order(END, List.of(OVERWORLD)));
    }

    @Test
    public void createdAndFailedPipelinesAreNotScheduledAgain() {
        List<String> order = IrisPipelineSchedule.order(OVERWORLD, List.of(OVERWORLD, NETHER, END, "custom:sky"));

        assertEquals(List.of(NETHER, "custom:sky"), IrisPipelineSchedule.pending(order, Set.of(OVERWORLD), Set.of(END)));
        assertEquals(List.of(), IrisPipelineSchedule.pending(order, Set.of(OVERWORLD, NETHER, "custom:sky"), Set.of(END)));
    }
}
