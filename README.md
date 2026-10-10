# BEACON WARS

Java 11+ implementation using only the JDK. The game engine, four agents, tournament runner, JSON replay support, tests, and self-play tuner are under `java/`.

## Build and Run

From the `java` directory:

```powershell
javac -d out src/beaconwars/*.java test/beaconwars/GameTests.java
java -cp out beaconwars.GameTests
java -cp out beaconwars.Arena --match --agents champion greedy --seed 7 --replay match.json
java -cp out beaconwars.Arena --agents champion random greedy aggressive --seeds 10 --output tournament.json --replay-dir replays
java -cp out beaconwars.SelfPlay --trials 8 --seeds 3
```

The arena enforces a one-second agent deadline. A timeout or exception becomes WAIT. Tournaments run each seat permutation for each selected seed. Match ranking is score, then kills, then remaining energy; leaderboard win rates and Elo use the same tiebreak.

## Rules

- Matches have 2-4 players on a 15x15 board, with five units each, nine beacons, and a 200-turn limit.
- Maps are seeded, fourfold rotationally symmetric, and approximately 15% walls. For three players the omitted corner rotates across seeds.
- Units have 5 HP and 10 energy. MOVE costs 1, ATTACK costs 3, SCAN costs 2; energy regenerates by 1 each turn or 3 on the base tile.
- Normal vision has Manhattan radius 3; SCAN reveals radius 6 for that turn. Discovered walls and explored cells remain in observations.
- Same-destination moves and swaps bounce. Attacks resolve after movement and damage simultaneously. Kills award 2 points; the player dealing the most damage gets credit, with ties awarded to the lower player id.
- A player scores 1 point per beacon per turn if their units are within distance 1 and no opponent is. Respawns occur after three turns with full HP and 5 energy.

The specification defines no HP healing. Retreating a damaged unit can preserve it and recharge energy at base, but HP recovery requires death and respawn.

## Submit an Agent

Implement `beaconwars.Agent`:

```java
package myagent;

import beaconwars.Agent;
import java.util.Map;

public class MyAgent implements Agent {
	@Override
	public void initialize(int playerId, Map<String, Object> config) { }

	@Override
	public Map<Integer, Map<String, Object>> act(Map<String, Object> observation) {
		return Map.of(); // Missing orders are WAIT.
	}
}
```

Orders use `type` `MOVE`, `ATTACK`, `SCAN`, or `WAIT`. MOVE includes `dir` (`up`, `down`, `left`, `right`); ATTACK includes `target` as `[row, column]`. Compile the custom class onto the runtime classpath and pass `custom=myagent.MyAgent` after `--agents`. Observations include own units, visible enemies and beacons, remembered walls/tiles, scores, and the player's base.

`beaconwars.Game` provides `reset()`, `step(...)`, `observations()`, `render()`, and `replayJson()`. The focused tests cover movement bounces, mutual kills, post-move attacks, scan visibility, and contested beacons.

## Included Agents

The champion remembers enemy sightings with decaying confidence, classifies opponent behavior, assigns units to distinct beacons, routes around known walls and ally traffic, focuses lethal attacks, guards a threatened base, retreats low-HP units, and scans when it can reveal enough unexplored map. The random, greedy, and aggressive agents provide baselines. The tuner searches beacon value, aggression, retreat threshold, scan gain, and memory decay against baselines and archived configs. Win-rate and timing goals are empirical and should be measured against the intended opponents.
