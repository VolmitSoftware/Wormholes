package art.arcane.wormholes.network.client;

import art.arcane.optics.stream.ViewStreamExtension;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.ViewStreamReader;
import art.arcane.optics.stream.ViewStreamWriter;

public final class LocalDestinationExtension implements ViewStreamExtension<LocalDestination> {
    public static final LocalDestinationExtension INSTANCE = new LocalDestinationExtension();
    public static final int ID = 65;

    private LocalDestinationExtension() {
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
    public Class<LocalDestination> type() {
        return LocalDestination.class;
    }

    @Override
    public String name(int id) {
        return "LOCAL_DESTINATION";
    }

    @Override
    public int id(LocalDestination message) {
        return ID;
    }

    @Override
    public void encode(LocalDestination message, ViewStreamWriter out) throws ViewStreamProtocolException {
        out.i32(message.portalKey());
        out.i32(message.geometryRevision());
        out.string(message.destinationWorld());
    }

    @Override
    public LocalDestination decode(int id, ViewStreamReader in) throws ViewStreamProtocolException {
        try {
            return new LocalDestination(in.i32(), in.i32(), in.string());
        } catch (IllegalArgumentException failure) {
            throw new ViewStreamProtocolException("Invalid portal local destination", failure);
        }
    }

    @Override
    public long capabilities() {
        return ClientViewExtensions.LOCAL_DESTINATION;
    }
}
