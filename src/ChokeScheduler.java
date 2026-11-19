import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// Picks preferred and optimistically unchoked neighbors on timers.
public class ChokeScheduler {

    private static final int CHOKE = 0;
    private static final int UNCHOKE = 1;

    private final int localPeerId;
    private final PeerState state;
    private final PeerLogger logger;
    private final ConnectionManager manager;
    private final CommonConfig config;

    // One thread runs both timers so they never overlap
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task);
                thread.setDaemon(true);
                return thread;
            });

    private Set<Integer> preferred = new HashSet<>();
    private int optimistic = -1;

    public ChokeScheduler(int localPeerId, PeerState state, PeerLogger logger,
            ConnectionManager manager, CommonConfig config) {
        this.localPeerId = localPeerId;
        this.state = state;
        this.logger = logger;
        this.manager = manager;
        this.config = config;
    }

    public void start() {
        int p = config.getUnchokingInterval();
        int m = config.getOptimisticUnchokingInterval();

        timer.scheduleAtFixedRate(
                () -> runSafely(this::selectPreferredNeighbors), p, p, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(
                () -> runSafely(this::selectOptimisticNeighbor), m, m, TimeUnit.SECONDS);
    }

    public void stop() {
        timer.shutdownNow();
    }

    // Every p seconds: pick the k interested neighbors that fed us the most
    private void selectPreferredNeighbors() {
        List<Integer> interested = new ArrayList<>();

        for (int id : connectedNeighbors()) {
            if (state.getRemoteInterest(id)) {
                interested.add(id);
            }
        }

        // Shuffle first so ties are broken randomly, a complete file stays random
        Collections.shuffle(interested);

        if (!hasCompleteFile()) {
            Map<Integer, Long> bytes = state.getBytesReceivedThisInterval();
            interested.sort((a, b) -> Long.compare(
                    bytes.getOrDefault(b, 0L), bytes.getOrDefault(a, 0L)));
        }

        List<Integer> chosen = new ArrayList<>(interested.subList(
                0, Math.min(config.getNumberOfPreferredNeighbors(), interested.size())));
        Set<Integer> newPreferred = new HashSet<>(chosen);

        for (int id : connectedNeighbors()) {
            if (newPreferred.contains(id)) {
                if (state.isChokingPeer(id)) {
                    unchoke(id);
                }
            } else if (id != optimistic && !state.isChokingPeer(id)) {
                choke(id);
            }
        }

        state.resetBytesReceived();

        if (!newPreferred.equals(preferred)) {
            preferred = newPreferred;
            logger.log("Peer " + localPeerId + " has the preferred neighbors "
                    + chosen.stream().map(String::valueOf)
                            .collect(Collectors.joining(",")) + ".");
        }
    }

    // Every m seconds: unchoke one random choked neighbor that is interested
    private void selectOptimisticNeighbor() {
        List<Integer> candidates = new ArrayList<>();

        for (int id : connectedNeighbors()) {
            if (state.getRemoteInterest(id) && state.isChokingPeer(id)) {
                candidates.add(id);
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        int chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        int previous = optimistic;
        optimistic = chosen;
        unchoke(chosen);

        // The old optimistic neighbor goes back to choked unless it is preferred
        if (previous != -1 && previous != chosen && !preferred.contains(previous)
                && connectedNeighbors().contains(previous)
                && !state.isChokingPeer(previous)) {
            choke(previous);
        }

        logger.log("Peer " + localPeerId
                + " has the optimistically unchoked neighbor " + chosen + ".");
    }

    private void unchoke(int peerId) {
        state.unchokePeer(peerId);
        send(peerId, UNCHOKE);
    }

    private void choke(int peerId) {
        state.chokePeer(peerId);
        send(peerId, CHOKE);
    }

    private void send(int peerId, int type) {
        try {
            manager.send(peerId, type, new byte[0]);
        } catch (IOException e) {
            logger.log("Peer " + localPeerId + " could not send to " + peerId
                    + ": " + e.getMessage());
        }
    }

    // Connected peers whose state is already registered
    private List<Integer> connectedNeighbors() {
        List<Integer> result = new ArrayList<>();

        for (int id : manager.connectedPeerIds()) {
            if (state.getNeighborBitfield(id) != null) {
                result.add(id);
            }
        }

        return result;
    }

    private boolean hasCompleteFile() {
        for (boolean piece : state.getLocalBitfield()) {
            if (!piece) {
                return false;
            }
        }

        return true;
    }

    // An exception in a timer task would silently stop future runs
    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            logger.log("Peer " + localPeerId + " choke timer error: " + e);
        }
    }
}
