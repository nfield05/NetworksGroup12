import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Reads complete messages from a TCP byte stream
public class ProtocolIO {
    // Reads all 32 handshake bytes or reports an early close
    public static byte[] readHandshake(DataInputStream input)
            throws IOException {
        byte[] handshake = new byte[32];
        input.readFully(handshake);
        return handshake;
    }

    // Reads the length first, then reads one complete normal message
    public static byte[] readMessageFrame(DataInputStream input, int maxLength)
            throws IOException {
        byte[] lengthBytes = new byte[4];
        input.readFully(lengthBytes);

        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length < 1 || length > maxLength || length > Integer.MAX_VALUE - 4) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] frame = new byte[4 + length];
        System.arraycopy(lengthBytes, 0, frame, 0, 4);
        input.readFully(frame, 4, length);
        return frame;
    }
}