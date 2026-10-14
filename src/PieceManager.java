import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public class PieceManager {

    private final Path filePath;
    private final long fileSize;
    private final int pieceSize;

    public PieceManager(
            Path peerDirectory,
            String fileName,
            long fileSize,
            int pieceSize) throws IOException {

        this.filePath = peerDirectory.resolve(fileName);
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;

        Files.createDirectories(peerDirectory);
    }

    public int getNumberOfPieces() {
        return (int) ((fileSize + pieceSize - 1) / pieceSize);
    }

    public int getPieceLength(int pieceIndex) {
        checkPieceIndex(pieceIndex);

        long offset = (long) pieceIndex * pieceSize;
        return (int) Math.min(pieceSize, fileSize - offset);
    }

    public byte[] readPiece(int pieceIndex) throws IOException {
        checkPieceIndex(pieceIndex);

        int length = getPieceLength(pieceIndex);
        byte[] data = new byte[length];

        try (RandomAccessFile file =
                     new RandomAccessFile(filePath.toFile(), "r")) {

            long offset = (long) pieceIndex * pieceSize;
            file.seek(offset);
            file.readFully(data);
        }

        return data;
    }

    public void savePiece(int pieceIndex, byte[] data) throws IOException {
        checkPieceIndex(pieceIndex);

        int expectedLength = getPieceLength(pieceIndex);

        if (data == null || data.length != expectedLength) {
            throw new IllegalArgumentException(
                "Incorrect piece length for piece " + pieceIndex
            );
        }

        try (RandomAccessFile file =
                     new RandomAccessFile(filePath.toFile(), "rw")) {

            long offset = (long) pieceIndex * pieceSize;
            file.seek(offset);
            file.write(data);
        }
    }

    public boolean fileExists() {
        return Files.exists(filePath);
    }

    private void checkPieceIndex(int pieceIndex) {
        if (pieceIndex < 0 || pieceIndex >= getNumberOfPieces()) {
            throw new IllegalArgumentException(
                "Invalid piece index: " + pieceIndex
            );
        }
    }
}
