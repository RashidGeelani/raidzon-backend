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
    public TournamentService(TournamentRepository repository){this.repository=repository;}
    public List<Tournament> list(UUID owner){return repository.list(owner);}
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
        if(input.players()==null || (!input.players().isEmpty() && (input.players().size()<7 || input.players().size()>12)) || input.expectedRevision()<0)
            throw new IllegalArgumentException("Save seven starters and up to five substitutes, or clear the roster.");
        var players=input.players().stream().map(player->{
            if(player==null)throw new IllegalArgumentException("Enter each roster player.");
            var phone=player.phone();
            if(phone==null || !phone.matches("\\+[1-9][0-9]{7,14}"))throw new IllegalArgumentException("Use international player phone numbers.");
            return new Tournament.RosterPlayer(name(player.name(),70),phone);
        }).toList();
        if(players.stream().map(Tournament.RosterPlayer::phone).distinct().count()!=players.size())
            throw new IllegalArgumentException("Each player needs a unique phone number.");
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
    private static void required(UUID id){if(id==null)throw new IllegalArgumentException("An ID is required.");}
    private static String name(String value,int max){if(value==null || value.strip().isEmpty() || value.strip().length()>max || value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter a valid name or venue (maximum "+max+" characters).");return value.strip();}
}
