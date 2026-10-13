import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Handles the byte format for our handshakes and normal messages
public class ProtocolCodec {
    // Builds the 32-byte handshake using this peer's ID
    public static byte[] encodeHandshake(int localPeerId) {
        if (localPeerId <= 0) {
            throw new IllegalArgumentException("Peer ID must be positive");
        }
        
        byte[] result = new byte[32];
        byte[] header = "P2PFILESHARINGPROJ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, result, 0, header.length);

        result[28] = (byte) (localPeerId >>> 24);
        result[29] = (byte) (localPeerId >>> 16);
        result[30] = (byte) (localPeerId >>> 8);
        result[31] = (byte) localPeerId;

        return result;
    }

    // Checks a received handshake and reads the peer ID from it
    public static int decodeHandshake(byte[] handshake) {
        if (handshake == null || handshake.length != 32) {
            throw new IllegalArgumentException("Handshake must be exactly 32 bytes");
        }

        byte[] expectedHeader ="P2PFILESHARINGPROJ".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedHeader.length; i++) {
            if (handshake[i] != expectedHeader[i]) {
                throw new IllegalArgumentException("Wrong handshake header");
            }
        }

        for (int i = 18; i < 28; i++) {
            if (handshake[i] != 0) {
                throw new IllegalArgumentException("Handshake zero section is not zero");
            }
        }

        int peerId = ByteBuffer.wrap(handshake, 28, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (peerId <= 0) {
            throw new IllegalArgumentException("Peer ID must be positive");
        }

        return peerId;
    }

    // Packs a message's length, type, and extra data into bytes
    public static byte[] encodeMessage(int type, byte[] payload) {
        if (type < 0 || type > 7) {
            throw new IllegalArgumentException("Unknown message type");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (type <= 3 && payload.length != 0) {
            throw new IllegalArgumentException("This message type must have no payload");
        }
        if ((type == 4 || type == 6) && payload.length != 4) {
            throw new IllegalArgumentException("Have and request need a four-byte piece index");
        }
        if (type == 7 && payload.length <= 4) {
            throw new IllegalArgumentException("Piece needs a four-byte index and file data");
        }

        int length = 1 + payload.length;
        ByteBuffer message = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN);

        message.putInt(length);
        message.put((byte) type);
        message.put(payload);

        return message.array();
    }

    // Checks a complete message and separates its type and extra data
    public static DecodedMessage decodeMessage(byte[] frame) {
        if (frame == null || frame.length < 5) {
            throw new IllegalArgumentException("Normal message is too short");
        }

        int declaredLength = ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        if (declaredLength != frame.length - 4) {
            throw new IllegalArgumentException("Message length does not match its bytes");
        }

        int type = frame[4];
        if (type < 0 || type > 7) {
            throw new IllegalArgumentException("Unknown message type");
        }

        int payloadLength = declaredLength - 1;
        if (type <= 3 && payloadLength != 0) {
            throw new IllegalArgumentException("This message type must have no payload");
        }
        if ((type == 4 || type == 6) && payloadLength != 4) {
            throw new IllegalArgumentException("Have and request need a four-byte piece index");
        }
        if (type == 7 && payloadLength <= 4) {
            throw new IllegalArgumentException("Piece needs a four-byte index and file data");
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, 5, payload, 0, payloadLength);
        return new DecodedMessage(type, payload);
    }

    // Holds the type and extra data from a decoded message
    public static class DecodedMessage {
        public final int type;
        public final byte[] payload;

        // Stores those two values in this message holder
        public DecodedMessage(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}

