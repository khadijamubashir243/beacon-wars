package beaconwars;

import java.util.*;

/** Deterministic simultaneous-turn BEACON WARS environment. */
public final class Game {
    public static final int SIZE = 15, MAX_TURNS = 200, MAX_HP = 5, MAX_ENERGY = 10;
    public static final String[] DIRECTIONS = {"up", "down", "left", "right"};
    public static final int[][] DELTAS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    public static final List<Pos> BEACONS;
    private static final List<Pos> CORNERS = Arrays.asList(new Pos(0, 0), new Pos(0, 14), new Pos(14, 14), new Pos(14, 0));
    static {
        List<Pos> beacons = new ArrayList<>();
        for (int row = 6; row <= 8; row++) for (int col = 6; col <= 8; col++) beacons.add(new Pos(row, col));
        BEACONS = Collections.unmodifiableList(beacons);
    }

    public static final class Pos {
        public final int row, col;
        public Pos(int row, int col) { this.row = row; this.col = col; }
        public int distance(Pos other) { return Math.abs(row - other.row) + Math.abs(col - other.col); }
        public List<Integer> json() { return Arrays.asList(row, col); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Pos)) return false;
            Pos pos = (Pos) other;
            return row == pos.row && col == pos.col;
        }
        @Override public int hashCode() { return Objects.hash(row, col); }
        @Override public String toString() { return row + "," + col; }
    }

    public static final class Unit {
        public final int id;
        public Pos pos;
        public int hp = MAX_HP, energy = MAX_ENERGY;
        public Integer respawnAt;
        Unit(int id, Pos pos) { this.id = id; this.pos = pos; }
    }

    private static final class Ref {
        final int owner, id;
        Ref(int owner, int id) { this.owner = owner; this.id = id; }
        @Override public boolean equals(Object other) { return other instanceof Ref && owner == ((Ref) other).owner && id == ((Ref) other).id; }
        @Override public int hashCode() { return Objects.hash(owner, id); }
        @Override public int compareTo(Object other) {
            Ref ref = (Ref) other;
            return owner == ref.owner ? Integer.compare(id, ref.id) : Integer.compare(owner, ref.owner);
        }
    }

    private final int seed, playerCount;
    private Random random;
    private int turn;
    private int[] scores, kills;
    private List<Pos> bases;
    private Set<Pos> walls;
    private List<Set<Pos>> knownWalls, knownTiles, scanCells;
    private List<List<Unit>> units;
    private final List<Map<String, Object>> replayTurns = new ArrayList<>();

    public Game(int seed, int playerCount) {
        if (playerCount < 2 || playerCount > 4) throw new IllegalArgumentException("players must be 2-4");
        this.seed = seed;
        this.playerCount = playerCount;
        reset();
    }

    public Map<Integer, Map<String, Object>> reset() {
        random = new Random(seed);
        turn = 0;
        scores = new int[playerCount];
        kills = new int[playerCount];
        bases = makeBases();
        walls = makeWalls();
        knownWalls = new ArrayList<>(); knownTiles = new ArrayList<>(); scanCells = new ArrayList<>();
        units = new ArrayList<>();
        for (int player = 0; player < playerCount; player++) {
            knownWalls.add(new HashSet<>()); knownTiles.add(new HashSet<>()); scanCells.add(new HashSet<>());
            List<Unit> group = new ArrayList<>();
            List<Pos> area = startingArea(bases.get(player));
            for (int id = 0; id < 5; id++) group.add(new Unit(id, area.get(id)));
            units.add(group);
        }
        replayTurns.clear();
        return observations();
    }

    private static Pos rotate(Pos pos, int times) {
        int row = pos.row, col = pos.col;
        for (int i = 0; i < times % 4; i++) { int next = col; col = SIZE - 1 - row; row = next; }
        return new Pos(row, col);
    }

    private List<Pos> makeBases() {
        List<Pos> corners = new ArrayList<>();
        for (Pos corner : CORNERS) corners.add(rotate(corner, Math.floorMod(seed, 4)));
        List<Pos> result = new ArrayList<>();
        if (playerCount == 2) { result.add(corners.get(0)); result.add(corners.get(2)); }
        else if (playerCount == 3) {
            int missing = Math.floorMod(seed / 4, 4);
            for (int i = 0; i < 4; i++) if (i != missing) result.add(corners.get(i));
        } else result.addAll(corners);
        return result;
    }

    private List<Pos> startingArea(Pos base) {
        int rowSign = base.row == 0 ? 1 : -1, colSign = base.col == 0 ? 1 : -1;
        List<Pos> area = new ArrayList<>();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++)
            area.add(new Pos(base.row + rowSign * row, base.col + colSign * col));
        area.sort(Comparator.comparingInt((Pos pos) -> pos.distance(base)).thenComparingInt(pos -> pos.row).thenComparingInt(pos -> pos.col));
        return area;
    }

    private Set<Pos> makeWalls() {
        Set<Pos> protectedCells = new HashSet<>(BEACONS);
        for (Pos base : bases) protectedCells.addAll(startingArea(base));
        Set<Pos> visited = new HashSet<>();
        List<Set<Pos>> orbits = new ArrayList<>();
        for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++) {
            Pos pos = new Pos(row, col);
            if (visited.contains(pos)) continue;
            Set<Pos> orbit = new HashSet<>();
            for (int rotation = 0; rotation < 4; rotation++) orbit.add(rotate(pos, rotation));
            visited.addAll(orbit);
            if (Collections.disjoint(orbit, protectedCells)) orbits.add(orbit);
        }
        Collections.shuffle(orbits, random);
        Set<Pos> result = new HashSet<>();
        for (Set<Pos> orbit : orbits) {
            if (result.size() >= Math.round(SIZE * SIZE * 0.15)) break;
            result.addAll(orbit);
        }
        return result;
    }

    private static Map<String, Object> order(String type) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("type", type); return result;
    }

    private Map<String, Object> normalize(Object candidate) {
        if (!(candidate instanceof Map<?, ?>)) return order("WAIT");
        Map<?, ?> input = (Map<?, ?>) candidate;
        Object rawType = input.get("type");
        if (!(rawType instanceof String)) return order("WAIT");
        String type = (String) rawType;
        if (type.equals("WAIT") || type.equals("SCAN")) return order(type);
        if (type.equals("MOVE") && input.get("dir") instanceof String && Arrays.asList(DIRECTIONS).contains(input.get("dir"))) {
            Map<String, Object> result = order(type); result.put("dir", input.get("dir")); return result;
        }
        if (type.equals("ATTACK") && input.get("target") instanceof List<?>) {
            List<?> target = (List<?>) input.get("target");
            if (target.size() == 2 && target.get(0) instanceof Number && target.get(1) instanceof Number) {
                int row = ((Number) target.get(0)).intValue(), col = ((Number) target.get(1)).intValue();
                if (row >= 0 && row < SIZE && col >= 0 && col < SIZE) {
                    Map<String, Object> result = order(type); result.put("target", Arrays.asList(row, col)); return result;
                }
            }
        }
        return order("WAIT");
    }

    public Map<Integer, Map<String, Object>> step(Map<Integer, Map<Integer, Map<String, Object>>> ordersByPlayer) {
        if (turn >= MAX_TURNS) throw new IllegalStateException("match is complete");
        Map<Ref, Map<String, Object>> orders = new LinkedHashMap<>();
        for (int owner = 0; owner < playerCount; owner++) {
            Map<Integer, Map<String, Object>> playerOrders = ordersByPlayer.getOrDefault(owner, Collections.emptyMap());
            for (Unit unit : units.get(owner)) orders.put(new Ref(owner, unit.id), normalize(playerOrders.get(unit.id)));
        }

        List<Set<Pos>> scans = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) scans.add(new HashSet<>());
        for (Map.Entry<Ref, Map<String, Object>> entry : orders.entrySet()) {
            Ref ref = entry.getKey(); Unit unit = units.get(ref.owner).get(ref.id);
            if (unit.pos == null || !"SCAN".equals(entry.getValue().get("type")) || unit.energy < 2) continue;
            unit.energy -= 2;
            for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++) {
                Pos cell = new Pos(row, col); if (unit.pos.distance(cell) <= 6) scans.get(ref.owner).add(cell);
            }
        }

        Map<Ref, Pos> starts = new LinkedHashMap<>(), proposals = new LinkedHashMap<>();
        Set<Ref> blocked = new HashSet<>();
        for (int owner = 0; owner < playerCount; owner++) for (Unit unit : units.get(owner)) starts.put(new Ref(owner, unit.id), unit.pos);
        for (Map.Entry<Ref, Map<String, Object>> entry : orders.entrySet()) {
            Ref ref = entry.getKey(); Unit unit = units.get(ref.owner).get(ref.id);
            if (unit.pos == null || !"MOVE".equals(entry.getValue().get("type")) || unit.energy < 1) continue;
            unit.energy--;
            int direction = Arrays.asList(DIRECTIONS).indexOf(entry.getValue().get("dir"));
            Pos destination = new Pos(unit.pos.row + DELTAS[direction][0], unit.pos.col + DELTAS[direction][1]);
            if (inBounds(destination) && !walls.contains(destination)) proposals.put(ref, destination);
        }
        Map<Pos, List<Ref>> byDestination = new HashMap<>();
        for (Map.Entry<Ref, Pos> entry : proposals.entrySet()) byDestination.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        for (List<Ref> refs : byDestination.values()) if (refs.size() > 1) blocked.addAll(refs);
        List<Ref> movers = new ArrayList<>(proposals.keySet());
        for (int i = 0; i < movers.size(); i++) for (int j = i + 1; j < movers.size(); j++) {
            Ref left = movers.get(i), right = movers.get(j);
            if (Objects.equals(proposals.get(left), starts.get(right)) && Objects.equals(proposals.get(right), starts.get(left))) {
                blocked.add(left); blocked.add(right);
            }
        }
        for (Map.Entry<Ref, Pos> entry : proposals.entrySet()) if (!blocked.contains(entry.getKey()))
            units.get(entry.getKey().owner).get(entry.getKey().id).pos = entry.getValue();

        Map<Ref, List<Pos>> attacks = new LinkedHashMap<>();
        for (Map.Entry<Ref, Map<String, Object>> entry : orders.entrySet()) {
            Ref ref = entry.getKey(); Unit unit = units.get(ref.owner).get(ref.id);
            if (unit.pos == null || !"ATTACK".equals(entry.getValue().get("type")) || unit.energy < 3) continue;
            List<?> rawTarget = (List<?>) entry.getValue().get("target");
            Pos target = new Pos(((Number) rawTarget.get(0)).intValue(), ((Number) rawTarget.get(1)).intValue());
            if (unit.pos.distance(target) != 1) continue;
            unit.energy -= 3;
            attacks.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(target);
        }
        Map<Ref, Map<Integer, Integer>> damage = new LinkedHashMap<>();
        for (Map.Entry<Ref, List<Pos>> attack : attacks.entrySet()) {
            for (Pos target : attack.getValue()) for (int enemy = 0; enemy < playerCount; enemy++) {
                if (enemy == attack.getKey().owner) continue;
                for (Unit unit : units.get(enemy)) if (target.equals(unit.pos)) {
                    Ref targetRef = new Ref(enemy, unit.id);
                    damage.computeIfAbsent(targetRef, ignored -> new HashMap<>()).merge(attack.getKey().owner, 2, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> deaths = new ArrayList<>();
        for (Map.Entry<Ref, Map<Integer, Integer>> entry : damage.entrySet()) {
            Ref targetRef = entry.getKey(); Unit unit = units.get(targetRef.owner).get(targetRef.id);
            unit.hp -= entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
            if (unit.hp <= 0 && unit.pos != null) {
                int killer = entry.getValue().entrySet().stream().sorted((a, b) -> {
                    int comparison = Integer.compare(b.getValue(), a.getValue());
                    return comparison != 0 ? comparison : Integer.compare(a.getKey(), b.getKey());
                }).findFirst().get().getKey();
                scores[killer] += 2; kills[killer]++;
                Map<String, Object> death = new LinkedHashMap<>(); death.put("player_id", targetRef.owner); death.put("unit_id", targetRef.id); death.put("killer", killer); deaths.add(death);
                unit.pos = null; unit.respawnAt = turn + 3; unit.energy = 0;
            }
        }
        for (int owner = 0; owner < playerCount; owner++) {
            knownWalls.get(owner).addAll(intersection(scans.get(owner), walls));
            scanCells.set(owner, scans.get(owner));
        }
        int[] beaconPoints = new int[playerCount];
        for (Pos beacon : BEACONS) {
            Set<Integer> occupants = new HashSet<>();
            for (int owner = 0; owner < playerCount; owner++) for (Unit unit : units.get(owner))
                if (unit.pos != null && unit.pos.distance(beacon) <= 1) occupants.add(owner);
            if (occupants.size() == 1) { int owner = occupants.iterator().next(); scores[owner]++; beaconPoints[owner]++; }
        }
        for (int owner = 0; owner < playerCount; owner++) for (Unit unit : units.get(owner)) {
            if (unit.pos == null) {
                if (unit.respawnAt != null && unit.respawnAt <= turn + 1) { unit.pos = bases.get(owner); unit.hp = MAX_HP; unit.energy = 5; unit.respawnAt = null; }
            } else unit.energy = Math.min(MAX_ENERGY, unit.energy + (unit.pos.equals(bases.get(owner)) ? 3 : 1));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("turn", turn); record.put("orders", ordersJson(orders)); record.put("deaths", deaths);
        record.put("beacon_points", beaconPoints); record.put("scores", intList(scores)); record.put("kills", intList(kills)); record.put("units", unitsJson());
        replayTurns.add(record);
        turn++;
        return observations();
    }

    private static Set<Pos> intersection(Set<Pos> left, Set<Pos> right) { Set<Pos> result = new HashSet<>(left); result.retainAll(right); return result; }
    private static boolean inBounds(Pos pos) { return pos.row >= 0 && pos.row < SIZE && pos.col >= 0 && pos.col < SIZE; }
    private static List<Integer> intList(int[] values) { List<Integer> list = new ArrayList<>(); for (int value : values) list.add(value); return list; }
    private Map<String, Object> ordersJson(Map<Ref, Map<String, Object>> orders) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int player = 0; player < playerCount; player++) {
            Map<String, Object> playerOrders = new LinkedHashMap<>();
            for (Map.Entry<Ref, Map<String, Object>> entry : orders.entrySet()) if (entry.getKey().owner == player) playerOrders.put(String.valueOf(entry.getKey().id), entry.getValue());
            result.put(String.valueOf(player), playerOrders);
        }
        return result;
    }
    private List<Object> unitsJson() {
        List<Object> result = new ArrayList<>();
        for (int player = 0; player < playerCount; player++) for (Unit unit : units.get(player)) {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("player_id", player); row.put("id", unit.id);
            row.put("pos", unit.pos == null ? null : unit.pos.json()); row.put("hp", unit.hp); row.put("energy", unit.energy); row.put("respawn_at", unit.respawnAt); result.add(row);
        }
        return result;
    }

    public Map<Integer, Map<String, Object>> observations() {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (int player = 0; player < playerCount; player++) {
            Set<Pos> visible = new HashSet<>();
            for (Unit unit : units.get(player)) if (unit.pos != null)
                for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++) {
                    Pos cell = new Pos(row, col); if (unit.pos.distance(cell) <= 3) visible.add(cell);
                }
            visible.addAll(scanCells.get(player));
            knownTiles.get(player).addAll(visible);
            knownWalls.get(player).addAll(intersection(visible, walls));
            List<Object> ownUnits = new ArrayList<>(), enemies = new ArrayList<>(), beacons = new ArrayList<>();
            for (Unit unit : units.get(player)) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("id", unit.id); row.put("pos", unit.pos == null ? null : unit.pos.json());
                row.put("hp", unit.hp); row.put("energy", unit.energy); row.put("respawn_in", unit.respawnAt == null ? 0 : Math.max(0, unit.respawnAt - turn)); ownUnits.add(row);
            }
            for (int other = 0; other < playerCount; other++) if (other != player) for (Unit unit : units.get(other)) if (unit.pos != null && visible.contains(unit.pos)) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("player_id", other); row.put("id", unit.id); row.put("pos", unit.pos.json()); row.put("hp", unit.hp); row.put("energy", unit.energy); enemies.add(row);
            }
            for (int id = 0; id < BEACONS.size(); id++) {
                Pos beacon = BEACONS.get(id); if (!visible.contains(beacon)) continue;
                Map<Integer, Integer> occupancy = new LinkedHashMap<>();
                for (int owner = 0; owner < playerCount; owner++) for (Unit unit : units.get(owner)) if (unit.pos != null && unit.pos.distance(beacon) <= 1) occupancy.merge(owner, 1, Integer::sum);
                Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); row.put("pos", beacon.json());
                row.put("controlled_by", occupancy.size() == 1 ? occupancy.keySet().iterator().next() : null);
                row.put("contested", occupancy.size() > 1); row.put("occupants", occupancy); beacons.add(row);
            }
            Map<String, Object> observation = new LinkedHashMap<>(); observation.put("turn", turn); observation.put("player_id", player);
            observation.put("units", ownUnits); observation.put("enemies", enemies); observation.put("beacons", beacons);
            observation.put("known_walls", posLists(knownWalls.get(player))); observation.put("known_tiles", posLists(knownTiles.get(player)));
            observation.put("visible_tiles", posLists(visible)); observation.put("scores", intList(scores)); observation.put("kills", intList(kills));
            observation.put("base", bases.get(player).json()); result.put(player, observation);
        }
        return result;
    }

    private static List<Object> posLists(Collection<Pos> positions) {
        List<Pos> sorted = new ArrayList<>(positions); sorted.sort(Comparator.comparingInt((Pos pos) -> pos.row).thenComparingInt(pos -> pos.col));
        List<Object> result = new ArrayList<>(); for (Pos pos : sorted) result.add(pos.json()); return result;
    }
    public int getTurn() { return turn; }
    public int getPlayerCount() { return playerCount; }
    public int[] getScores() { return scores.clone(); }
    public int[] getKills() { return kills.clone(); }
    public List<List<Unit>> getUnits() { return units; }
    public List<Pos> getBases() { return bases; }
    public Set<Pos> getWalls() { return Collections.unmodifiableSet(walls); }
    public String render() {
        char[][] grid = new char[SIZE][SIZE]; for (char[] row : grid) Arrays.fill(row, '.');
        for (Pos wall : walls) grid[wall.row][wall.col] = '#';
        for (Pos beacon : BEACONS) grid[beacon.row][beacon.col] = '*';
        for (int player = 0; player < playerCount; player++) grid[bases.get(player).row][bases.get(player).col] = (char) ('0' + player);
        for (int player = 0; player < playerCount; player++) for (Unit unit : units.get(player)) if (unit.pos != null) grid[unit.pos.row][unit.pos.col] = (char) ('0' + player);
        StringBuilder out = new StringBuilder(); for (char[] row : grid) out.append(row).append('\n'); return out.toString();
    }
    public String replayJson() {
        Map<String, Object> map = new LinkedHashMap<>(); map.put("seed", seed); map.put("n_players", playerCount);
        Map<String, Object> setup = new LinkedHashMap<>(); setup.put("size", SIZE); setup.put("walls", posLists(walls)); setup.put("beacons", posLists(BEACONS)); setup.put("bases", posLists(bases));
        map.put("map", setup); map.put("turns", replayTurns); return Json.stringify(map);
    }
}
