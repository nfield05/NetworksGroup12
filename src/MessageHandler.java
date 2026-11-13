import java.io.IOException;
import java.nio.ByteBuffer;

// Receives connection events and messages and updates our peer state
public class MessageHandler implements ConnectionManager.Handler {

    private static final int CHOKE = 0;
    private static final int UNCHOKE = 1;
    private static final int INTERESTED = 2;
    private static final int NOT_INTERESTED = 3;
    private static final int HAVE = 4;
    private static final int BITFIELD = 5;

    private final int localPeerId;
    private final PeerState state;
    private final PeerLogger logger;

    // Set after the manager is created since the manager needs this handler first
    private ConnectionManager manager;

    public MessageHandler(int localPeerId, PeerState state, PeerLogger logger) {
        this.localPeerId = localPeerId;
        this.state = state;
        this.logger = logger;
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
                logger.log("Peer " + localPeerId + " is choked by " + peerId + ".");
                break;

            case UNCHOKE:
                state.neighborUnchokesUs(peerId);
                logger.log("Peer " + localPeerId + " is unchoked by " + peerId + ".");
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
                state.updateNeighborPiece(peerId, pieceIndex, true);
                logger.log("Peer " + localPeerId + " received the 'have' message from "
                        + peerId + " for the piece " + pieceIndex + ".");
                updateInterest(peerId, false);
                break;

            case BITFIELD:
                state.updateNeighborBitfield(
                        peerId, state.bitfieldFromPayload(message.payload));
                updateInterest(peerId, true);
                break;

            default:
                // request and piece messages are not handled yet
                break;
        }
    }

    @Override
    public void onDisconnected(int peerId, Exception error) {
        logger.log("Peer " + localPeerId + " disconnected from " + peerId + ".");
    }

    @Override
    public void onConnectionFailed(int peerId, Exception error) {
        logger.log("Peer " + localPeerId + " failed to connect to " + peerId
                + ": " + (error == null ? "unknown error" : error.getMessage()));
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

    private boolean hasAnyPiece() {
        for (boolean piece : state.getLocalBitfield()) {
            if (piece) {
                return true;
            }
        }

        return false;
    }

    private void send(int peerId, int type, byte[] payload) {
        try {
            manager.send(peerId, type, payload);
        } catch (IOException e) {
            logger.log("Peer " + localPeerId + " could not send to " + peerId
                    + ": " + e.getMessage());
        }
    }
}
