package beaconwars;

import java.nio.file.*;
import java.util.*;

/** Random-search tuning for champion weights against built-ins and old configs. */
public final class SelfPlay {
    private SelfPlay() {}
    private static final String[] BASELINES={"random","greedy","aggressive"};
    private static Map<String,Object> defaultConfig(){Map<String,Object> config=new LinkedHashMap<>();config.put("beacon_value",1.0);config.put("aggression",1.0);config.put("retreat_hp",2);config.put("scan_gain",24);config.put("memory_decay",0.86);return config;}
    private static Map<String,Object> randomConfig(Random random){Map<String,Object> config=new LinkedHashMap<>();config.put("beacon_value",Math.round((0.6+random.nextDouble()*1.6)*1000)/1000.0);config.put("aggression",Math.round((0.4+random.nextDouble()*1.6)*1000)/1000.0);config.put("retreat_hp",new int[]{1,2,3}[random.nextInt(3)]);config.put("scan_gain",12+random.nextInt(49));config.put("memory_decay",Math.round((0.74+random.nextDouble()*0.22)*1000)/1000.0);return config;}
    private static double winRate(Map<String,Object> config,String opponent,List<Integer> seeds)throws Exception{
        double points=0;int games=0;
        for(int seed:seeds)for(boolean championFirst:new boolean[]{true,false}){
            List<Arena.Entry> lineup=new ArrayList<>();if(championFirst){lineup.add(new Arena.Entry("champion",null));lineup.add(new Arena.Entry(opponent,null));}else{lineup.add(new Arena.Entry(opponent,null));lineup.add(new Arena.Entry("champion",null));}
            Map<String,Object> configs=new HashMap<>();configs.put("champion",new HashMap<>(config));
            Arena.Match result=Arena.runMatch(lineup,seed,configs,1000,null,false);int seat=result.players.indexOf("champion");
            if(result.winner<0)points+=0.5;else if(result.winner==seat)points++;games++;
        }
        return games==0?0:points/games;
    }
    private static double objective(Map<String,Object> config,List<Integer> seeds,List<Map<String,Object>> prior)throws Exception{
        double total=0;int count=0;for(String opponent:BASELINES){total+=winRate(config,opponent,seeds);count++;}
        int index=0;for(Map<String,Object> old:prior){String name="old"+index++;double value=0;int games=0;
            for(int seed:seeds)for(boolean championFirst:new boolean[]{true,false}){
                List<Arena.Entry> lineup=championFirst?Arrays.asList(new Arena.Entry("champion",null),new Arena.Entry(name,null)):Arrays.asList(new Arena.Entry(name,null),new Arena.Entry("champion",null));
                Map<String,Object> configs=new HashMap<>();configs.put("champion",new HashMap<>(config));configs.put(name,new HashMap<>(old));Arena.Match match=Arena.runMatch(lineup,seed,configs,1000,null,false);int seat=match.players.indexOf("champion");if(match.winner<0)value+=0.5;else if(match.winner==seat)value++;games++;
            }
            total+=games==0?0:value/games;count++;
        }
        return count==0?0:total/count;
    }
    public static void main(String[] args)throws Exception{
        int trials=8,seedCount=3;Path output=Paths.get("train","best_config.json"),history=Paths.get("train","history");
        for(int i=0;i<args.length;i++)switch(args[i]){case "--trials":trials=Integer.parseInt(args[++i]);break;case "--seeds":seedCount=Integer.parseInt(args[++i]);break;case "--output":output=Paths.get(args[++i]);break;default:throw new IllegalArgumentException("Unknown option: "+args[i]);}
        List<Integer> seeds=new ArrayList<>();for(int i=0;i<seedCount;i++)seeds.add(i);
        List<Map<String,Object>> prior=new ArrayList<>();if(Files.exists(history))try(DirectoryStream<Path> stream=Files.newDirectoryStream(history,"*.json")){for(Path path:stream)prior.add(Json.parseObject(Files.readString(path)));}
        if(prior.isEmpty())prior.add(defaultConfig());
        Map<String,Double> before=new LinkedHashMap<>();for(String baseline:BASELINES)before.put(baseline,winRate(defaultConfig(),baseline,seeds));
        Random random=new Random(9321);Map<String,Object> best=defaultConfig();double bestScore=objective(best,seeds,prior);
        for(int trial=0;trial<trials;trial++){Map<String,Object> candidate=randomConfig(random);double score=objective(candidate,seeds,prior);if(score>bestScore){best=candidate;bestScore=score;}}
        Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,Json.stringify(best));Files.createDirectories(history);int version=0;try(DirectoryStream<Path> stream=Files.newDirectoryStream(history,"*.json")){for(Path ignored:stream)version++;}Files.writeString(history.resolve(String.format(Locale.ROOT,"champion_%04d.json",version+1)),Json.stringify(best));
        System.out.println("baseline       before    after");for(String baseline:BASELINES)System.out.printf(Locale.ROOT,"%-14s %6.1f%% %8.1f%%%n",baseline,before.get(baseline)*100,winRate(best,baseline,seeds)*100);
        System.out.println("search score: "+String.format(Locale.ROOT,"%.1f%%",bestScore*100)+"; config: "+output);System.out.println(Json.stringify(best));
    }
}
