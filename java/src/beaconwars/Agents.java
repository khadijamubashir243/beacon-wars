package beaconwars;

import java.util.*;

/** Built-in baseline and champion strategies. */
public final class Agents {
    private Agents() {}

    public static Agent create(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "random": return new RandomAgent();
            case "greedy": return new GreedyAgent();
            case "aggressive": return new AggressiveAgent();
            case "champion": return new ChampionAgent();
            default: throw new IllegalArgumentException("Unknown built-in agent: " + name);
        }
    }

    static int number(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> rows(Object value) { return value instanceof List<?> ? (List<Map<String, Object>>) value : Collections.emptyList(); }
    static Game.Pos pos(Object value) {
        if (!(value instanceof List<?>)) return null;
        List<?> pair = (List<?>) value;
        return pair.size() == 2 ? new Game.Pos(number(pair.get(0), -1), number(pair.get(1), -1)) : null;
    }
    static Map<String, Object> move(String direction) { Map<String, Object> result = new LinkedHashMap<>(); result.put("type", "MOVE"); result.put("dir", direction); return result; }
    static Map<String, Object> waitOrder() { return Collections.singletonMap("type", "WAIT"); }
    static Map<String, Object> attack(Game.Pos pos) { Map<String, Object> result = new LinkedHashMap<>(); result.put("type", "ATTACK"); result.put("target", pos.json()); return result; }

    private abstract static class PathAgent implements Agent {
        int playerId;
        @Override public void initialize(int playerId, Map<String, Object> config) { this.playerId = playerId; }
        List<Game.Pos> knownWalls(Map<String, Object> obs) {
            Set<Game.Pos> walls = new HashSet<>(); for (Object value : (List<?>) obs.getOrDefault("known_walls", Collections.emptyList())) walls.add(pos(value)); return new ArrayList<>(walls);
        }
        String path(Game.Pos start, Set<Game.Pos> goals, Set<Game.Pos> walls, Set<Game.Pos> avoid) {
            Queue<Game.Pos> queue = new ArrayDeque<>(); queue.add(start);
            Map<Game.Pos, String> first = new HashMap<>(); first.put(start, null);
            while (!queue.isEmpty()) {
                Game.Pos current = queue.remove(); if (goals.contains(current)) return first.get(current);
                for (int direction = 0; direction < 4; direction++) {
                    Game.Pos next = new Game.Pos(current.row + Game.DELTAS[direction][0], current.col + Game.DELTAS[direction][1]);
                    if (next.row < 0 || next.row >= Game.SIZE || next.col < 0 || next.col >= Game.SIZE || walls.contains(next) || first.containsKey(next) || avoid.contains(next)) continue;
                    first.put(next, current.equals(start) ? Game.DIRECTIONS[direction] : first.get(current)); queue.add(next);
                }
            }
            return null;
        }
        Set<Game.Pos> beaconGoals(Game.Pos beacon, Set<Game.Pos> walls) {
            Set<Game.Pos> result = new HashSet<>();
            for (int row = Math.max(0, beacon.row - 1); row <= Math.min(14, beacon.row + 1); row++)
                for (int col = Math.max(0, beacon.col - 1); col <= Math.min(14, beacon.col + 1); col++) {
                    Game.Pos candidate = new Game.Pos(row, col);
                    if (candidate.distance(beacon) <= 1 && !walls.contains(candidate)) result.add(candidate);
                }
            return result;
        }
    }

    public static final class RandomAgent implements Agent {
        private Random random;
        @Override public void initialize(int playerId, Map<String, Object> config) {
            random = new Random(number(config.get("seed"), 0) + playerId * 7919L);
        }
        @Override public Map<Integer, Map<String, Object>> act(Map<String, Object> obs) {
            Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map<String, Object> unit : rows(obs.get("units"))) {
                if (unit.get("pos") == null) continue;
                List<Map<String, Object>> choices = new ArrayList<>(); choices.add(waitOrder()); choices.add(Collections.singletonMap("type", "SCAN"));
                for (String direction : Game.DIRECTIONS) choices.add(move(direction));
                Game.Pos here = pos(unit.get("pos"));
                for (Map<String, Object> enemy : rows(obs.get("enemies"))) {
                    Game.Pos target = pos(enemy.get("pos")); if (here.distance(target) == 1) choices.add(attack(target));
                }
                result.put(number(unit.get("id"), 0), choices.get(random.nextInt(choices.size())));
            }
            return result;
        }
    }

    public static class GreedyAgent extends PathAgent {
        @Override public Map<Integer, Map<String, Object>> act(Map<String, Object> obs) {
            Set<Game.Pos> walls = new HashSet<>(knownWalls(obs)), reserved = new HashSet<>();
            Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
            List<Map<String, Object>> units = new ArrayList<>(rows(obs.get("units")));
            units.sort(Comparator.comparingInt(unit -> number(unit.get("id"), 0)));
            for (Map<String, Object> unit : units) {
                Game.Pos start = pos(unit.get("pos")); if (start == null) continue;
                List<Game.Pos> goals = new ArrayList<>(Game.BEACONS); goals.removeAll(reserved);
                Game.Pos target = goals.stream().min(Comparator.comparingInt(start::distance)).orElse(start); reserved.add(target);
                Set<Game.Pos> destinations = beaconGoals(target, walls);
                String direction = path(start, destinations, walls, Collections.emptySet());
                result.put(number(unit.get("id"), 0), direction == null ? waitOrder() : move(direction));
            }
            return result;
        }
    }

    public static final class AggressiveAgent extends GreedyAgent {
        @Override public Map<Integer, Map<String, Object>> act(Map<String, Object> obs) {
            Set<Game.Pos> walls = new HashSet<>(); for (Object value : (List<?>) obs.getOrDefault("known_walls", Collections.emptyList())) walls.add(pos(value));
            Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
            List<Map<String, Object>> enemies = rows(obs.get("enemies"));
            for (Map<String, Object> unit : rows(obs.get("units"))) {
                Game.Pos start = pos(unit.get("pos")); if (start == null) continue;
                int id = number(unit.get("id"), 0), energy = number(unit.get("energy"), 0);
                if (!enemies.isEmpty()) {
                    Map<String, Object> enemy = enemies.stream().min(Comparator.comparingInt(row -> start.distance(pos(row.get("pos"))))).get();
                    Game.Pos target = pos(enemy.get("pos"));
                    if (start.distance(target) == 1 && energy >= 3) { result.put(id, attack(target)); continue; }
                    Set<Game.Pos> approach = new HashSet<>();
                    for (int d = 0; d < 4; d++) { Game.Pos cell = new Game.Pos(target.row + Game.DELTAS[d][0], target.col + Game.DELTAS[d][1]); if (cell.row >= 0 && cell.row < 15 && cell.col >= 0 && cell.col < 15 && !walls.contains(cell)) approach.add(cell); }
                    String direction = path(start, approach, walls, Collections.emptySet());
                    if (direction != null) { result.put(id, move(direction)); continue; }
                }
                Game.Pos beacon = Game.BEACONS.stream().min(Comparator.comparingInt(start::distance)).get();
                String direction = path(start, beaconGoals(beacon, walls), walls, Collections.emptySet());
                result.put(id, direction == null ? waitOrder() : move(direction));
            }
            return result;
        }
    }

    public static final class ChampionAgent extends PathAgent {
        private static final class Memory { Game.Pos pos; int hp, energy, seen; double confidence; }
        private static final class Style { int close, camp, seen; String label = "passive"; }
        private final Map<String, Memory> memories = new HashMap<>();
        private final Map<Integer, Style> styles = new HashMap<>();
        private Set<Game.Pos> walls = new HashSet<>(), knownTiles = new HashSet<>();
        private double beaconValue = 1.0, aggression = 1.0, memoryDecay = 0.86;
        private int retreatHp = 2, scanGain = 24, turn;

        @Override public void initialize(int playerId, Map<String, Object> config) {
            this.playerId = playerId;
            beaconValue = numberDouble(config.get("beacon_value"), 1.0);
            aggression = numberDouble(config.get("aggression"), 1.0);
            memoryDecay = numberDouble(config.get("memory_decay"), 0.86);
            retreatHp = number(config.get("retreat_hp"), 2);
            scanGain = number(config.get("scan_gain"), 24);
        }
        private static double numberDouble(Object value, double fallback) { return value instanceof Number ? ((Number) value).doubleValue() : fallback; }
        private void update(Map<String, Object> obs) {
            turn = number(obs.get("turn"), 0);
            for (Object value : (List<?>) obs.getOrDefault("known_walls", Collections.emptyList())) walls.add(pos(value));
            for (Object value : (List<?>) obs.getOrDefault("known_tiles", Collections.emptyList())) knownTiles.add(pos(value));
            List<Game.Pos> own = new ArrayList<>();
            for (Map<String, Object> unit : rows(obs.get("units"))) if (pos(unit.get("pos")) != null) own.add(pos(unit.get("pos")));
            Set<String> visible = new HashSet<>();
            for (Map<String, Object> enemy : rows(obs.get("enemies"))) {
                int owner = number(enemy.get("player_id"), -1), id = number(enemy.get("id"), -1);
                String key = owner + ":" + id; visible.add(key);
                Game.Pos enemyPos = pos(enemy.get("pos"));
                Memory old = memories.get(key); Style style = styles.computeIfAbsent(owner, ignored -> new Style());
                style.seen++;
                int nearest = own.stream().mapToInt(enemyPos::distance).min().orElse(99);
                if (nearest <= 4) style.close++;
                if (old != null && old.pos.equals(enemyPos) && Game.BEACONS.stream().anyMatch(beacon -> enemyPos.distance(beacon) <= 1)) style.camp++;
                if (style.camp >= 4 && style.camp > style.close) style.label = "beacon-camper";
                else if (style.close >= 2) style.label = "aggressive";
                else style.label = "passive";
                Memory current = new Memory(); current.pos = enemyPos; current.hp = number(enemy.get("hp"), 5);
                current.energy = number(enemy.get("energy"), 10); current.seen = turn; current.confidence = 1.0; memories.put(key, current);
            }
            Iterator<Map.Entry<String, Memory>> iterator = memories.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Memory> entry = iterator.next(); if (visible.contains(entry.getKey())) continue;
                Memory memory = entry.getValue(); memory.confidence *= memoryDecay;
                if (turn - memory.seen > 14 || memory.confidence < 0.08) iterator.remove();
            }
        }
        private Map<Game.Pos, Double> threatMap(List<Game.Pos> own) {
            Map<Game.Pos, Double> threats = new HashMap<>();
            for (Map.Entry<String, Memory> entry : memories.entrySet()) {
                Memory memory = entry.getValue(); String[] key = entry.getKey().split(":");
                Style style = styles.get(Integer.parseInt(key[0]));
                for (int row = Math.max(0, memory.pos.row - 3); row <= Math.min(14, memory.pos.row + 3); row++)
                    for (int col = Math.max(0, memory.pos.col - 3); col <= Math.min(14, memory.pos.col + 3); col++) {
                        Game.Pos cell = new Game.Pos(row, col); int radius = cell.distance(memory.pos); if (radius > 3) continue;
                        double weight = style.label.equals("aggressive") ? 1.3 : style.label.equals("beacon-camper") && Game.BEACONS.stream().anyMatch(b -> b.distance(cell) <= 1) ? 1.2 : 0.65;
                        threats.merge(cell, memory.confidence * weight * 0.45 / (radius + 1), Double::sum);
                    }
                if (style.label.equals("aggressive") && !own.isEmpty()) {
                    Game.Pos target = own.stream().min(Comparator.comparingInt(memory.pos::distance)).get();
                    for (int d = 0; d < 4; d++) {
                        Game.Pos next = new Game.Pos(memory.pos.row + Game.DELTAS[d][0], memory.pos.col + Game.DELTAS[d][1]);
                        if (next.row >= 0 && next.row < 15 && next.col >= 0 && next.col < 15 && !walls.contains(next) && next.distance(target) < memory.pos.distance(target)) threats.merge(next, 5.0 * memory.confidence, Double::sum);
                    }
                }
            }
            return threats;
        }
        private String safestPath(Game.Pos start, Set<Game.Pos> goals, Set<Game.Pos> occupied, Map<Game.Pos, Double> threat) {
            Map<Game.Pos, Double> cost = new HashMap<>(); Map<Game.Pos, String> first = new HashMap<>();
            PriorityQueue<Game.Pos> queue = new PriorityQueue<>(Comparator.comparingDouble(cost::get));
            cost.put(start, 0.0); first.put(start, null); queue.add(start);
            while (!queue.isEmpty()) {
                Game.Pos current = queue.remove(); double currentCost = cost.get(current);
                if (goals.contains(current)) return first.get(current);
                for (int d = 0; d < 4; d++) {
                    Game.Pos next = new Game.Pos(current.row + Game.DELTAS[d][0], current.col + Game.DELTAS[d][1]);
                    if (next.row < 0 || next.row >= 15 || next.col < 0 || next.col >= 15 || walls.contains(next)) continue;
                    double nextCost = currentCost + 1 + (occupied.contains(next) ? 5 : 0) + threat.getOrDefault(next, 0.0);
                    if (nextCost < cost.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        cost.put(next, nextCost); first.put(next, current.equals(start) ? Game.DIRECTIONS[d] : first.get(current)); queue.remove(next); queue.add(next);
                    }
                }
            }
            return null;
        }
        private int scanValue(Game.Pos center) {
            int count = 0;
            for (int row = Math.max(0, center.row - 6); row <= Math.min(14, center.row + 6); row++)
                for (int col = Math.max(0, center.col - 6); col <= Math.min(14, center.col + 6); col++) {
                    Game.Pos cell = new Game.Pos(row, col); if (center.distance(cell) <= 6 && !knownTiles.contains(cell)) count++;
                }
            return count;
        }
        @Override public Map<Integer, Map<String, Object>> act(Map<String, Object> obs) {
            update(obs);
            List<Map<String, Object>> units = rows(obs.get("units")), enemies = rows(obs.get("enemies"));
            List<Map<String, Object>> live = new ArrayList<>(); List<Game.Pos> ownPositions = new ArrayList<>();
            for (Map<String, Object> unit : units) if (pos(unit.get("pos")) != null) { live.add(unit); ownPositions.add(pos(unit.get("pos"))); }
            Map<Game.Pos, Double> threats = threatMap(ownPositions);
            Game.Pos base = pos(obs.get("base"));
            boolean baseThreat = enemies.stream().anyMatch(e -> pos(e.get("pos")).distance(base) <= 5) || memories.values().stream().anyMatch(m -> m.confidence >= 0.35 && m.pos.distance(base) <= 5);
            Integer guardId = null;
            if (baseThreat && !live.isEmpty()) guardId = live.stream().min(Comparator.comparingInt((Map<String, Object> u) -> pos(u.get("pos")).distance(base)).thenComparingInt(u -> -number(u.get("hp"), 5))).map(u -> number(u.get("id"), 0)).get();
            final Integer selectedGuard = guardId;

            Map<Integer, Integer> assigned = assign(live, guardId, threats);
            Map<Integer, Map<String, Object>> result = new LinkedHashMap<>(); Set<Integer> usedAttackers = new HashSet<>();
            List<Map<String, Object>> sortedEnemies = new ArrayList<>(enemies); sortedEnemies.sort(Comparator.comparingInt(e -> number(e.get("hp"), 5)));
            for (Map<String, Object> enemy : sortedEnemies) {
                Game.Pos enemyPos = pos(enemy.get("pos")); List<Map<String, Object>> adjacent = new ArrayList<>();
                for (Map<String, Object> unit : live) if (!usedAttackers.contains(number(unit.get("id"), 0)) && number(unit.get("energy"), 0) >= 3 && pos(unit.get("pos")).distance(enemyPos) == 1) adjacent.add(unit);
                int needed = (number(enemy.get("hp"), 5) + 1) / 2;
                if (!adjacent.isEmpty() && (number(enemy.get("hp"), 5) <= 2 || adjacent.size() >= needed)) {
                    adjacent.sort(Comparator.comparingInt(u -> -number(u.get("hp"), 5)));
                    for (int i = 0; i < Math.min(needed, adjacent.size()); i++) { int id = number(adjacent.get(i).get("id"), 0); result.put(id, attack(enemyPos)); usedAttackers.add(id); }
                }
            }
            Set<Game.Pos> occupied = new HashSet<>(ownPositions), reserved = new HashSet<>();
            List<Map<String, Object>> ordered = new ArrayList<>(live);
            ordered.sort(Comparator.comparingInt((Map<String, Object> u) -> Objects.equals(number(u.get("id"), 0), selectedGuard) ? 0 : 1).thenComparingInt(u -> number(u.get("id"), 0)));
            for (Map<String, Object> unit : ordered) {
                int id = number(unit.get("id"), 0); if (result.containsKey(id)) continue;
                Game.Pos start = pos(unit.get("pos")); Set<Game.Pos> goals = new HashSet<>();
                if (number(unit.get("hp"), 5) <= retreatHp) {
                    for (int row = Math.max(0, base.row - 1); row <= Math.min(14, base.row + 1); row++) for (int col = Math.max(0, base.col - 1); col <= Math.min(14, base.col + 1); col++) { Game.Pos cell = new Game.Pos(row, col); if (!walls.contains(cell)) goals.add(cell); }
                } else if (Objects.equals(id, selectedGuard)) goals.add(base);
                else {
                    Game.Pos beacon = Game.BEACONS.get(assigned.getOrDefault(id, 4)); goals.addAll(beaconGoals(beacon, walls));
                }
                Set<Game.Pos> avoid = new HashSet<>(occupied); avoid.remove(start); avoid.addAll(reserved);
                String direction = safestPath(start, goals, avoid, threats);
                if (direction == null) result.put(id, waitOrder());
                else {
                    result.put(id, move(direction)); int d = Arrays.asList(Game.DIRECTIONS).indexOf(direction);
                    reserved.add(new Game.Pos(start.row + Game.DELTAS[d][0], start.col + Game.DELTAS[d][1]));
                }
            }
            if (turn < 175 && enemies.isEmpty()) {
                Map<String, Object> scanner = live.stream().filter(u -> number(u.get("energy"), 0) >= 6 && "WAIT".equals(result.getOrDefault(number(u.get("id"), 0), waitOrder()).get("type")))
                        .max(Comparator.comparingInt(u -> scanValue(pos(u.get("pos"))))).orElse(null);
                if (scanner != null && scanValue(pos(scanner.get("pos"))) >= scanGain) result.put(number(scanner.get("id"), 0), Collections.singletonMap("type", "SCAN"));
            }
            return result;
        }
        private Map<Integer, Integer> assign(List<Map<String, Object>> live, Integer guardId, Map<Game.Pos, Double> threats) {
            class Pair { int unit, beacon; double score; Pair(int u, int b, double s) { unit=u; beacon=b; score=s; } }
            List<Pair> pairs = new ArrayList<>();
            for (Map<String, Object> unit : live) {
                int id = number(unit.get("id"), 0); if (Objects.equals(id, guardId)) continue;
                Game.Pos start = pos(unit.get("pos"));
                for (int b = 0; b < Game.BEACONS.size(); b++) {
                    Game.Pos beacon = Game.BEACONS.get(b); String direction = safestPath(start, beaconGoals(beacon, walls), Collections.emptySet(), threats);
                    double route = direction == null ? start.distance(beacon) + 20 : start.distance(beacon);
                    double value = beaconValue * (1.5 / (beacon.distance(new Game.Pos(7, 7)) + 1.0));
                    pairs.add(new Pair(id, b, route - value));
                }
            }
            pairs.sort(Comparator.comparingDouble(pair -> pair.score));
            Map<Integer, Integer> result = new HashMap<>(); Set<Integer> taken = new HashSet<>();
            for (Pair pair : pairs) if (!result.containsKey(pair.unit) && taken.add(pair.beacon)) result.put(pair.unit, pair.beacon);
            return result;
        }
    }
}
