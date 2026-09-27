package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TournamentStandingsTest {
    private final UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID();
    private Tournament.Team team(UUID id,String name){return new Tournament.Team(id,name,0,List.of());}
    private Tournament.Fixture fixture(UUID first,UUID second,int scoreA,int scoreB,String winner,String status){
        return new Tournament.Fixture(UUID.randomUUID(),first,second,null,UUID.randomUUID(),status,scoreA,scoreB,"REGULATION",0,0,winner,0);
    }
    @Test void ranksByTablePointsThenRegulationScoreDifferenceAndIgnoresLiveFixtures(){
        var rows=TournamentStandings.calculate(List.of(team(a,"A"),team(b,"B"),team(c,"C")),List.of(
            fixture(a,b,10,5,"TEAM_A","COMPLETED"),
            fixture(a,c,11,10,"TEAM_A","COMPLETED"),
            fixture(b,c,8,8,"DRAW","COMPLETED"),
            fixture(c,b,100,0,"TEAM_A","LIVE")));
        assertEquals(List.of(a,c,b),rows.stream().map(Tournament.Standing::teamId).toList());
        assertEquals(List.of(6,1,1),rows.stream().map(Tournament.Standing::tablePoints).toList());
        assertEquals(List.of(6,-1,-5),rows.stream().map(Tournament.Standing::scoreDifference).toList());
        assertEquals(List.of(1,2,3),rows.stream().map(Tournament.Standing::rank).toList());
    }
    @Test void equalPointsAndDifferenceShareRank(){
        var rows=TournamentStandings.calculate(List.of(team(a,"A"),team(b,"B")),List.of());
        assertEquals(List.of(1,1),rows.stream().map(Tournament.Standing::rank).toList());
    }
}
