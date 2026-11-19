import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Receives connection events and messages and updates our peer state
public class MessageHandler implements ConnectionManager.Handler {

    private static final int CHOKE = 0;
    private static final int UNCHOKE = 1;
    private static final int INTERESTED = 2;
    private static final int NOT_INTERESTED = 3;
    private static final int HAVE = 4;
    private static final int BITFIELD = 5;
    private static final int REQUEST = 6;
    private static final int PIECE = 7;

    private final int localPeerId;
    private final PeerState state;
    private final PeerLogger logger;
    private final PieceManager pieceManager;
    private final CommonConfig config;

    // Set after the manager is created since the manager needs this handler first
    private ConnectionManager manager;

    public MessageHandler(int localPeerId, PeerState state, PeerLogger logger,
            PieceManager pieceManager, CommonConfig config) {
        this.localPeerId = localPeerId;
        this.state = state;
        this.logger = logger;
        this.pieceManager = pieceManager;
        this.config = config;
    }

    public void setConnectionManager(ConnectionManager manager) {
        this.manager = manager;
    }

    @Override
    public void onConnected(int peerId, boolean outgoing) {
        state.registerNeighbor(peerId);

        if (outgoing) {
            logger.logConnectionMade(localPeerId, peerId);
        } else {
            logger.logConnectionReceived(localPeerId, peerId);
        }

        // Peers with no pieces can skip the bitfield message
        if (hasAnyPiece()) {
            send(peerId, BITFIELD, state.getLocalBitfieldPayload());
        }
    }

    @Override
    public void onMessage(int peerId, ProtocolCodec.DecodedMessage message) {
        switch (message.type) {
            case CHOKE:
                state.neighborChokesUs(peerId);
                state.releasePendingFrom(peerId);
                logger.log("Peer " + localPeerId + " is choked by " + peerId + ".");
                break;

            case UNCHOKE:
                state.neighborUnchokesUs(peerId);
                logger.log("Peer " + localPeerId + " is unchoked by " + peerId + ".");
                requestNextPiece(peerId);
                break;

            case INTERESTED:
                state.updateRemoteInterest(peerId, true);
                logger.log("Peer " + localPeerId
                        + " received the 'interested' message from " + peerId + ".");
                break;

            case NOT_INTERESTED:
                state.updateRemoteInterest(peerId, false);
                logger.log("Peer " + localPeerId
                        + " received the 'not interested' message from " + peerId + ".");
                break;

            case HAVE:
                int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
                checkPieceIndex(pieceIndex);
                state.updateNeighborPiece(peerId, pieceIndex, true);
                logger.log("Peer " + localPeerId + " received the 'have' message from "
                        + peerId + " for the piece " + pieceIndex + ".");
                updateInterest(peerId, false);
                requestNextPiece(peerId);
                break;

            case BITFIELD:
                boolean[] bitfield = state.bitfieldFromPayload(message.payload);
                checkSpareBits(message.payload);
                state.updateNeighborBitfield(peerId, bitfield);
                updateInterest(peerId, true);
                break;

            case REQUEST:
                handleRequest(peerId, message);
                break;

            case PIECE:
                handlePiece(peerId, message);
                break;

            default:
                break;
        }
    }

    @Override
    public void onDisconnected(int peerId, Exception error) {
        state.releasePendingFrom(peerId);
        logger.log("Peer " + localPeerId + " disconnected from " + peerId + ".");
    }

    @Override
    public void onConnectionFailed(int peerId, Exception error) {
        logger.log("Peer " + localPeerId + " failed to connect to " + peerId
                + ": " + (error == null ? "unknown error" : error.getMessage()));
    }

    // Sends the piece if the peer is unchoked and we have it.
    private void handleRequest(int peerId, ProtocolCodec.DecodedMessage message) {
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        checkPieceIndex(pieceIndex);

        // Requests from choked peers or for pieces we lack are ignored
        if (state.isChokingPeer(peerId) || !state.getLocalBitfield()[pieceIndex]) {
            return;
        }

        byte[] data;
        try {
            data = pieceManager.readPiece(pieceIndex);
        } catch (IOException e) {
            logger.log("Peer " + localPeerId + " could not read piece "
                    + pieceIndex + ": " + e.getMessage());
            return;
        }

        byte[] payload = ByteBuffer.allocate(4 + data.length)
                .putInt(pieceIndex).put(data).array();
        send(peerId, PIECE, payload);
    }

