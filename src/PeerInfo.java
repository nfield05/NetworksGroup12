public class PeerInfo {

    private final int peerId;
    private final String hostName;
    private final int port;
    private final boolean hasFile;

    public PeerInfo(int peerId, String hostName, int port, boolean hasFile) {
        this.peerId = peerId;
        this.hostName = hostName;
        this.port = port;
        this.hasFile = hasFile;
    }

    public int getPeerId() {
        return peerId;
    }

    public String getHostName() {
        return hostName;
    }

    public int getPort() {
        return port;
    }

    public boolean hasFile() {
        return hasFile;
    }
}