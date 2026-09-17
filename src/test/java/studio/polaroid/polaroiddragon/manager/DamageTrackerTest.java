package studio.polaroid.polaroiddragon.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DamageTracker is plain Java, so it is testable without a running server.
 * It is also the only per-hit hot path in the plugin and the one structure read
 * from both the main thread and PlaceholderAPI's threads.
 */
class DamageTrackerTest {

    private DamageTracker tracker;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB   = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CARL  = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @BeforeEach
    void setUp() {
        tracker = new DamageTracker();
    }

    @Test
    void accumulatesRepeatedHitsFromTheSamePlayer() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        tracker.addDamage(ALICE, "Alice", 5.5);

        assertEquals(15.5, tracker.getDamage(ALICE));
        assertEquals(1, tracker.getParticipantCount());
    }

    @Test
    void rankingIsOrderedByDamageDescending() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        tracker.addDamage(BOB, "Bob", 30.0);
        tracker.addDamage(CARL, "Carl", 20.0);

        List<Map.Entry<UUID, Double>> ranking = tracker.getRanking();

        assertEquals(List.of(BOB, CARL, ALICE),
                ranking.stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void rankingReflectsWritesThatHappenedAfterTheLastRead() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        assertEquals(ALICE, tracker.getRanking().get(0).getKey());

        // The ranking rebuilds lazily; a read after a write must still be current.
        tracker.addDamage(BOB, "Bob", 99.0);

        assertEquals(BOB, tracker.getRanking().get(0).getKey());
    }

    @Test
    void tiedDamageIsBrokenDeterministically() {
        tracker.addDamage(ALICE, "Alice", 50.0);
        tracker.addDamage(BOB, "Bob", 50.0);

        List<UUID> first = tracker.getRanking().stream().map(Map.Entry::getKey).toList();

        DamageTracker other = new DamageTracker();
        // Same damage, inserted in the opposite order.
        other.addDamage(BOB, "Bob", 50.0);
        other.addDamage(ALICE, "Alice", 50.0);

        assertEquals(first, other.getRanking().stream().map(Map.Entry::getKey).toList(),
                "a tie must not depend on insertion order");
    }

    @Test
    void rankingSnapshotIsImmutable() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        List<Map.Entry<UUID, Double>> ranking = tracker.getRanking();

        assertThrows(UnsupportedOperationException.class, () -> ranking.clear());
    }

    @Test
    void unknownPlayerHasZeroDamage() {
        assertEquals(0.0, tracker.getDamage(ALICE));
        assertTrue(tracker.getRanking().isEmpty());
    }

    @Test
    void playerNameFallsBackToTheUuid() {
        assertEquals(ALICE.toString(), tracker.getPlayerName(ALICE));

        tracker.addDamage(ALICE, "Alice", 1.0);
        assertEquals("Alice", tracker.getPlayerName(ALICE));
    }

    @Test
    void resetClearsEverything() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        tracker.reset();

        assertEquals(0, tracker.getParticipantCount());
        assertEquals(0.0, tracker.getDamage(ALICE));
        assertTrue(tracker.getRanking().isEmpty());
    }

    @Test
    void restoreRebuildsTheLedgerAfterARestart() {
        tracker.restore(ALICE, "Alice", 120.0);
        tracker.restore(BOB, "Bob", 340.0);

        assertEquals(2, tracker.getParticipantCount());
        assertEquals(120.0, tracker.getDamage(ALICE));
        assertEquals(BOB, tracker.getRanking().get(0).getKey(),
                "a restored ledger must rank like a live one");
        assertEquals("Alice", tracker.getPlayerName(ALICE));
    }

    @Test
    void restoreIgnoresNonPositiveDamage() {
        tracker.restore(ALICE, "Alice", 0.0);
        tracker.restore(BOB, "Bob", -5.0);

        assertEquals(0, tracker.getParticipantCount());
    }

    @Test
    void snapshotRoundTripsThroughRestore() {
        tracker.addDamage(ALICE, "Alice", 10.0);
        tracker.addDamage(BOB, "Bob", 20.0);

        Map<UUID, Double> damage = tracker.snapshotDamage();
        Map<UUID, String> names = tracker.snapshotNames();

        DamageTracker restored = new DamageTracker();
        damage.forEach((uuid, value) -> restored.restore(uuid, names.get(uuid), value));

        assertEquals(tracker.getRanking(), restored.getRanking());
    }

    /**
     * Reads come from PlaceholderAPI's threads while the damage listener writes
     * on the main thread. A rebuild that is not safe under that mix surfaces as
     * a ConcurrentModificationException mid-event.
     */
    @Test
    void concurrentReadsAndWritesDoNotThrow() throws Exception {
        int writers = 4;
        int readers = 4;
        int iterations = 500;

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);

        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        tracker.addDamage(UUID.randomUUID(), "Player" + i, 1.0);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        for (Map.Entry<UUID, Double> entry : tracker.getRanking()) {
                            assertNotNull(entry.getKey());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish in time");
        pool.shutdownNow();

        assertEquals(writers * iterations, tracker.getParticipantCount());
    }
}
