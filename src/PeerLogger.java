import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PeerLogger {

    private final String logFileName;
    private final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public PeerLogger(int peerId) {
        this.logFileName = "log_peer_" + peerId + ".log";
    }

    public synchronized void log(String message) {
        String timestamp = LocalDateTime.now().format(formatter);

        try (PrintWriter writer =
                     new PrintWriter(new FileWriter(logFileName, true))) {

            writer.println(timestamp + ": " + message);

        } catch (IOException e) {
            System.err.println(
                "Could not write to log file: " + e.getMessage()
            );
        }
    }

    public void logConnectionMade(int localPeerId, int remotePeerId) {
        log("Peer " + localPeerId +
            " makes a connection to Peer " + remotePeerId + ".");
    }

    public void logConnectionReceived(int localPeerId, int remotePeerId) {
        log("Peer " + localPeerId +
            " is connected from Peer " + remotePeerId + ".");
    }
}