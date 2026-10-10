package beaconwars;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Timed matches, replay output, round-robin seat swaps, and Elo leaderboard. */
public final class Arena {
    private Arena() {}
    static final class Entry { final String name; final String className; Entry(String n, String c) { name=n; className=c; } }
    static final class Match {
        List<String> players; List<Integer> scores, kills, energy; int turns; int winner;
        Map<String, Integer> timeouts = new LinkedHashMap<>(), errors = new LinkedHashMap<>();
        Map<String, Object> json() {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("players", players); row.put("scores", scores); row.put("kills", kills); row.put("remaining_energy", energy); row.put("turns", turns); row.put("winner", winner < 0 ? null : winner); row.put("timeouts", timeouts); row.put("errors", errors); return row;
        }
    }
    private static final class Runner {
        final Entry entry; final Agent agent; final long timeoutMillis; boolean busy; int timeouts, errors;
        Runner(Entry entry, int playerId, Map<String, Object> config, long timeoutMillis) throws Exception {
            this.entry=entry; this.timeoutMillis=Math.min(Math.max(timeoutMillis, 0), 1000);
            if (entry.className == null) agent = Agents.create(entry.name);
            else agent = (Agent) Class.forName(entry.className).getDeclaredConstructor().newInstance();
            agent.initialize(playerId, config);
        }
        synchronized Map<Integer, Map<String, Object>> act(Map<String, Object> observation) {
            if (busy) { timeouts++; return Collections.emptyMap(); }
            busy = true;
            final Object[] output = new Object[1]; final Throwable[] failure = new Throwable[1];
            Thread worker = new Thread(() -> {
                try { output[0] = agent.act(observation); }
                catch (Throwable throwable) { failure[0] = throwable; }
                finally { synchronized (Runner.this) { busy=false; Runner.this.notifyAll(); } }
            }, "agent-" + entry.name);
            worker.setDaemon(true); worker.start();
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            while (busy) {
                long remaining = deadline - System.nanoTime(); if (remaining <= 0) break;
                try { wait(Math.max(1, remaining / 1_000_000L)); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
            }
            if (busy) { timeouts++; return Collections.emptyMap(); }
            if (failure[0] != null) { errors++; return Collections.emptyMap(); }
            if (output[0] instanceof Map<?, ?>) return (Map<Integer, Map<String, Object>>) output[0];
            return Collections.emptyMap();
        }
    }
    private static final class Stats { double points; long score, matches; double elo=1000; }

