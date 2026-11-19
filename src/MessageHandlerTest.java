/*
cd src
javac *.java
java MessageHandlerTest
*/

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

// Runs three real peers on localhost in one program.
// Peer 9001 starts with the file and 9002/9003 should download all of it.
public class MessageHandlerTest {

    private static final String FILE_NAME = "TheFile.dat";
    private static final int FILE_SIZE = 4500;
    private static final int PIECE_SIZE = 1000;
    private static final int[] IDS = {9001, 9002, 9003};
    private static final int[] PORTS = {6201, 6202, 6203};

    private static class Peer {
        int id;
        PeerState state;
        PeerLogger logger;
        ConnectionManager manager;
        ChokeScheduler scheduler;
    }

    public static void main(String[] args) throws Exception {
        CommonConfig config = new CommonConfig(
                2, 1, 2, FILE_NAME, FILE_SIZE, PIECE_SIZE);

        byte[] original = new byte[FILE_SIZE];
        new Random(1).nextBytes(original);

        Path root = Files.createTempDirectory("p2ptest");
        Files.createDirectories(root.resolve("peer_9001"));
        Files.write(root.resolve("peer_9001").resolve(FILE_NAME), original);

        List<ConnectionManager.PeerAddress> addresses = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            addresses.add(new ConnectionManager.PeerAddress(
                    IDS[i], "localhost", PORTS[i]));
        }

        List<Peer> peers = new ArrayList<>();

        try {
            // Peers start in config order, later ones connect to earlier ones
            for (int id : IDS) {
                Files.deleteIfExists(Path.of("log_peer_" + id + ".log"));

                Peer peer = new Peer();
                peer.id = id;
                peer.state = new PeerState(config.getNumberOfPieces());
                peer.state.initializeLocalBitfield(id == 9001);
                peer.logger = new PeerLogger(id);

                PieceManager pieces = new PieceManager(
                        root.resolve("peer_" + id), FILE_NAME, FILE_SIZE, PIECE_SIZE);
                MessageHandler handler = new MessageHandler(
                        id, peer.state, peer.logger, pieces, config);

                peer.manager = new ConnectionManager(
                        id, addresses, PIECE_SIZE + 16, handler);
                handler.setConnectionManager(peer.manager);
                peer.manager.start();

                peer.scheduler = new ChokeScheduler(
                        id, peer.state, peer.logger, peer.manager, config);
                peer.scheduler.start();

                peers.add(peer);
            }

            // Wait up to 30 seconds for both downloaders to finish
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline
                    && !(isComplete(peers.get(1)) && isComplete(peers.get(2)))) {
                Thread.sleep(200);
            }

            check(isComplete(peers.get(1)), "Peer 9002 downloaded every piece");
            check(isComplete(peers.get(2)), "Peer 9003 downloaded every piece");

            for (int id : new int[] {9002, 9003}) {
                byte[] copy = Files.readAllBytes(
                        root.resolve("peer_" + id).resolve(FILE_NAME));
                check(Arrays.equals(original, copy),
                        "Peer " + id + " file matches the original");

                String log = Files.readString(Path.of("log_peer_" + id + ".log"));
                check(log.contains("has downloaded the complete file."),
                        "Peer " + id + " logged completion");
                check(log.contains("has downloaded the piece"),
                        "Peer " + id + " logged piece downloads");
            }

            String seedLog = Files.readString(Path.of("log_peer_9001.log"));
            check(seedLog.contains("received the 'interested' message"),
                    "Seed logged interested messages");
            check(seedLog.contains("has the preferred neighbors"),
                    "Seed logged preferred neighbors");

            System.out.println("All checks passed.");
        } finally {
            for (Peer peer : peers) {
                if (peer.scheduler != null) {
                    peer.scheduler.stop();
                }
                peer.manager.close();
            }
        }
    }

    private static boolean isComplete(Peer peer) {
        for (boolean piece : peer.state.getLocalBitfield()) {
            if (!piece) {
                return false;
            }
        }

        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        System.out.println("ok: " + message);
    }
}