    // Saves the piece first, then updates state and announces it.
    private void handlePiece(int peerId, ProtocolCodec.DecodedMessage message) {
        int pieceIndex = ByteBuffer.wrap(message.payload).getInt();
        checkPieceIndex(pieceIndex);

        byte[] data = Arrays.copyOfRange(message.payload, 4, message.payload.length);
        if (data.length != pieceManager.getPieceLength(pieceIndex)) {
            throw new IllegalArgumentException(
                    "Wrong length for piece " + pieceIndex);
        }

        // Ignore pieces we did not request from this peer
        Integer requestedFrom = state.getPendingPieces().get(pieceIndex);
        if (requestedFrom == null || requestedFrom != peerId) {
            return;
        }

        // Disk write happens outside the state lock
        try {
            pieceManager.savePiece(pieceIndex, data);
        } catch (IOException e) {
            logger.log("Peer " + localPeerId + " could not save piece "
                    + pieceIndex + ": " + e.getMessage());
            state.releasePendingFrom(peerId);
            return;
        }

        state.completePieceDownload(pieceIndex, peerId, data.length);

        int count = countLocalPieces();
        logger.log("Peer " + localPeerId + " has downloaded the piece " + pieceIndex
                + " from " + peerId + ". Now the number of pieces it has is "
                + count + ".");

        if (count == config.getNumberOfPieces()) {
            logger.log("Peer " + localPeerId + " has downloaded the complete file.");
        }

        // Tell everyone, then drop interest in neighbors we no longer need
        byte[] have = ByteBuffer.allocate(4).putInt(pieceIndex).array();
        for (int id : connectedNeighbors()) {
            send(id, HAVE, have);
            updateInterest(id, false);
        }

        requestNextPiece(peerId);
    }

    // Requests a random needed piece if the peer has unchoked us
    // and we are not already waiting on a piece from it.
    private void requestNextPiece(int peerId) {
        if (state.isChokedByPeer(peerId) || state.getPendingPieces().containsValue(peerId)) {
            return;
        }

        int pieceIndex = state.reserveRandomPiece(peerId);
        if (pieceIndex < 0) {
            return;
        }

        byte[] payload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
        if (!send(peerId, REQUEST, payload)) {
            state.releasePendingFrom(peerId);
        }
    }

    // Recalculates interest and sends interested/not interested to the neighbor.
    // Always sends after a bitfield, otherwise only when interest changed.
    private void updateInterest(int peerId, boolean alwaysSend) {
        boolean before = state.getLocalInterest(peerId);
        boolean now = state.calculateLocalInterest(peerId);

        if (alwaysSend || before != now) {
            send(peerId, now ? INTERESTED : NOT_INTERESTED, new byte[0]);
        }
    }

    // Connected peers whose state is already registered.
    private List<Integer> connectedNeighbors() {
        List<Integer> result = new ArrayList<>();

        for (int id : manager.connectedPeerIds()) {
            if (state.getNeighborBitfield(id) != null) {
                result.add(id);
            }
        }

        return result;
    }

    // Validation checks, a bad message throws and drops the connection.
    private void checkPieceIndex(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= config.getNumberOfPieces()) {
            throw new IllegalArgumentException("Invalid piece index: " + pieceIndex);
        }
    }

    // Spare bits at the end of a bitfield must be zero.
    private void checkSpareBits(byte[] payload) {
        int spareBits = payload.length * 8 - config.getNumberOfPieces();

        if (spareBits > 0 && (payload[payload.length - 1] & ((1 << spareBits) - 1)) != 0) {
            throw new IllegalArgumentException("Bitfield spare bits are not zero");
        }
    }

    private boolean hasAnyPiece() {
        return countLocalPieces() > 0;
    }

    private int countLocalPieces() {
        int count = 0;

        for (boolean piece : state.getLocalBitfield()) {
            if (piece) {
                count++;
            }
        }

        return count;
    }

    private boolean send(int peerId, int type, byte[] payload) {
        try {
            manager.send(peerId, type, payload);
            return true;
        } catch (IOException e) {
            logger.log("Peer " + localPeerId + " could not send to " + peerId
                    + ": " + e.getMessage());
            return false;
        }
    }
}
