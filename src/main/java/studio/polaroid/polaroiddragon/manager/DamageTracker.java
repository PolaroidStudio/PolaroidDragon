package studio.polaroid.polaroiddragon.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-event damage accumulator.
 *
 * <p>Writes come from the main thread (damage listener), but reads come from
 * PlaceholderAPI, which scoreboard/tab plugins routinely call off the main
 * thread. Every mutable field is therefore concurrent, and the ranking is
 * exposed as an immutable volatile snapshot instead of a live stream, which
 * would otherwise throw {@link java.util.ConcurrentModificationException}
 * halfway through an event.
 */
public class DamageTracker {

    private final Map<UUID, Double> damageMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    /** Immutable ranking snapshot. Safe to read from any thread. */
    private volatile List<Map.Entry<UUID, Double>> cachedRanking = List.of();

    /** Set by writes, cleared when a reader rebuilds the snapshot. */
    private volatile boolean rankingDirty = false;

    /** Serializes rebuilds so concurrent readers do not each sort the same data. */
    private final Object rebuildLock = new Object();

    public void addDamage(UUID uuid, String playerName, double damage) {
        damageMap.merge(uuid, damage, Double::sum);
        nameCache.put(uuid, playerName);
        // Mark dirty instead of sorting here. This runs on every hit from every
        // player for the whole fight; sorting and copying the full ranking per
        // hit was hundreds of sorts per second on the main thread.
        rankingDirty = true;
    }

    public double getDamage(UUID uuid) {
        return damageMap.getOrDefault(uuid, 0.0);
    }

    /** Ranking from highest to lowest damage. Immutable snapshot, safe off the main thread. */
    public List<Map.Entry<UUID, Double>> getRanking() {
        if (rankingDirty) {
            synchronized (rebuildLock) {
                // Re-check: another reader may have rebuilt it while we waited.
                if (rankingDirty) {
                    rankingDirty = false;
                    rebuildRanking();
                }
            }
        }
        return cachedRanking;
    }

    public String getPlayerName(UUID uuid) {
        return nameCache.getOrDefault(uuid, uuid.toString());
    }

    /** Copy of the participant set, so callers cannot observe later mutations. */
    public Set<UUID> getParticipants() {
        return new HashSet<>(damageMap.keySet());
    }

    public int getParticipantCount() {
        return damageMap.size();
    }

    public void reset() {
        damageMap.clear();
        nameCache.clear();
        cachedRanking = List.of();
        rankingDirty = false;
    }

    /**
     * Restores one participant's accumulated damage.
     *
     * <p>Used to rebuild the ledger after a restart mid-fight. Damage lives only
     * in memory during an event, so without this the server could be restarted
     * with the dragon at 30% health and every reward would then be paid out
     * against an empty ranking.
     */
    public void restore(UUID uuid, String playerName, double damage) {
        if (damage <= 0) return;
        damageMap.put(uuid, damage);
        if (playerName != null && !playerName.isBlank()) nameCache.put(uuid, playerName);
        rankingDirty = true;
    }

    /** Immutable view of the current ledger, for persistence. */
    public Map<UUID, Double> snapshotDamage() {
        return Map.copyOf(damageMap);
    }

    /** Immutable view of the cached participant names, for persistence. */
    public Map<UUID, String> snapshotNames() {
        return Map.copyOf(nameCache);
    }

    private void rebuildRanking() {
        List<Map.Entry<UUID, Double>> snapshot = new ArrayList<>(damageMap.size());
        for (Map.Entry<UUID, Double> entry : damageMap.entrySet()) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        // Tie-break on the UUID itself: comparing toString() allocated a string
        // per comparison, i.e. O(n log n) garbage per rebuild.
        snapshot.sort(Map.Entry.<UUID, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey));
        cachedRanking = List.copyOf(snapshot);
    }
}
