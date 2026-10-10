package beaconwars;

import java.util.Map;

/** Implement this interface to enter an agent in the arena. */
public interface Agent {
    void initialize(int playerId, Map<String, Object> config);
    Map<Integer, Map<String, Object>> act(Map<String, Object> observation);
}
