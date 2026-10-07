package art.arcane.optics.stream;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.entity.ProjectedEntityEvent;
import art.arcane.optics.entity.EntityProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshLocalEntityIdentityTest {
    @Test
    void realLocalClaimsSuppressOpaqueVisualsAndForcePresenceToRetireExistingCopies() throws Exception {
        Fixture fixture = new Fixture();
        ViewStreamMessage.EntityFrame initial = fixture.lastFrame();
        assertEquals(List.of(fixture.localOpaque, fixture.remoteOpaque), initial.presentIds());
        assertEquals(2, initial.entities().size());
        int frames = fixture.harness.sent(ViewStreamMessageType.ENTITY_FRAME);
        fixture.harness.tick();
        assertEquals(frames, fixture.harness.sent(ViewStreamMessageType.ENTITY_FRAME));
        fixture.cover(1, true);
        fixture.harness.tick();
        ViewStreamMessage.EntityFrame filtered = fixture.lastFrame();
        assertTrue(fixture.harness.sent(ViewStreamMessageType.ENTITY_FRAME) > frames);
        assertTrue(filtered.presence());
        assertEquals(List.of(fixture.remoteOpaque), filtered.presentIds());
        assertEquals(List.of(fixture.remoteOpaque), filtered.entities().stream().map(EntitySnapshot::id).toList());
        assertFalse(filtered.presentIds().contains(fixture.localOpaque));
        fixture.cover(2, false);
        fixture.harness.tick();
        ViewStreamMessage.EntityFrame resumed = fixture.lastFrame();
        assertTrue(resumed.presence());
        assertEquals(List.of(fixture.localOpaque, fixture.remoteOpaque), resumed.presentIds());
        assertTrue(resumed.entities().stream().allMatch(EntitySnapshot::isFull));
        assertTrue(fixture.harness.warnings.isEmpty(), fixture.harness.warnings.toString());
    }

    @Test
    void eventsUseTheSameOpaqueCoverageAndResumeAfterRetraction() throws Exception {
        Fixture fixture = new Fixture();
        fixture.cover(1, true);
        fixture.harness.tick();
        int first = fixture.harness.client.received.size();
        fixture.source.event(ProjectedEntityEvent.animation(fixture.localReal, 3));
        fixture.source.event(ProjectedEntityEvent.animation(fixture.remoteReal, 3));
        fixture.harness.tick();
        assertEquals(List.of(fixture.remoteOpaque), events(fixture.harness, first));
        fixture.cover(2, false);
        fixture.harness.tick();
        int resumed = fixture.harness.client.received.size();
        fixture.source.event(ProjectedEntityEvent.hurt(fixture.localReal, 45));
        fixture.harness.tick();
        assertEquals(List.of(fixture.localOpaque), events(fixture.harness, resumed));
    }

    private static List<UUID> events(SessionHarness harness, int start) {
        List<UUID> result = new ArrayList<>();
        for (int index = start; index < harness.client.received.size(); index++) {
            if (harness.client.received.get(index) instanceof ViewStreamMessage.EntityEvent event) {
                result.add(event.entityId());
            }
        }
        return result;
    }

    private static EntitySnapshot visual(UUID id) {
        return new EntitySnapshot(EntitySnapshot.MODE_FULL, 0, EntitySnapshot.FIELD_ALL_FULL, id, "minecraft:armor_stand", 11, 67, 20,
            1.975, 0, 0, -1, 180, 0, 0, 0, 0, true, "", "", "", null, null,
            EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
    }

    private static final class Fixture {
        private final SessionHarness harness = new SessionHarness(SessionHarness.options(true, 32));
        private final UUID localReal = UUID.randomUUID();
        private final UUID remoteReal = UUID.randomUUID();
        private final UUID localOpaque = EntityProjection.opaque(123, localReal);
        private final UUID remoteOpaque = EntityProjection.opaque(123, remoteReal);
        private final EntityFrames<String> source;
        private final ViewStreamMessage.MeshBegin begin;

        private Fixture() throws Exception {
            List<EntitySnapshot> visuals = List.of(visual(localOpaque), visual(remoteOpaque));
            source = new EntityFrames<>(new EntityFrames.Scenes<String>() {
                @Override
                public Object sceneKey(String observer, UUID portal) {
                    return portal;
                }

                @Override
                public List<EntitySnapshot> capture(String observer, UUID portal, long tick) {
                    return visuals;
                }

                @Override
                public UUID projectedId(UUID sourceId) {
                    return EntityProjection.opaque(123, sourceId);
                }
            });
            harness.entities = source;
            harness.access.localWorld = true;
            harness.access.meshDistance = 32;
            SessionPortal portal = harness.access.add(new SessionPortal("local-opaque-entities", 0));
            portal.mirror = true;
            portal.plate = portal.build(new SessionWorld(1));
            harness.handshake(SessionHarness.NATIVE_CAPS);
            harness.tick();
            begin = (ViewStreamMessage.MeshBegin) harness.last(ViewStreamMessageType.MESH_BEGIN);
            assertEquals(localOpaque, source.projectedId(localReal));
        }

        private void cover(int sequence, boolean available) throws Exception {
            assertEquals(ViewStreamInbound.HANDLED, harness.c2s(ViewStreamFixtures.CODEC.encodeC2S(
                new ViewStreamMessage.MeshLocal(begin.portalKey(), begin.generation(), sequence, available, List.of(), List.of(localReal)))));
        }

        private ViewStreamMessage.EntityFrame lastFrame() {
            return (ViewStreamMessage.EntityFrame) harness.last(ViewStreamMessageType.ENTITY_FRAME);
        }
    }
}
