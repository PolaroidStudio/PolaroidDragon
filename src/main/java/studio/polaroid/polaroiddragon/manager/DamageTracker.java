package studio.polaroid.polaroiddragon.manager;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** Immutable ranking snapshot, replaced on every write. Safe to read from any thread. */
    private volatile List<Map.Entry<UUID, Double>> cachedRanking = List.of();

    public void addDamage(UUID uuid, String playerName, double damage) {
        damageMap.merge(uuid, damage, Double::sum);
        nameCache.put(uuid, playerName);
        rebuildRanking();
    }

    public double getDamage(UUID uuid) {
        return damageMap.getOrDefault(uuid, 0.0);
    }

    /** Ranking from highest to lowest damage. Immutable snapshot, safe off the main thread. */
    public List<Map.Entry<UUID, Double>> getRanking() {
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
    }

    private void rebuildRanking() {
        List<Map.Entry<UUID, Double>> snapshot = new ArrayList<>(damageMap.size());
        for (Map.Entry<UUID, Double> entry : damageMap.entrySet()) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        snapshot.sort(Map.Entry.<UUID, Double>comparingByValue().reversed()
                .thenComparing(Comparator.comparing(e -> e.getKey().toString())));
        cachedRanking = List.copyOf(snapshot);
    }
}
