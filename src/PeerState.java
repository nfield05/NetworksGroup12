import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PeerState {

    // Pieces this peer has successfully downloaded and saved.
    private boolean[] localBitfield;

    // Maps each neighbor's peer ID to the pieces that neighbor has.
    private Map<Integer, boolean[]> neighborBitfields;

    // Tracks neighbors before their bitfields arrive.
    private Set<Integer> registeredNeighbors;

    // Whether this peer is interested in obtaining pieces from each neighbor.
    private Map<Integer, Boolean> localInterest;

    // Whether each neighbor is interested in obtaining pieces from this peer.
    private Map<Integer, Boolean> remoteInterest;

    // Whether each neighbor is choking us.
    private Map<Integer, Boolean> remoteChoke;

    // Whether we are choking each neighbor.
    private Map<Integer, Boolean> localChoke;

    // Maps each pending piece index to the peer it was requested from.
    private Map<Integer, Integer> pendingPieces;

    // Bytes received from each neighbor during the current unchoking interval.
    private Map<Integer, Long> bytesReceivedThisInterval;

    public PeerState(int numberOfPieces) {
        if (numberOfPieces <= 0) {
            throw new IllegalArgumentException(
                "Number of pieces must be positive."
            );
        }

        localBitfield = new boolean[numberOfPieces];

        neighborBitfields = new HashMap<>();
        registeredNeighbors = new HashSet<>();
        localInterest = new HashMap<>();
        remoteInterest = new HashMap<>();
        localChoke = new HashMap<>();
        remoteChoke = new HashMap<>();
        pendingPieces = new HashMap<>();
        bytesReceivedThisInterval = new HashMap<>();
    }




    // Initialize our bitfield based on whether we already have the file.
    public synchronized void initializeLocalBitfield(boolean hasFile) {
        for (int i = 0; i < localBitfield.length; i++) {
            localBitfield[i] = hasFile;
        }
    }

    // Update whether we have a particular piece.
    public synchronized void updateLocalBitfield(int pieceIndex, boolean hasPiece) {
        if (pieceIndex < 0 || pieceIndex >= localBitfield.length) {
            throw new IllegalArgumentException("Invalid piece index.");
        }

        localBitfield[pieceIndex] = hasPiece;
    }

    // Pack our bitfield into bytes, piece 0 is the high bit of byte 0.
    public synchronized byte[] getLocalBitfieldPayload() {
        byte[] payload = new byte[(localBitfield.length + 7) / 8];

        for (int i = 0; i < localBitfield.length; i++) {
            if (localBitfield[i]) {
                payload[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }

        return payload;
    }

    // Turn a received BITFIELD payload into one boolean per piece.
    public synchronized boolean[] bitfieldFromPayload(byte[] payload) {
        if (payload == null || payload.length != (localBitfield.length + 7) / 8) {
            throw new IllegalArgumentException("Invalid bitfield payload.");
        }

        boolean[] bitfield = new boolean[localBitfield.length];

        for (int i = 0; i < bitfield.length; i++) {
            bitfield[i] = (payload[i / 8] & (0x80 >> (i % 8))) != 0;
        }

        return bitfield;
    }

    // Initialize state for a neighbor before their bitfield arrives.
    public synchronized void registerNeighbor(int peerId) {
        registeredNeighbors.add(peerId);
        neighborBitfields.putIfAbsent(peerId, new boolean[localBitfield.length]);
        localInterest.putIfAbsent(peerId, false);
        remoteInterest.putIfAbsent(peerId, false);
        localChoke.putIfAbsent(peerId, true);
        remoteChoke.putIfAbsent(peerId, true);
        bytesReceivedThisInterval.putIfAbsent(peerId, 0L);
    }

    // Store a neighbor's bitfield when their BITFIELD message arrives.
    public synchronized void updateNeighborBitfield(int peerId, boolean[] bitfield) {
        if (bitfield == null || bitfield.length != localBitfield.length) {
            throw new IllegalArgumentException("Invalid neighbor bitfield.");
        }

        registerNeighbor(peerId);
        neighborBitfields.put(peerId, bitfield.clone());
    }

    // Update whether a neighbor has a particular piece.
    public synchronized void updateNeighborPiece(int peerId, int pieceIndex, boolean hasPiece) {
        requireNeighbor(peerId);

        if (pieceIndex < 0 || pieceIndex >= localBitfield.length) {
            throw new IllegalArgumentException("Invalid piece index.");
        }

        boolean[] bitfield = neighborBitfields.get(peerId);

        if (bitfield == null) {
            throw new IllegalArgumentException("Neighbor bitfield not initialized.");
        }

        bitfield[pieceIndex] = hasPiece;
    }

    // Check whether we need any pieces this neighbor has.
    public synchronized boolean calculateLocalInterest(int peerId) {
        boolean[] neighborBitfield = neighborBitfields.get(peerId);

        if (neighborBitfield == null) {
            throw new IllegalArgumentException("Unknown neighbor.");
        }

        for (int i = 0; i < localBitfield.length; i++) {
            if (!localBitfield[i] && neighborBitfield[i]) {
                localInterest.put(peerId, true);
                return true;
            }
        }

        localInterest.put(peerId, false);
        return false;
    }

    // Update remote interest when an INTERESTED/NOT_INTERESTED message arrives.
    public synchronized void updateRemoteInterest(int peerId, boolean interested) {
        requireNeighbor(peerId);
        remoteInterest.put(peerId, interested);
    }

    // Update our choking state for a neighbor.
    public synchronized void chokePeer(int peerId) {
        requireNeighbor(peerId);
        localChoke.put(peerId, true);
    }

    public synchronized void unchokePeer(int peerId) {
        requireNeighbor(peerId);
        localChoke.put(peerId, false);
    }

    // Update whether a neighbor is choking us.
    public synchronized void neighborChokesUs(int peerId) {
        requireNeighbor(peerId);
        remoteChoke.put(peerId, true);
    }

    public synchronized void neighborUnchokesUs(int peerId) {
        requireNeighbor(peerId);
        remoteChoke.put(peerId, false);
    }

    // Track which neighbor we requested each piece from.
    public synchronized boolean addPendingPiece(int pieceIndex, int peerId) {
        requireNeighbor(peerId);

        if (pieceIndex < 0 || pieceIndex >= localBitfield.length) {
            throw new IllegalArgumentException("Invalid piece index.");
        }
        
        if (localBitfield[pieceIndex] || pendingPieces.containsKey(pieceIndex)) {
            return false;
        }

        pendingPieces.put(pieceIndex, peerId);
        return true;
    }

    // Call after a received piece has been successfully saved.
    public synchronized void completePieceDownload(
            int pieceIndex, int senderPeerId, long bytesReceived) {

        requireNeighbor(senderPeerId);

        if (pieceIndex < 0 || pieceIndex >= localBitfield.length) {
            throw new IllegalArgumentException("Invalid piece index.");
        }

        if (bytesReceived < 0) {
            throw new IllegalArgumentException("Invalid byte count.");
        }

        localBitfield[pieceIndex] = true;
        pendingPieces.remove(pieceIndex);

        bytesReceivedThisInterval.put(
            senderPeerId,
            bytesReceivedThisInterval.getOrDefault(senderPeerId, 0L)
                + bytesReceived
        );
    }

    // Reset download counters at the end of an unchoking interval.
    public synchronized void resetBytesReceived() {
        for (Integer peerId : bytesReceivedThisInterval.keySet()) {
            bytesReceivedThisInterval.put(peerId, 0L);
        }
    }

    // Getters return copies where mutable data is involved.
    public synchronized boolean[] getLocalBitfield() {
        return localBitfield.clone();
    }

    public synchronized boolean[] getNeighborBitfield(int peerId) {
        boolean[] bitfield = neighborBitfields.get(peerId);
        return bitfield == null ? null : bitfield.clone();
    }

    public synchronized Map<Integer, boolean[]> getNeighborBitfields() {
        Map<Integer, boolean[]> copy = new HashMap<>();

        for (Map.Entry<Integer, boolean[]> entry : neighborBitfields.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }

        return copy;
    }

    public synchronized boolean getLocalInterest(int peerId) {
        return localInterest.getOrDefault(peerId, false);
    }

    public synchronized boolean getRemoteInterest(int peerId) {
        return remoteInterest.getOrDefault(peerId, false);
    }

    public synchronized boolean isChokingPeer(int peerId) {
        return localChoke.getOrDefault(peerId, true);
    }

    public synchronized boolean isChokedByPeer(int peerId) {
        return remoteChoke.getOrDefault(peerId, true);
    }

    public synchronized Map<Integer, Integer> getPendingPieces() {
        return new HashMap<>(pendingPieces);
    }

    public synchronized Map<Integer, Long> getBytesReceivedThisInterval() {
        return new HashMap<>(bytesReceivedThisInterval);
    }

    private void requireNeighbor(int peerId) {
        if (!registeredNeighbors.contains(peerId)) {
            throw new IllegalArgumentException("Unknown neighbor.");
        }
    }




}