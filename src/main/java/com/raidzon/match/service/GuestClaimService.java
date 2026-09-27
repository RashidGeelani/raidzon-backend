package com.raidzon.match.service;

import com.raidzon.identity.dto.AuthIdentity;
import com.raidzon.match.dto.CreateMatchRequest;
import com.raidzon.match.dto.MatchRegistration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Reconciles reusable identities without rewriting immutable match-local player IDs or names. */
public final class GuestClaimService {
    private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final MatchService matches;
    public GuestClaimService(JdbcTemplate jdbc,TransactionTemplate tx,MatchService matches){this.jdbc=jdbc;this.tx=tx;this.matches=matches;}
    public MatchRegistration claim(CreateMatchRequest request,AuthIdentity actor){
        return tx.execute(status->{
            var result=matches.create(request,actor.accountId(),actor.deviceId());
            var players=request.teams().stream().flatMap(team->team.players().stream()).sorted(java.util.Comparator.comparing(CreateMatchRequest.Player::phone)).toList();
            for(var player:players){
                jdbc.update("INSERT INTO player_profiles(id,phone,initial_name,claimed_by) VALUES (?,?,?,(SELECT id FROM user_accounts WHERE phone=?)) ON CONFLICT(phone) DO NOTHING",UUID.randomUUID(),player.phone(),player.name(),player.phone());
                UUID profile=jdbc.queryForObject("SELECT id FROM player_profiles WHERE phone=?",UUID.class,player.phone());
                jdbc.update("INSERT INTO match_player_links(match_id,local_player_id,profile_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",request.matchId(),player.id(),profile);
            }
            return result;
        });
    }
}
