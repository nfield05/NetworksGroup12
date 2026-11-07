import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Handles TCP connections and normal messages for this peer
public class ConnectionManager implements AutoCloseable {
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int HANDSHAKE_TIMEOUT = 5000;

    private final int localPeerId;
    private final int localRow;
    private final int maxLength;
    private final List<PeerAddress> peers;
    private final Handler handler;

    private final Object lock = new Object();
    private final Map<Integer, Integer> peerRows = new HashMap<>();
    private final Map<Integer, PeerConnection> connections =
            new ConcurrentHashMap<>();
    private final Set<Socket> sockets = new HashSet<>();
    private final ExecutorService workers = Executors.newCachedThreadPool();

    private ServerSocket server;
    private volatile boolean running;
    private boolean started;
    private boolean closed;

    // Stores the address information needed to connect to one peer
    public static class PeerAddress {
        public final int peerId;
        public final String host;
        public final int port;

        public PeerAddress(int peerId, String host, int port) {
            if (peerId <= 0 || host == null || host.isBlank() ||
                    port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid peer address");
            }

            this.peerId = peerId;
            this.host = host;
            this.port = port;
        }
    }

    // Reports connection events and decoded messages to the calling code
    public interface Handler {
        void onConnected(int peerId, boolean outgoing);

        void onMessage(int peerId, ProtocolCodec.DecodedMessage message);

        void onDisconnected(int peerId, Exception error);

        void onConnectionFailed(int peerId, Exception error);
    }

    // Stores the peer list in its supplied configuration order
    public ConnectionManager(int localPeerId, List<PeerAddress> peers,
            int maxLength, Handler handler) {
        if (peers == null || handler == null || maxLength < 1) {
            throw new IllegalArgumentException(
                    "Peers, handler and a positive maximum length are required");
        }

        this.localPeerId = localPeerId;
        this.peers = List.copyOf(peers);
        this.maxLength = maxLength;
        this.handler = handler;

        for (int i = 0; i < this.peers.size(); i++) {
            PeerAddress peer = this.peers.get(i);
            if (peerRows.put(peer.peerId, i) != null) {
                throw new IllegalArgumentException("Duplicate peer ID");
            }
        }

        Integer row = peerRows.get(localPeerId);
        if (row == null) {
            throw new IllegalArgumentException("Local peer is missing");
        }

        this.localRow = row;
    }

    // Starts listening and makes connections to peers in earlier rows
    public void start() throws IOException {
        synchronized (lock) {
            if (started || closed) {
                throw new IllegalStateException("Manager cannot be started");
            }

            server = new ServerSocket(peers.get(localRow).port);
            started = true;
            running = true;
        }

        try {
            synchronized (lock) {
                if (!running) {
                    throw new IOException("Manager closed during startup");
                }

                workers.execute(this::acceptConnections);
            }

            for (int i = 0; i < localRow; i++) {
                Socket socket = new Socket();
                if (!startConnection(socket, peers.get(i))) {
                    socket.close();
                    throw new IOException("Manager closed during startup");
                }
            }
        } catch (IOException | RuntimeException error) {
            try {
                close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }

            throw error;
        }
    }

    // Accepts incoming sockets while the manager is running
    private void acceptConnections() {
        while (running) {
            try {
                Socket socket = server.accept();
                if (!startConnection(socket, null)) {
                    socket.close();
                }
            } catch (IOException | RuntimeException error) {
                if (running) {
                    try {
                        close();
                    } catch (IOException closeError) {
                        error.addSuppressed(closeError);
                    }

                    handler.onConnectionFailed(0, error);
                }

                return;
            }
        }
    }

    // Tracks the socket before starting its connection worker
    private boolean startConnection(Socket socket, PeerAddress destination) {
        synchronized (lock) {
            if (!running) {
                return false;
            }

            sockets.add(socket);
            workers.execute(() -> handleConnection(socket, destination));
            return true;
        }
    }

    // Checks the handshake and reads messages from one connected peer
    private void handleConnection(Socket socket, PeerAddress destination) {
        int peerId = destination == null ? 0 : destination.peerId;
        PeerConnection connection = null;
        boolean registered = false;
        Exception failure = null;

        try (socket) {
            if (destination != null) {
                socket.connect(new InetSocketAddress(
                        destination.host, destination.port), CONNECT_TIMEOUT);
            }

            socket.setSoTimeout(HANDSHAKE_TIMEOUT);

            try (PeerConnection opened = new PeerConnection(socket, maxLength)) {
                connection = opened;

                int receivedId = opened.handshake(localPeerId);
                Integer remoteRow = peerRows.get(receivedId);

                if (remoteRow == null || receivedId == localPeerId) {
                    throw new IOException("Unknown or local peer ID");
                }

                if (destination != null && receivedId != destination.peerId) {
                    throw new IOException("Unexpected peer ID: " + receivedId);
                }

                if (destination == null && remoteRow <= localRow) {
                    throw new IOException("Incoming peer must be in a later row");
                }

                peerId = receivedId;

                // Allows an established peer to remain idle without disconnecting
                socket.setSoTimeout(0);

                synchronized (opened) {
                    synchronized (lock) {
                        if (!running) {
                            return;
                        }

                        if (connections.putIfAbsent(peerId, opened) != null) {
                            throw new IOException("Peer is already connected");
                        }

                        registered = true;
                    }

                    handler.onConnected(peerId, destination != null);
                }

                while (running) {
                    ProtocolCodec.DecodedMessage message = opened.receive();
                    if (message == null) {
                        break;
                    }

                    handler.onMessage(peerId, message);
                }
            }
        } catch (IOException | RuntimeException error) {
            if (running) {
                failure = error;
            }
        } finally {
            if (registered) {
                connections.remove(peerId, connection);
            }

            synchronized (lock) {
                sockets.remove(socket);
            }

            if (registered) {
                handler.onDisconnected(peerId, failure);
            } else if (failure != null) {
                handler.onConnectionFailed(peerId, failure);
            }
        }
    }

    // Sends a normal message to the selected connected peer
    public void send(int peerId, int type, byte[] payload) throws IOException {
        if (!running) {
            throw new IOException("Manager is not running");
        }

        PeerConnection connection = connections.get(peerId);
        if (connection == null) {
            throw new IOException("Peer is not connected: " + peerId);
        }

        try {
            synchronized (connection) {
                connection.send(type, payload);
            }
        } catch (IOException error) {
            try {
                connection.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }

            throw error;
        }
    }

    // Returns the IDs of the currently registered connections
    public Set<Integer> connectedPeerIds() {
        return Set.copyOf(connections.keySet());
    }

    // Stops new work and closes listening, connecting and connected sockets
    @Override
    public void close() throws IOException {
        ServerSocket listener;
        List<Socket> openSockets;

        synchronized (lock) {
            if (closed) {
                return;
            }

            closed = true;
            running = false;
            listener = server;
            openSockets = new ArrayList<>(sockets);
            connections.clear();
            workers.shutdown();
        }

        IOException failure = null;

        if (listener != null) {
            try {
                listener.close();
            } catch (IOException error) {
                failure = error;
            }
        }

        for (Socket socket : openSockets) {
            try {
                socket.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    // Waits for the network workers to finish after close is called
    public boolean awaitTermination(long timeoutMillis)
            throws InterruptedException {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }

        return workers.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}