package com.raidzon.tournament.service;

import com.raidzon.tournament.dto.Tournament;
import com.raidzon.tournament.repository.TournamentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service @Profile("postgres")
public class TournamentService {
    private final TournamentRepository repository;
    private final com.raidzon.team.repository.TeamRepository teams;
    public TournamentService(TournamentRepository repository,com.raidzon.team.repository.TeamRepository teams){this.repository=repository;this.teams=teams;}
    public List<Tournament> list(UUID owner){return repository.list(owner);}
    public List<Tournament> browse(String search) {
        String query=search.trim();
        if(query.length()>100) throw new com.raidzon.identity.service.AuthFailure(400,"INVALID_SEARCH","Search must be 100 characters or fewer.");
        return repository.browse(query);
    }
    @Transactional(readOnly=true) public Tournament.PublicDetail publicDetail(UUID id){return repository.publicDetail(id);}
    public List<UUID> joined(UUID account){return repository.joined(account);}
    public List<Tournament.PlayerRanking> leaderboard(UUID id,String category){
        if(!List.of("raid","tackle","total").contains(category)) throw new IllegalArgumentException("Unknown leaderboard category.");
        return repository.leaderboard(id,category);
    }
    @Transactional public void join(UUID id,UUID account){repository.join(id,account);}
    @Transactional public void leave(UUID id,UUID account){repository.leave(id,account);}
    public Tournament.Detail detail(UUID id, UUID owner){return repository.detail(id,owner);}
    @Transactional public Tournament.Detail create(Tournament input,UUID owner){
        required(input.id());
        if(input.startsOn()==null || input.halfMinutes()<1 || input.halfMinutes()>60 || input.raidSeconds()<5 || input.raidSeconds()>120)throw new IllegalArgumentException("Choose a start date, 1–60 minute halves and 5–120 second raids.");
        var value=new Tournament(input.id(),name(input.name(),100),name(input.venue(),160),input.startsOn(),input.halfMinutes(),input.raidSeconds());
        repository.create(value,owner);return detail(value.id(),owner);
    }
    @Transactional public Tournament.Detail team(UUID id,Tournament.TeamInput input,UUID owner){
        repository.owned(id,owner,true);required(input.id());repository.team(id,new Tournament.TeamInput(input.id(),name(input.name(),60)));return detail(id,owner);
    }
    @Transactional public Tournament.Detail roster(UUID id,UUID team,Tournament.RosterInput input,UUID owner){
        repository.owned(id,owner,true);
        if(input.players()==null || (!input.players().isEmpty() && (input.players().size()<Tournament.MIN_ROSTER || input.players().size()>Tournament.MAX_ROSTER)) || input.expectedRevision()<0)
            throw new IllegalArgumentException("Save a squad of 7 to 20 players, or clear the roster.");
        var players=input.players().stream().map(player->{
            if(player==null)throw new IllegalArgumentException("Enter each roster player.");
            var phone=player.phone();
            if(phone==null || !phone.matches("\\+[1-9][0-9]{7,14}"))throw new IllegalArgumentException("Use international player phone numbers.");
            return new Tournament.RosterPlayer(name(player.name(),70),phone);
        }).toList();
        if(players.stream().map(Tournament.RosterPlayer::phone).distinct().count()!=players.size())
            throw new IllegalArgumentException("Each player needs a unique phone number.");
        onlyOneTeam(id,team,players);
        repository.roster(id,team,new Tournament.RosterInput(players,input.expectedRevision()));return detail(id,owner);
    }
    @Transactional public Tournament.Detail fixture(UUID id,Tournament.FixtureInput input,UUID owner){
        repository.owned(id,owner,true);required(input.id());required(input.teamAId());required(input.teamBId());
        var normalized=new Tournament.FixtureInput(input.id(),input.teamAId(),input.teamBId(),input.scheduledAt()==null?null:input.scheduledAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        repository.fixture(id,normalized);return detail(id,owner);
    }
    @Transactional public Tournament.Detail link(UUID id,UUID fixture,Tournament.LinkInput input,UUID owner){
        repository.owned(id,owner,true);required(input.matchId());repository.link(id,fixture,input.matchId(),owner);return detail(id,owner);
    }
    @Transactional public Tournament.Detail reschedule(UUID id,UUID fixture,Tournament.ScheduleInput input,UUID owner){
        repository.owned(id,owner,true);
        if(input.expectedRevision()<0)throw new IllegalArgumentException("Invalid fixture revision.");
        var normalized=new Tournament.ScheduleInput(input.scheduledAt()==null?null:input.scheduledAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),input.expectedRevision());
        repository.reschedule(id,fixture,normalized);return detail(id,owner);
    }
    /** Register one of your saved teams (you must own or manage it); its current squad is copied as the roster. */
    @Transactional public Tournament.Detail registerSavedTeam(UUID id,Tournament.SavedTeamInput input,UUID owner){
        repository.owned(id,owner,true);required(input.id());required(input.teamId());
        var squad=savedSquad(input.teamId(),owner);
        onlyOneTeam(id,input.id(),squad.players());
        repository.registerSavedTeam(id,input.id(),input.teamId(),squad.name(),squad.players());
        return detail(id,owner);
    }
    /** Re-copy the saved team's current squad into this tournament (e.g. after adding a replacement player). */
    @Transactional public Tournament.Detail syncSavedTeam(UUID id,UUID team,UUID owner){
        repository.owned(id,owner,true);
        var squad=savedSquad(repository.savedTeamOf(id,team),owner);
        onlyOneTeam(id,team,squad.players());
        repository.syncSavedRoster(id,team,squad.players());
        return detail(id,owner);
    }
    private com.raidzon.team.repository.TeamRepository.Squad savedSquad(UUID savedTeam,UUID owner){
        var role=teams.role(savedTeam,owner);
        if(role==null)throw new com.raidzon.identity.service.AuthFailure(404,"TEAM_NOT_FOUND","Saved team not found.");
        if(role==com.raidzon.team.dto.Team.Role.COACH)throw new com.raidzon.identity.service.AuthFailure(403,"TEAM_FORBIDDEN","Only the team's owner or manager can register it.");
        var squad=teams.squad(savedTeam);
        if(squad.archived())throw new com.raidzon.identity.service.AuthFailure(409,"TEAM_ARCHIVED","This team is archived.");
        if(squad.players().size()<Tournament.MIN_ROSTER)
            throw new com.raidzon.identity.service.AuthFailure(409,"SQUAD_TOO_SMALL","Add at least "+Tournament.MIN_ROSTER+" players to the squad before registering.");
        return squad;
    }
    private void onlyOneTeam(UUID tournament,UUID team,List<Tournament.RosterPlayer> players){
        var clashes=repository.playersOnOtherTeams(tournament,team,players);
        if(!clashes.isEmpty())throw new com.raidzon.identity.service.AuthFailure(409,"PLAYER_IN_OTHER_TEAM",
            "A player can play for only one team in a tournament. Already registered: "+String.join(", ",clashes)+".");
    }
    private static void required(UUID id){if(id==null)throw new IllegalArgumentException("An ID is required.");}
    private static String name(String value,int max){if(value==null || value.strip().isEmpty() || value.strip().length()>max || value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter a valid name or venue (maximum "+max+" characters).");return value.strip();}
}
