package art.arcane.optics.stream;

import java.util.List;
import java.util.UUID;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;

public interface ViewStreamEndpoints<O, B> {
    void interested(O observer, List<UUID> out);

    long geometryRevision(O observer, UUID portal);

    ApertureDescriptor geometry(O observer, UUID portal, SessionPalette palette);

    ViewPlate<B> plate(O observer, UUID portal, boolean firstAttendance);

    default int meshDistanceBlocks(O observer) {
        return 0;
    }

    default Vec3d meshEye(O observer) {
        return null;
    }

    default void prepareNested(O observer, UUID context, UUID parentContext, UUID portal) {
    }

    default void releaseNested(O observer, UUID context) {
    }

    default Vec3d nestedEye(O observer, UUID context) {
        return null;
    }

    default WorldChangeTracker meshChanges(O observer) {
        return null;
    }

    default boolean localMeshWorld(O observer, UUID context) {
        return false;
    }

    default ViewPlate<B> meshSection(O observer, UUID portal, BlockBox clip, int distance) {
        return null;
    }

    default ViewPlate<B> nestedMeshSection(O observer, UUID parent, UUID child, BlockBox clip, int distance) {
        return null;
    }

    default boolean meshSectionQueued(O observer, UUID portal, BlockBox clip) {
        return false;
    }

    default boolean nestedMeshSectionQueued(O observer, UUID parent, UUID child, BlockBox clip) {
        return meshSectionQueued(observer, child, clip);
    }

    default SectionBiomes meshBiomes(O observer, UUID portal, ViewPlate<B> plate) {
        return SectionBiomes.NONE;
    }

    boolean refused(O observer, UUID portal);

    ViewPlate<B> standbyPlate(O observer, UUID portal);

    BrickLightSource lightBaseline(O observer, UUID portal, ViewPlate<B> plate);

    void releaseVanilla(O observer, UUID portal);

    void nested(O observer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out);

    long nestedGeometryRevision(O observer, UUID parent, UUID child);

    ApertureDescriptor nestedGeometry(O observer, UUID parent, UUID child, SessionPalette palette);

    ViewPlate<B> nestedPlate(O observer, UUID parent, UUID child);

    void effects(O observer, List<UUID> out);

    long effectGeometryRevision(O observer, UUID portal);

    ApertureDescriptor effectGeometry(O observer, UUID portal, SessionPalette palette);
}
