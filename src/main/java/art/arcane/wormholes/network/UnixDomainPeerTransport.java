package art.arcane.wormholes.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class UnixDomainPeerTransport implements PeerTransport {
    private static final Set<PosixFilePermission> SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int LIVE_SOCKET_PROBE_TIMEOUT_MILLIS = 250;

    private final ServerSocketChannel serverChannel;
    private final UnixDomainSocketAddress socketAddress;
    private final Path socketPath;
    private final Object socketFileKey;

    private UnixDomainPeerTransport(ServerSocketChannel serverChannel, UnixDomainSocketAddress socketAddress, Path socketPath, Object socketFileKey) {
        this.serverChannel = serverChannel;
        this.socketAddress = socketAddress;
        this.socketPath = socketPath;
        this.socketFileKey = socketFileKey;
    }

    public static UnixDomainPeerTransport bind(Path socketPath) throws IOException {
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        prepareSocketPath(socketPath, address);
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(address);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        Object socketFileKey;
        try {
            try {
                Files.setPosixFilePermissions(socketPath, SOCKET_PERMISSIONS);
            } catch (UnsupportedOperationException ignored) {
            }
            socketFileKey = socketFileIdentity(socketPath);
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        return new UnixDomainPeerTransport(channel, address, socketPath, socketFileKey);
    }

    public static PeerChannel dial(Path socketPath) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            connect(channel, address, CONNECT_TIMEOUT_MILLIS);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new UnixDomainPeerChannel(channel, address);
    }

    public static Path defaultServerSocketPath(Path dataDirectory, String localPeerId) {
        return dataDirectory.resolve("uds").resolve("peer-" + sanitize(localPeerId) + ".sock");
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.';
            builder.append(safe ? ch : '_');
        }
        return builder.toString();
    }

    public Path socketPath() {
        return socketPath;
    }

    @Override
    public String name() {
        return "unix";
    }

    @Override
    public boolean isListening() {
        return serverChannel != null && serverChannel.isOpen();
    }

    @Override
    public boolean isLoopback() {
        return true;
    }

    @Override
    public SocketAddress localAddress() {
        return socketAddress;
    }

    @Override
    public PeerChannel accept() throws IOException {
        if (serverChannel == null) {
            throw new IOException("uds transport is outbound-only");
        }
        SocketChannel client = serverChannel.accept();
        return new UnixDomainPeerChannel(client, (UnixDomainSocketAddress) client.getRemoteAddress());
    }

    @Override
    public PeerChannel connect(SocketAddress remote, int timeoutMillis) throws IOException {
        if (!(remote instanceof UnixDomainSocketAddress unixRemote)) {
            throw new IOException("uds transport requires UnixDomainSocketAddress, got " + (remote == null ? "null" : remote.getClass().getName()));
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            connect(channel, unixRemote, timeoutMillis);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new UnixDomainPeerChannel(channel, unixRemote);
    }

    @Override
    public void close() throws IOException {
        IOException firstError = null;
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                firstError = e;
            }
        }
        if (socketPath != null) {
            try {
                Object currentFileKey = Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)
                    ? socketFileIdentity(socketPath)
                    : null;
                if (socketFileKey != null && Objects.equals(socketFileKey, currentFileKey)) {
                    Files.deleteIfExists(socketPath);
                }
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private static void prepareSocketPath(Path socketPath, UnixDomainSocketAddress address) throws IOException {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isRegularFile(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(socketPath);
            return;
        }
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            connect(probe, address, LIVE_SOCKET_PROBE_TIMEOUT_MILLIS);
            throw new BindException("UNIX domain socket is already in use: " + socketPath);
        } catch (ConnectException e) {
            Files.delete(socketPath);
        }
    }

    private static Object socketFileIdentity(Path socketPath) throws IOException {
        Object fileKey = Files.readAttributes(socketPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (fileKey != null) {
            return fileKey;
        }
        try {
            return Files.getAttribute(socketPath, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static void connect(SocketChannel channel, UnixDomainSocketAddress address, int timeoutMillis) throws IOException {
        channel.configureBlocking(false);
        if (channel.connect(address)) {
            return;
        }
        try (Selector selector = Selector.open()) {
            channel.register(selector, SelectionKey.OP_CONNECT);
            int selected = selector.select(Math.max(1, timeoutMillis));
            if (selected == 0) {
                throw new SocketTimeoutException("Timed out connecting to " + address.getPath());
            }
            if (!channel.finishConnect()) {
                throw new ConnectException("Could not connect to " + address.getPath());
            }
        }
    }

    private static final class UnixDomainPeerChannel implements PeerChannel {
        private final SocketChannel channel;
        private final UnixDomainSocketAddress remoteAddress;
        private final Selector readSelector;
        private final Selector writeSelector;
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private volatile int readTimeoutMillis;

        private UnixDomainPeerChannel(SocketChannel channel, UnixDomainSocketAddress remoteAddress) throws IOException {
            this.channel = channel;
            this.remoteAddress = remoteAddress;
            this.channel.configureBlocking(false);
            this.readSelector = Selector.open();
            this.writeSelector = Selector.open();
            this.channel.register(readSelector, SelectionKey.OP_READ);
            this.channel.register(writeSelector, SelectionKey.OP_WRITE);
            this.inputStream = new ChannelInputStream();
            this.outputStream = new ChannelOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void setReadTimeout(int millis) {
            readTimeoutMillis = Math.max(0, millis);
        }

        @Override
        public void setTcpNoDelay(boolean noDelay) {
        }

        @Override
        public String describeRemote() {
            if (remoteAddress == null || remoteAddress.getPath() == null) {
                return "uds:?";
            }
            return "uds:" + remoteAddress.getPath();
        }

        @Override
        public SocketAddress remoteAddress() {
            return remoteAddress;
        }

        @Override
        public boolean isLoopback() {
            return true;
        }

        @Override
        public void close() throws IOException {
            IOException firstError = null;
            readSelector.wakeup();
            writeSelector.wakeup();
            try {
                channel.close();
            } catch (IOException e) {
                firstError = e;
            }
            try {
                readSelector.close();
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
            try {
                writeSelector.close();
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
            if (firstError != null) {
                throw firstError;
            }
        }

        private boolean awaitReady(Selector selector, int timeoutMillis) throws IOException {
            if (timeoutMillis <= 0) {
                while (selector.isOpen()) {
                    selector.selectedKeys().clear();
                    if (selector.select() > 0) {
                        selector.selectedKeys().clear();
                        return true;
                    }
                }
                return false;
            }
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (selector.isOpen()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                selector.selectedKeys().clear();
                if (selector.select(remainingMillis) > 0) {
                    selector.selectedKeys().clear();
                    return true;
                }
            }
            return false;
        }

        private final class ChannelInputStream extends InputStream {
            private final byte[] singleByte = new byte[1];

            @Override
            public int read() throws IOException {
                int read = read(singleByte, 0, 1);
                return read < 0 ? -1 : singleByte[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) {
                    return 0;
                }
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, length);
                while (true) {
                    int read = channel.read(byteBuffer);
                    if (read != 0) {
                        return read;
                    }
                    int timeoutMillis = readTimeoutMillis;
                    if (!awaitReady(readSelector, timeoutMillis)) {
                        throw new SocketTimeoutException("Timed out reading from " + describeRemote());
                    }
                }
            }

            @Override
            public int available() {
                return 0;
            }

            @Override
            public void close() throws IOException {
                UnixDomainPeerChannel.this.close();
            }
        }

        private final class ChannelOutputStream extends OutputStream {
            private final byte[] singleByte = new byte[1];

            @Override
            public void write(int value) throws IOException {
                singleByte[0] = (byte) value;
                write(singleByte, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, offset, length);
                while (byteBuffer.hasRemaining()) {
                    if (channel.write(byteBuffer) == 0) {
                        awaitReady(writeSelector, 0);
                    }
                }
            }

            @Override
            public void close() throws IOException {
                UnixDomainPeerChannel.this.close();
            }
        }
    }
}
