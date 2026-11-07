import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.OutputStream;

// Reads complete messages from a TCP byte stream
public class ProtocolIO {
    // Reads all 32 handshake bytes or reports an early close
    public static byte[] readHandshake(DataInputStream input)
            throws IOException {
        byte[] handshake = new byte[32];
        input.readFully(handshake);
        return handshake;
    }

    // Reads one complete normal message, or returns null between messages
    public static byte[] readMessageFrame(DataInputStream input, int maxLength)
            throws IOException {
        // Returns null if the peer closes before starting another message
        int firstByte = input.read();
        if (firstByte == -1) {
            return null;
        }

        // Reads the remaining bytes of the message length
        byte[] lengthBytes = new byte[4];
        lengthBytes[0] = (byte) firstByte;
        input.readFully(lengthBytes, 1, 3);

        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length < 1 || length > maxLength || length > Integer.MAX_VALUE - 4) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] frame = new byte[4 + length];
        System.arraycopy(lengthBytes, 0, frame, 0, 4);
        input.readFully(frame, 4, length);
        return frame;
    }

    // Sends one complete normal message without mixing it with another send
    public static void writeMessage(OutputStream output, int type, byte[] payload)
            throws IOException {
        byte[] frame = ProtocolCodec.encodeMessage(type, payload);

        synchronized (output) {
            output.write(frame);
        }
    }
}