package art.arcane.optics.entity;

import java.util.ArrayList;
import java.util.List;

public final class ItemFrameMetadata<D> {
    private static final int DIRECTION_INDEX = 8;
    private static final int ITEM_INDEX = 9;
    private static final int ROTATION_INDEX = 10;
    private final MetadataAccess<D> access;

    public ItemFrameMetadata(MetadataAccess<D> access) {
        this.access = access;
    }

    public List<D> transformMetadata(List<D> metadata,
                                                  int transform,
                                                  Integer projectedMapId,
                                                  boolean stripMapId) {
        if ((transform == ItemFrameTransform.NONE && projectedMapId == null && !stripMapId) || metadata == null || metadata.isEmpty()) {
            return metadata;
        }
        boolean filledMap = !stripMapId && mapId(metadata) != null;
        ArrayList<D> patched = new ArrayList<D>(metadata.size());
        Object targetFace = transform == ItemFrameTransform.NONE ? null : access.direction(ItemFrameTransform.targetFacing(transform));
        for (D data : metadata) {
            if (transform != ItemFrameTransform.NONE && access.index(data) == DIRECTION_INDEX && access.isDirection(access.value(data))) {
                patched.add(access.replace(data, targetFace));
                continue;
            }
            if (access.index(data) == ITEM_INDEX && access.isItem(access.value(data))) {
                Object item = access.value(data);
                if (access.mapId(item) != null && (projectedMapId != null || stripMapId)) {
                    Object projectedItem = access.withMapId(item, stripMapId ? null : projectedMapId);
                    patched.add(access.replace(data, projectedItem));
                    continue;
                }
            }
            if (transform != ItemFrameTransform.NONE && access.index(data) == ROTATION_INDEX && access.value(data) instanceof Integer) {
                int sourceRotation = ((Integer) access.value(data)).intValue();
                patched.add(access.replace(data,
                    Integer.valueOf(ItemFrameTransform.transformRotation(transform, sourceRotation, filledMap))));
                continue;
            }
            patched.add(data);
        }
        return patched;
    }

    public Integer mapId(List<D> metadata) {
        if (metadata == null) {
            return null;
        }
        for (D data : metadata) {
            if (access.index(data) != ITEM_INDEX || !access.isItem(access.value(data))) {
                continue;
            }
            Object item = access.value(data);
            return access.mapId(item);
        }
        return null;
    }
}
