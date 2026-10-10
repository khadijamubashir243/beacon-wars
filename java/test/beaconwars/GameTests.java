package beaconwars;

import java.util.*;

public final class GameTests {
    private static Map<String, Object> move(String direction) {
        Map<String, Object> order = new HashMap<>(); order.put("type", "MOVE"); order.put("dir", direction); return order;
    }
    private static Map<String, Object> attack(int row, int col) {
        Map<String, Object> order = new HashMap<>(); order.put("type", "ATTACK"); order.put("target", Arrays.asList(row, col)); return order;
    }
    private static Map<String, Object> scan() { return Collections.singletonMap("type", "SCAN"); }
    private static Map<Integer, Map<Integer, Map<String, Object>>> orders(Object... entries) {
        Map<Integer, Map<Integer, Map<String, Object>>> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((Integer) entries[i], (Map<Integer, Map<String, Object>>) entries[i + 1]);
        return result;
    }
    private static Map<Integer, Map<String, Object>> player(Object... entries) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((Integer) entries[i], (Map<String, Object>) entries[i + 1]);
        return result;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void main(String[] args) {
        sameDestinationBounce();
        swapAndMutualKills();
        contestedBeacon();
        attackAfterMovement();
        scanVisibility();
        System.out.println("All BEACON WARS resolution tests passed.");
    }
    private static void sameDestinationBounce() {
        Game game = new Game(17, 2);
        Game.Unit left = game.getUnits().get(0).get(0), right = game.getUnits().get(1).get(0);
        left.pos = new Game.Pos(7, 6); right.pos = new Game.Pos(7, 8);
        game.step(orders(0, player(0, move("right")), 1, player(0, move("left"))));
        check(left.pos.equals(new Game.Pos(7, 6)) && right.pos.equals(new Game.Pos(7, 8)), "collision should bounce both");
        check(left.energy == 10 && right.energy == 10, "bounced moves still regenerate after paying cost");
    }
    private static void swapAndMutualKills() {
        Game game = new Game(17, 2);
        Game.Unit left = game.getUnits().get(0).get(0), right = game.getUnits().get(1).get(0);
        left.pos = new Game.Pos(7, 6); right.pos = new Game.Pos(7, 7);
        game.step(orders(0, player(0, move("right")), 1, player(0, move("left"))));
        check(left.pos.equals(new Game.Pos(7, 6)) && right.pos.equals(new Game.Pos(7, 7)), "swapping units should bounce");
        left.pos = new Game.Pos(7, 6); right.pos = new Game.Pos(7, 7); left.hp = right.hp = 2; left.energy = right.energy = 10;
        game.step(orders(0, player(0, attack(7, 7)), 1, player(0, attack(7, 6))));
        check(left.pos == null && right.pos == null, "simultaneous attacks should permit mutual kills");
        check(game.getKills()[0] == 1 && game.getKills()[1] == 1, "both kills should count");
    }
    private static void contestedBeacon() {
        Game game = new Game(17, 2); Game.Pos beacon = new Game.Pos(7, 7);
        game.getUnits().get(0).get(0).pos = new Game.Pos(7, 6);
        game.getUnits().get(1).get(0).pos = new Game.Pos(7, 8);
        game.step(Collections.emptyMap());
        check(game.getScores()[0] == 0 && game.getScores()[1] == 0, "contested beacon scores nobody");
    }
    private static void attackAfterMovement() {
        Game game = new Game(18, 2);
        Game.Unit attacker = game.getUnits().get(0).get(0), target = game.getUnits().get(1).get(0);
        attacker.pos = new Game.Pos(7, 6); target.pos = new Game.Pos(7, 8); target.hp = 2;
        game.step(orders(0, player(0, attack(7, 7)), 1, player(0, move("left"))));
        check(target.pos == null && game.getKills()[0] == 1, "attack must resolve against post-move position");
    }
    private static void scanVisibility() {
        Game game = new Game(19, 2);
        for (Game.Unit unit : game.getUnits().get(0)) unit.pos = new Game.Pos(0, 0);
        game.getUnits().get(1).get(0).pos = new Game.Pos(0, 5);
        for (int i = 1; i < game.getUnits().get(1).size(); i++) game.getUnits().get(1).get(i).pos = new Game.Pos(14, 14);
        check(((List<?>) game.observations().get(0).get("enemies")).isEmpty(), "enemy starts outside normal vision");
        Map<Integer, Map<String, Object>> obs = game.step(orders(0, player(0, scan()), 1, Collections.emptyMap()));
        check(((List<?>) obs.get(0).get("enemies")).size() == 1, "scan should reveal enemy for this turn");
    }
}
