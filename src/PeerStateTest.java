/*
cd src
javac PeerState.java PeerStateTest.java
java PeerStateTest
*/


import java.util.Arrays;
import java.util.Map;

public class PeerStateTest {
    private static int testsRun = 0;

    public static void main(String[] args) {
        testInitialBitfield();
        testNeighborBitfieldsAndInterest();
        testNeighborPieceUpdate();
        testChokeStates();
        testPendingPiecesAndDownload();
        testByteCounterReset();
        testDefensiveCopies();

        System.out.println("All " + testsRun + " checks passed.");
    }

    private static void testInitialBitfield() {
        PeerState state = new PeerState(4);

        assertArrayEquals(new boolean[] {false, false, false, false},
                state.getLocalBitfield(), "Initial bitfield");

        state.initializeLocalBitfield(true);
        assertArrayEquals(new boolean[] {true, true, true, true},
                state.getLocalBitfield(), "Complete-file bitfield");

        state.initializeLocalBitfield(false);
        assertArrayEquals(new boolean[] {false, false, false, false},
                state.getLocalBitfield(), "Empty-file bitfield");
    }

    private static void testNeighborBitfieldsAndInterest() {
        PeerState state = new PeerState(4);
        state.updateLocalBitfield(0, true);
        state.registerNeighbor(1002);
        state.updateNeighborBitfield(
                1002, new boolean[] {false, true, true, false});

        assertTrue(state.calculateLocalInterest(1002),
                "Should need pieces from neighbor");
        assertTrue(state.getLocalInterest(1002),
                "Local interest should be stored");

        state.updateLocalBitfield(1, true);
        state.updateLocalBitfield(2, true);

        assertFalse(state.calculateLocalInterest(1002),
                "Should no longer need neighbor's pieces");
        assertFalse(state.getLocalInterest(1002),
                "Local interest should be cleared");
    }

    private static void testNeighborPieceUpdate() {
        PeerState state = new PeerState(3);
        state.registerNeighbor(1003);
        state.updateNeighborBitfield(
                1003, new boolean[] {false, false, false});

        state.updateNeighborPiece(1003, 1, true);
        assertArrayEquals(new boolean[] {false, true, false},
                state.getNeighborBitfield(1003), "Piece availability update");
    }

    private static void testChokeStates() {
        PeerState state = new PeerState(2);
        state.registerNeighbor(1004);

        assertTrue(state.isChokingPeer(1004), "Initially choking neighbor");
        assertTrue(state.isChokedByPeer(1004), "Initially choked by neighbor");

        state.unchokePeer(1004);
        state.neighborUnchokesUs(1004);

        assertFalse(state.isChokingPeer(1004), "Local unchoke");
        assertFalse(state.isChokedByPeer(1004), "Remote unchoke");

        state.chokePeer(1004);
        state.neighborChokesUs(1004);

        assertTrue(state.isChokingPeer(1004), "Local choke");
        assertTrue(state.isChokedByPeer(1004), "Remote choke");

        state.updateRemoteInterest(1004, true);
        assertTrue(state.getRemoteInterest(1004), "Remote interested");
        state.updateRemoteInterest(1004, false);
        assertFalse(state.getRemoteInterest(1004), "Remote not interested");
    }

    private static void testPendingPiecesAndDownload() {
        PeerState state = new PeerState(3);
        state.registerNeighbor(1005);
        state.registerNeighbor(1006);
        state.updateLocalBitfield(0, true);

        assertFalse(
                state.addPendingPiece(0, 1005),
                "Already-owned piece should be rejected"
        );

        assertTrue(state.addPendingPiece(1, 1005),
                "First request should be accepted");
        assertFalse(state.addPendingPiece(1, 1006),
                "Duplicate request should be rejected");
        assertFalse(state.addPendingPiece(0, 1005),
                "Already-owned piece should be rejected");

        assertEquals(Integer.valueOf(1005),
                state.getPendingPieces().get(1), "Pending request source");

        state.completePieceDownload(1, 1005, 32768);

        assertTrue(state.getLocalBitfield()[1],
                "Downloaded piece should be marked present");
        assertFalse(state.getPendingPieces().containsKey(1),
                "Completed request should be removed");
        assertEquals(Long.valueOf(32768),
                state.getBytesReceivedThisInterval().get(1005),
                "Received bytes should be credited to sender");
    }

    private static void testByteCounterReset() {
        PeerState state = new PeerState(2);
        state.registerNeighbor(1007);
        state.registerNeighbor(1008);

        state.completePieceDownload(0, 1007, 1200);
        state.completePieceDownload(1, 1008, 800);
        state.resetBytesReceived();

        Map<Integer, Long> counters = state.getBytesReceivedThisInterval();
        assertEquals(Long.valueOf(0), counters.get(1007), "First counter reset");
        assertEquals(Long.valueOf(0), counters.get(1008), "Second counter reset");
    }

    private static void testDefensiveCopies() {
        PeerState state = new PeerState(2);
        state.registerNeighbor(1009);

        boolean[] supplied = {true, false};
        state.updateNeighborBitfield(1009, supplied);
        supplied[0] = false;

        assertArrayEquals(new boolean[] {true, false},
                state.getNeighborBitfield(1009), "Input bitfield copied");

        boolean[] returned = state.getLocalBitfield();
        returned[0] = true;

        assertArrayEquals(new boolean[] {false, false},
                state.getLocalBitfield(), "Local bitfield getter copied");

        boolean[] neighborReturned = state.getNeighborBitfield(1009);
        neighborReturned[0] = false;

        assertArrayEquals(new boolean[] {true, false},
                state.getNeighborBitfield(1009), "Neighbor bitfield getter copied");
    }

    private static void assertTrue(boolean condition, String message) {
        testsRun++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(
            Object expected, Object actual, String message) {
        testsRun++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected
                    + ", got " + actual);
        }
    }

    private static void assertArrayEquals(
            boolean[] expected, boolean[] actual, String message) {
        testsRun++;
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": expected "
                    + Arrays.toString(expected) + ", got "
                    + Arrays.toString(actual));
        }
    }
}