    public static Match runMatch(List<Entry> entries, int seed, Map<String, Object> configs, long timeoutMillis, Path replay, boolean includeReplay) throws Exception {
        if (entries.size() < 2 || entries.size() > 4) throw new IllegalArgumentException("match requires 2-4 agents");
        Game game = new Game(seed, entries.size()); List<Runner> runners = new ArrayList<>();
        for (int seat=0; seat<entries.size(); seat++) {
            Entry entry=entries.get(seat); Object raw=configs.get(entry.name);
            Map<String,Object> config=raw instanceof Map<?,?> ? new LinkedHashMap<>((Map<String,Object>)raw) : new LinkedHashMap<>();
            config.put("seed", seed); runners.add(new Runner(entry, seat, config, timeoutMillis));
        }
        Map<Integer, Map<String,Object>> observations=game.observations();
        while (game.getTurn() < Game.MAX_TURNS) {
            Map<Integer, Map<Integer, Map<String,Object>>> orders = new LinkedHashMap<>();
            for (int seat=0; seat<runners.size(); seat++) orders.put(seat, runners.get(seat).act(observations.get(seat)));
            observations=game.step(orders);
        }
        if (replay != null) { Files.createDirectories(replay.toAbsolutePath().getParent()); Files.writeString(replay, game.replayJson()); }
        Match result=new Match(); result.players=new ArrayList<>(); result.scores=new ArrayList<>(); result.kills=new ArrayList<>(); result.energy=new ArrayList<>(); result.turns=game.getTurn();
        int[] scores=game.getScores(), kills=game.getKills(); int best=-1;
        for (int seat=0;seat<entries.size();seat++) {
            Entry entry=entries.get(seat); result.players.add(entry.name); result.scores.add(scores[seat]); result.kills.add(kills[seat]);
            int energy=game.getUnits().get(seat).stream().mapToInt(unit -> unit.energy).sum(); result.energy.add(energy);
            Runner runner=runners.get(seat); result.timeouts.put(entry.name, runner.timeouts); result.errors.put(entry.name, runner.errors);
            if (best < 0 || compare(scores[seat], kills[seat], energy, result.scores.get(best), result.kills.get(best), result.energy.get(best)) > 0) best=seat;
        }
        int top=best;
        for (int i=0;i<entries.size();i++) if (compare(scores[i], kills[i], result.energy.get(i), scores[top], kills[top], result.energy.get(top)) == 0 && i != top) { top=-1; break; }
        result.winner=top;
        return result;
    }
    private static int compare(int scoreA,int killsA,int energyA,int scoreB,int killsB,int energyB) {
        if (scoreA != scoreB) return Integer.compare(scoreA,scoreB); if (killsA != killsB) return Integer.compare(killsA,killsB); return Integer.compare(energyA,energyB);
    }
    private static void permutations(List<Entry> items,int index,List<List<Entry>> output) {
        if(index==items.size()){output.add(new ArrayList<>(items));return;}
        for(int i=index;i<items.size();i++){Collections.swap(items,index,i);permutations(items,index+1,output);Collections.swap(items,index,i);}
    }
    public static List<Map<String,Object>> tournament(List<Entry> entrants,int playerCount,List<Integer> seeds,long timeout,Map<String,Object> configs,Path replayDir) throws Exception {
        if(playerCount<2||playerCount>4||entrants.size()<playerCount)throw new IllegalArgumentException("not enough agents for tournament size");
        if(replayDir!=null)Files.createDirectories(replayDir);
        Map<String,Stats> stats=new LinkedHashMap<>();for(Entry entry:entrants)stats.put(entry.name,new Stats());
        List<Match> matches=new ArrayList<>();
        List<List<Entry>> groups=new ArrayList<>(); combinations(entrants,playerCount,0,new ArrayList<>(),groups);
        int matchIndex=0;
        for(List<Entry> group:groups){List<List<Entry>> seats=new ArrayList<>();permutations(group,0,seats);
            for(List<Entry> seating:seats)for(int seed:seeds){
                Path replay=replayDir==null?null:replayDir.resolve(String.format(Locale.ROOT,"match_%05d_seed_%d.json",matchIndex,seed));
                Match match=runMatch(seating,seed,configs,timeout,replay,false);matches.add(match);matchIndex++;
                List<int[]> perf=new ArrayList<>();for(int i=0;i<playerCount;i++)perf.add(new int[]{match.scores.get(i),match.kills.get(i),match.energy.get(i)});
                int maxIndex=0;for(int i=1;i<playerCount;i++)if(compare(perf.get(i)[0],perf.get(i)[1],perf.get(i)[2],perf.get(maxIndex)[0],perf.get(maxIndex)[1],perf.get(maxIndex)[2])>0)maxIndex=i;
                int tied=0;for(int[] value:perf)if(compare(value[0],value[1],value[2],perf.get(maxIndex)[0],perf.get(maxIndex)[1],perf.get(maxIndex)[2])==0)tied++;
                for(int seat=0;seat<playerCount;seat++){Stats stat=stats.get(match.players.get(seat));stat.score+=match.scores.get(seat);stat.matches++;if(compare(perf.get(seat)[0],perf.get(seat)[1],perf.get(seat)[2],perf.get(maxIndex)[0],perf.get(maxIndex)[1],perf.get(maxIndex)[2])==0)stat.points+=1.0/tied;}
                Map<String,Double> old=new HashMap<>(),delta=new HashMap<>();for(Entry entry:seating)old.put(entry.name,stats.get(entry.name).elo);
                for(int a=0;a<playerCount;a++)for(int b=a+1;b<playerCount;b++){
                    String left=match.players.get(a),right=match.players.get(b);double expected=1.0/(1.0+Math.pow(10,(old.get(right)-old.get(left))/400.0));
                    int cmp=compare(perf.get(a)[0],perf.get(a)[1],perf.get(a)[2],perf.get(b)[0],perf.get(b)[1],perf.get(b)[2]);double actual=cmp>0?1:cmp<0?0:0.5;double change=24*(actual-expected);delta.merge(left,change,Double::sum);delta.merge(right,-change,Double::sum);
                }
                for(Map.Entry<String,Double> change:delta.entrySet())stats.get(change.getKey()).elo+=change.getValue();
            }
        }
        List<Map<String,Object>> board=new ArrayList<>();for(Map.Entry<String,Stats> entry:stats.entrySet()){
            Stats stat=entry.getValue();Map<String,Object> row=new LinkedHashMap<>();row.put("agent",entry.getKey());row.put("win_rate",stat.matches==0?0:stat.points/stat.matches);row.put("average_score",stat.matches==0?0:(double)stat.score/stat.matches);row.put("elo",stat.elo);row.put("matches",stat.matches);board.add(row);
        }
        board.sort((a,b)->{int result=Double.compare((Double)b.get("win_rate"),(Double)a.get("win_rate"));if(result==0)result=Double.compare((Double)b.get("elo"),(Double)a.get("elo"));return result;});
        List<Object> matchJson=new ArrayList<>();for(Match match:matches)matchJson.add(match.json());
        if(!board.isEmpty())System.out.println("agent                 win rate   avg score      Elo  matches");
        for(Map<String,Object> row:board)System.out.printf(Locale.ROOT,"%-20s %7.1f%% %11.1f %8.1f %8d%n",row.get("agent"),(Double)row.get("win_rate")*100,(Double)row.get("average_score"),(Double)row.get("elo"),row.get("matches"));
        return board;
    }
    private static void combinations(List<Entry> source,int needed,int start,List<Entry> current,List<List<Entry>> output){if(current.size()==needed){output.add(new ArrayList<>(current));return;}for(int i=start;i<=source.size()-(needed-current.size());i++){current.add(source.get(i));combinations(source,needed,i+1,current,output);current.remove(current.size()-1);}}

