package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * League or group table from completed, server-linked fixtures. Order: table points, then point
 * difference, then scoring ratio (points for / points against). Fully tied rows share a rank.
 */
public final class TournamentStandings {
    private TournamentStandings() {}
    private static final class Count {
        final UUID id; final String name;
        int played, won, drawn, lost, tablePoints, pointsFor, pointsAgainst;
        Count(UUID id,String name){this.id=id;this.name=name;}
        void record(int scored,int conceded,String result){
            played++;pointsFor+=scored;pointsAgainst+=conceded;
            switch(result){case "WIN" -> {won++;tablePoints+=3;} case "DRAW" -> {drawn++;tablePoints++;} default -> lost++;}
        }
        int difference(){return pointsFor-pointsAgainst;}
        double ratio(){return pointsAgainst==0?(pointsFor>0?Double.MAX_VALUE:0):(double)pointsFor/pointsAgainst;}
        Tournament.Standing row(int rank,UUID group,boolean qualifies){return new Tournament.Standing(id,name,rank,played,won,drawn,lost,tablePoints,pointsFor,pointsAgainst,difference(),group,qualifies);}
    }
    public static List<Tournament.Standing> calculate(List<Tournament.Team> teams,List<Tournament.Fixture> fixtures){
        return calculate(teams,fixtures.stream().filter(f->!f.knockout()).toList(),null,0);
    }
    /** One group's table (group null = whole league). The top {@code advance} rows are marked as qualifying. */
    public static List<Tournament.Standing> calculate(List<Tournament.Team> teams,List<Tournament.Fixture> fixtures,UUID group,int advance){
        var counts=new java.util.LinkedHashMap<UUID,Count>();
        for(var team:teams)counts.put(team.id(),new Count(team.id(),team.name()));
        for(var fixture:fixtures){
            if(fixture.matchId()==null || !"COMPLETED".equals(fixture.status()) || fixture.scoreA()==null || fixture.scoreB()==null || fixture.winner()==null)continue;
            var a=counts.get(fixture.teamAId());var b=counts.get(fixture.teamBId());
            if(a==null || b==null)continue;
            a.record(fixture.scoreA(),fixture.scoreB(),fixture.winner().equals("DRAW")?"DRAW":fixture.winner().equals("TEAM_A")?"WIN":"LOSS");
            b.record(fixture.scoreB(),fixture.scoreA(),fixture.winner().equals("DRAW")?"DRAW":fixture.winner().equals("TEAM_B")?"WIN":"LOSS");
        }
        var sorted=new ArrayList<>(counts.values());
        sorted.sort(Comparator.comparingInt((Count value)->value.tablePoints).reversed()
            .thenComparing(Comparator.comparingInt(Count::difference).reversed())
            .thenComparing(Comparator.comparingDouble(Count::ratio).reversed())
            .thenComparing(value->value.name,String.CASE_INSENSITIVE_ORDER).thenComparing(value->value.id));
        var result=new ArrayList<Tournament.Standing>();
        for(int i=0;i<sorted.size();i++){
            var current=sorted.get(i);
            var previous=i>0?sorted.get(i-1):null;
            int rank=previous!=null && current.tablePoints==previous.tablePoints && current.difference()==previous.difference()
                && Double.compare(current.ratio(),previous.ratio())==0 ? result.get(i-1).rank():i+1;
            result.add(current.row(rank,group,i<advance));
        }
        return result;
    }
}
