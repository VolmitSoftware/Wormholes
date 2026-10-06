package art.arcane.optics.entity;

import java.util.ArrayList;
import java.util.List;

public final class ProjectedMetadata<D> {
    private static final int CUSTOM_NAME_INDEX = 2;
    private static final int CUSTOM_NAME_VISIBLE_INDEX = 3;
    private static final int PLAYER_SKIN_PARTS_INDEX = 16;
    private static final byte CAPE_PART_BIT = 0x01;
    private final MetadataAccess<D> access;

    public ProjectedMetadata(MetadataAccess<D> access) {
        this.access = access;
    }

    public List<D> upsideDownPlayer(List<D> metadata) {
        List<D> patched = new ArrayList<>(metadata.size() + 1);
        byte skinParts = 0;
        for (D data : metadata) {
            if (access.index(data) == PLAYER_SKIN_PARTS_INDEX && access.value(data) instanceof Byte value) {
                skinParts = value.byteValue();
                continue;
            }
            patched.add(data);
        }
        patched.add(access.skinParts(PLAYER_SKIN_PARTS_INDEX, (byte) (skinParts | CAPE_PART_BIT)));
        return patched;
    }

    public List<D> upsideDownEntity(List<D> metadata, boolean alreadyFlipped) {
        List<D> patched = new ArrayList<>(metadata.size() + 2);
        for (D data : metadata) {
            if (access.index(data) != CUSTOM_NAME_INDEX && access.index(data) != CUSTOM_NAME_VISIBLE_INDEX) {
                patched.add(data);
            }
        }
        if (!alreadyFlipped) {
            patched.add(access.customName(CUSTOM_NAME_INDEX, PlayerNames.FLIP_NAME));
            patched.add(access.nameVisible(CUSTOM_NAME_VISIBLE_INDEX, false));
        }
        return patched;
    }

    public String signature(List<D> metadata) {
        StringBuilder builder = new StringBuilder(metadata.size() * 8);
        for (D data : metadata) {
            builder.append(access.index(data)).append('=').append(String.valueOf(access.value(data))).append(';');
        }
        return builder.toString();
    }
}