    private static Entry parseEntry(String value) {
        int split=value.indexOf('=');if(split<0)return new Entry(value,null);return new Entry(value.substring(0,split),value.substring(split+1));
    }
    public static void main(String[] args) throws Exception {
        List<Entry> agents=new ArrayList<>();List<Integer> seeds=new ArrayList<>();int seed=0,seedCount=2,players=2;long timeout=1000;boolean match=false;Path replay=null,replayDir=null,output=null,configPath=null;
        for(int i=0;i<args.length;i++)switch(args[i]){
            case "--agents":while(i+1<args.length&&!args[i+1].startsWith("--"))agents.add(parseEntry(args[++i]));break;
            case "--match":match=true;break;case "--seed":seed=Integer.parseInt(args[++i]);break;case "--seeds":seedCount=Integer.parseInt(args[++i]);break;case "--players":players=Integer.parseInt(args[++i]);break;
            case "--timeout":timeout=(long)(Double.parseDouble(args[++i])*1000);break;case "--replay":replay=Paths.get(args[++i]);break;case "--replay-dir":replayDir=Paths.get(args[++i]);break;case "--output":output=Paths.get(args[++i]);break;case "--config":configPath=Paths.get(args[++i]);break;
            default:throw new IllegalArgumentException("Unknown argument: "+args[i]);
        }
        if(agents.isEmpty()){agents.add(new Entry("champion",null));agents.add(new Entry("random",null));agents.add(new Entry("greedy",null));agents.add(new Entry("aggressive",null));}
        Map<String,Object> configs=new HashMap<>();if(configPath!=null){Map<String,Object> loaded=Json.parseObject(Files.readString(configPath));Object inner=loaded.getOrDefault("champion",loaded);configs.put("champion",inner);}
        if(match){if(agents.size()!=2)throw new IllegalArgumentException("--match requires exactly two agents");Match result=runMatch(agents,seed,configs,timeout,replay,false);System.out.println(Json.stringify(result.json()));return;}
        for(int i=0;i<seedCount;i++)seeds.add(i);
        List<Map<String,Object>> board=tournament(agents,players,seeds,timeout,configs,replayDir);
        if(output!=null)Files.writeString(output,Json.stringify(board));
    }
}
