package art.arcane.optics.entity;

import art.arcane.optics.math.Face;

public interface MetadataAccess<D> {
    int index(D value);

    Object value(D value);

    D replace(D value, Object replacement);

    D skinParts(int index, byte parts);

    D customName(int index, String name);

    D nameVisible(int index, boolean visible);

    boolean isDirection(Object value);

    boolean isItem(Object value);

    Object direction(Face direction);

    Integer mapId(Object item);

    Object withMapId(Object item, Integer id);
}
