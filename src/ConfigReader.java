import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConfigReader {

    public static CommonConfig readCommonConfig(String filePath)
            throws IOException {

        int numberOfPreferredNeighbors = -1;
        int unchokingInterval = -1;
        int optimisticUnchokingInterval = -1;
        String fileName = null;
        long fileSize = -1;
        int pieceSize = -1;

        try (BufferedReader reader =
                     new BufferedReader(new FileReader(filePath))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);

                if (parts.length != 2) {
                    throw new IOException(
                        "Invalid line in Common.cfg: " + line
                    );
                }

                String key = parts[0];
                String value = parts[1];

                switch (key) {
                    case "NumberOfPreferredNeighbors":
                        numberOfPreferredNeighbors =
                            Integer.parseInt(value);
                        break;

                    case "UnchokingInterval":
                        unchokingInterval =
                            Integer.parseInt(value);
                        break;

                    case "OptimisticUnchokingInterval":
                        optimisticUnchokingInterval =
                            Integer.parseInt(value);
                        break;

                    case "FileName":
                        fileName = value;
                        break;

                    case "FileSize":
                        fileSize = Long.parseLong(value);
                        break;

                    case "PieceSize":
                        pieceSize = Integer.parseInt(value);
                        break;

                    default:
                        throw new IOException(
                            "Unknown Common.cfg setting: " + key
                        );
                }
            }
        }

        if (numberOfPreferredNeighbors < 0 ||
            unchokingInterval <= 0 ||
            optimisticUnchokingInterval <= 0 ||
            fileName == null ||
            fileSize <= 0 ||
            pieceSize <= 0) {

            throw new IOException(
                "Common.cfg is missing one or more required settings."
            );
        }

        return new CommonConfig(
            numberOfPreferredNeighbors,
            unchokingInterval,
            optimisticUnchokingInterval,
            fileName,
            fileSize,
            pieceSize
        );
    }

    public static List<PeerInfo> readPeerInfo(String filePath)
            throws IOException {

        List<PeerInfo> peers = new ArrayList<>();

        try (BufferedReader reader =
                     new BufferedReader(new FileReader(filePath))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+");

                if (parts.length != 4) {
                    throw new IOException(
                        "Invalid line in PeerInfo.cfg: " + line
                    );
                }

                int peerId = Integer.parseInt(parts[0]);
                String hostName = parts[1];
                int port = Integer.parseInt(parts[2]);

                int hasFileValue = Integer.parseInt(parts[3]);

                if (hasFileValue != 0 && hasFileValue != 1) {
                    throw new IOException(
                        "Peer file flag must be 0 or 1."
                    );
                }

                peers.add(
                    new PeerInfo(
                        peerId,
                        hostName,
                        port,
                        hasFileValue == 1
                    )
                );
            }
        }

        if (peers.isEmpty()) {
            throw new IOException("PeerInfo.cfg contains no peers.");
        }

        return peers;
    }

    public static PeerInfo findPeer(
            List<PeerInfo> peers,
            int peerId) {

        for (PeerInfo peer : peers) {
            if (peer.getPeerId() == peerId) {
                return peer;
            }
        }

        throw new IllegalArgumentException(
            "Peer ID not found: " + peerId
        );
    }
}