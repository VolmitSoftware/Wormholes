package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import java.io.IOException;
import java.util.logging.Logger;

final class PeerListener {
    static final int LISTEN_PORT_FALLBACK_RANGE = 50;

    private final NetworkManager network;
    private final Logger logger;

    private volatile TcpPeerTransport tcpTransport;
    private volatile UnixDomainPeerTransport udsTransport;
    private volatile Thread tcpAcceptThread;
    private volatile Thread udsAcceptThread;
    private volatile int boundListenPort;

    PeerListener(NetworkManager network, Logger logger, int initialListenPort) {
        this.network = network;
        this.logger = logger;
        this.boundListenPort = initialListenPort;
    }

    int boundPort() {
        return boundListenPort;
    }

    boolean isRawListening() {
        TcpPeerTransport transport = tcpTransport;
        return transport != null && transport.isListening();
    }

    void bind(NetworkConfig active) {
        int fallbackUpperPort = fallbackUpperPort(active.listenPort);
        tcpTransport = bindWithFallback(active.listenPort);
        if (tcpTransport == null) {
            logger.warning("net: could not bind any raw Wormholes port in " + active.listenPort + ".." + fallbackUpperPort + "; running sideband-only over the MC game port " + network.gamePort());
            boundListenPort = 0;
        }

        if (tcpTransport != null && tcpTransport.isListening()) {
            Thread accept = new Thread(this::runTcpAcceptLoop, "Wormholes-Net-Accept-Tcp");
            accept.setDaemon(true);
            tcpAcceptThread = accept;
            accept.start();
        }

        if (active.transport.udsEnabled) {
            try {
                udsTransport = UnixDomainPeerTransport.bind(network.dialer().localSocketPath(active));
                Thread udsAccept = new Thread(this::runUdsAcceptLoop, "Wormholes-Net-Accept-Uds");
                udsAccept.setDaemon(true);
                udsAcceptThread = udsAccept;
                udsAccept.start();
            } catch (IOException | UnsupportedOperationException e) {
                logger.warning("net: failed to bind UDS listener - " + e.getMessage() + "; falling back to TCP for loopback peers");
                udsTransport = null;
            }
        }
    }

    void closeTransports() {
        TcpPeerTransport tcp = tcpTransport;
        tcpTransport = null;
        if (tcp != null) {
            try {
                tcp.close();
            } catch (IOException ignored) {
            }
        }
        UnixDomainPeerTransport uds = udsTransport;
        udsTransport = null;
        if (uds != null) {
            try {
                uds.close();
            } catch (IOException ignored) {
            }
        }
    }

    void clearBindings() {
        tcpAcceptThread = null;
        udsAcceptThread = null;
        boundListenPort = 0;
    }

    static int fallbackUpperPort(int preferredPort) {
        if (preferredPort < NetworkConfig.MIN_LISTEN_PORT || preferredPort > NetworkConfig.MAX_LISTEN_PORT) {
            return preferredPort;
        }
        return (int) Math.min(NetworkConfig.MAX_LISTEN_PORT, (long) preferredPort + LISTEN_PORT_FALLBACK_RANGE);
    }

    private TcpPeerTransport bindWithFallback(int preferredPort) {
        if (preferredPort < NetworkConfig.MIN_LISTEN_PORT || preferredPort > NetworkConfig.MAX_LISTEN_PORT) {
            return null;
        }
        int upper = fallbackUpperPort(preferredPort);
        java.net.BindException firstFailure = null;
        for (int port = preferredPort; port <= upper; port++) {
            try {
                TcpPeerTransport transport = TcpPeerTransport.bind("0.0.0.0", port);
                boundListenPort = port;
                if (port != preferredPort) {
                    logger.info("net: raw Wormholes listen-port " + preferredPort + " was busy; bound " + port + " instead");
                }
                return transport;
            } catch (java.net.BindException be) {
                if (firstFailure == null) {
                    firstFailure = be;
                }
            } catch (IOException e) {
                logger.warning("net: bind " + port + " failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return null;
            }
        }
        return null;
    }

    private void runTcpAcceptLoop() {
        TcpPeerTransport transport = tcpTransport;
        while (network.isRunning() && transport != null && transport.isListening()) {
            try {
                PeerTransport.PeerChannel channel = transport.accept();
                network.acceptInbound(channel);
            } catch (IOException e) {
                if (network.isRunning()) {
                    logger.warning("net: tcp accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void runUdsAcceptLoop() {
        UnixDomainPeerTransport transport = udsTransport;
        while (network.isRunning() && transport != null && transport.isListening()) {
            try {
                PeerTransport.PeerChannel channel = transport.accept();
                network.acceptInbound(channel);
            } catch (IOException e) {
                if (network.isRunning()) {
                    logger.warning("net: uds accept failed: " + e.getMessage());
                }
            }
        }
    }
}
