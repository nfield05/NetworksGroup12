import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

// Handles normal messages over one connected peer socket
public class PeerConnection implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream input;
    private final OutputStream output;
    private final int maxLength;

    // Stores the connection and opens its input and output streams
    public PeerConnection(Socket socket, int maxLength) throws IOException {
        if (maxLength < 1) {
            throw new IllegalArgumentException("Maximum length must be positive");
        }

        this.socket = socket;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = socket.getOutputStream();
        this.maxLength = maxLength;
    }

    // Exchanges handshakes and reads the other peer's ID
    public int handshake(int localPeerId) throws IOException {
        byte[] handshake = ProtocolCodec.encodeHandshake(localPeerId);

        synchronized (output) {
            output.write(handshake);
        }

        return ProtocolCodec.decodeHandshake(ProtocolIO.readHandshake(input));
    }

    // Exchanges handshakes and checks the expected peer's ID
    public int handshake(int localPeerId, int expectedPeerId) throws IOException {
        int peerId = handshake(localPeerId);
        if (peerId != expectedPeerId) {
            throw new IOException("Unexpected peer ID: " + peerId);
        }

        return peerId;
    }

    // Sends one normal message through this connection
    public void send(int type, byte[] payload) throws IOException {
        ProtocolIO.writeMessage(output, type, payload);
    }

    // Reads and decodes one message, or returns null between messages
    public ProtocolCodec.DecodedMessage receive() throws IOException {
        byte[] frame = ProtocolIO.readMessageFrame(input, maxLength);
        if (frame == null) {
            return null;
        }

        return ProtocolCodec.decodeMessage(frame);
    }

    // Closes the socket and its input and output streams
    @Override
    public void close() throws IOException {
        socket.close();
    }
}