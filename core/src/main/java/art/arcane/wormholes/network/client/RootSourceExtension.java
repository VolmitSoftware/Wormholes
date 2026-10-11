package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class RootSourceExtension implements ViewStreamExtension<RootSource> {
    public static final RootSourceExtension INSTANCE = new RootSourceExtension();
    public static final int ID = 64;

    private RootSourceExtension() {
    }

    @Override
    public int firstId() {
        return ID;
    }

    @Override
    public int lastId() {
        return ID;
    }

    @Override
    public boolean serverbound(int id) {
        return false;
    }

    @Override
    public boolean clientbound(int id) {
        return id == ID;
    }

    @Override
    public Class<RootSource> type() {
        return RootSource.class;
    }

    @Override
    public String name(int id) {
        return "ROOT_SOURCE";
    }

    @Override
    public int id(RootSource message) {
        return ID;
    }

    @Override
    public void encode(RootSource message, ViewStreamWriter out) throws ViewStreamProtocolException {
        out.i32(message.portalKey());
        out.i32(message.geometryRevision());
        out.string(message.sourceWorld());
    }

    @Override
    public RootSource decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            return new RootSource(in.i32(), in.i32(), in.string());
        } catch (IllegalArgumentException failure) {
            throw new ViewStreamProtocolException("Invalid portal root source", failure);
        }
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.ROOT_SOURCE;
    }
}
