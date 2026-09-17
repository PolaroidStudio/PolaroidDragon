package studio.polaroid.polaroiddragon.manager;

import java.util.*;
import java.util.stream.Collectors;

public class DamageTracker {

    private final Map<UUID, Double> damageMap = new HashMap<>();
    private final Map<UUID, String> nameCache = new HashMap<>();

    public void addDamage(UUID uuid, String playerName, double damage) {
        damageMap.merge(uuid, damage, Double::sum);
        nameCache.put(uuid, playerName);
    }

    public double getDamage(UUID uuid) {
        return damageMap.getOrDefault(uuid, 0.0);
    }

    /** Ranking de mayor a menor daño. */
    public List<Map.Entry<UUID, Double>> getRanking() {
        return damageMap.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .collect(Collectors.toList());
    }

    public String getPlayerName(UUID uuid) {
        return nameCache.getOrDefault(uuid, uuid.toString());
    }

    public Set<UUID> getParticipants() {
        return Collections.unmodifiableSet(damageMap.keySet());
    }

    public int getParticipantCount() {
        return damageMap.size();
    }

    public void reset() {
        damageMap.clear();
        nameCache.clear();
    }
}
