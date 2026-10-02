package com.raidzon.tournament.service;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.notification.dto.Notification;
import com.raidzon.notification.repository.NotificationRepository;
import com.raidzon.team.dto.Team;
import com.raidzon.team.repository.TeamRepository;
import com.raidzon.tournament.dto.JoinRequest;
import com.raidzon.tournament.dto.Tournament;
import com.raidzon.tournament.repository.JoinRequestRepository;
import com.raidzon.tournament.repository.TournamentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/**
 * Join requests: a saved team's owner or manager asks to enter a tournament; only the tournament
 * organizer approves. Approval copies the team's current squad (with phones) as the tournament roster.
 */
@Service @Profile("postgres")
public class JoinRequestService {
    private final JoinRequestRepository requests;
    private final TournamentRepository tournaments;
    private final TeamRepository teams;
    private final NotificationRepository notifications;
    public JoinRequestService(JoinRequestRepository requests, TournamentRepository tournaments, TeamRepository teams,
                              NotificationRepository notifications) {
        this.requests = requests; this.tournaments = tournaments; this.teams = teams; this.notifications = notifications;
    }

    @Transactional
    public JoinRequest request(UUID tournamentId, JoinRequest.CreateInput input, UUID actor) {
        if (input.id() == null || input.teamId() == null) throw new IllegalArgumentException("Request and team IDs are required.");
        String message = note(input.message());
        var tournament = requests.tournament(tournamentId, true);
        manager(input.teamId(), actor);
        if (requests.exists(input.id())) { requests.insert(input.id(), tournamentId, input.teamId(), actor, message); return requests.one(input.id()); }
        if (!tournament.registrationOpen()) throw conflict("REGISTRATION_CLOSED", "This tournament is not accepting team requests.");
        var squad = eligibleSquad(input.teamId());
        if (requests.registered(tournamentId, input.teamId())) throw conflict("ALREADY_REGISTERED", "This team is already in the tournament.");
        if (requests.pending(tournamentId, input.teamId())) throw conflict("ALREADY_REQUESTED", "This team already has a pending request.");
        if (requests.nameTaken(tournamentId, squad.name())) throw conflict("TEAM_NAME_TAKEN", "A team named " + squad.name() + " is already in this tournament. Rename your team to request.");
        clashes(tournamentId, input.id(), squad.players());
        requests.insert(input.id(), tournamentId, input.teamId(), actor, message);
        var created = requests.one(input.id());
        if (!tournament.owner().equals(actor))
            notifications.notify(tournament.owner(), Notification.Kind.JOIN_REQUEST_RECEIVED,
                    created.teamName() + " wants to join " + created.tournamentName(),
                    created.message() != null ? "“" + created.message() + "”" : created.squadSize() + " players · review the squad to approve",
                    tournamentId, null, created.id());
        return created;
    }

    @Transactional
    public JoinRequest withdraw(UUID requestId, UUID actor) {
        var stored = requests.lock(requestId);
        manager(stored.teamId(), actor);
        if (stored.status().equals("WITHDRAWN")) return requests.one(requestId);
        if (!stored.status().equals("PENDING")) throw conflict("REQUEST_DECIDED", "This request was already " + stored.status().toLowerCase() + ".");
        requests.decide(requestId, "WITHDRAWN", actor, null, null);
        var withdrawn = requests.one(requestId);
        notifications.resolveRequest(requestId);
        var organizer = requests.tournament(stored.tournamentId(), false).owner();
        if (!organizer.equals(actor))
            notifications.notify(organizer, Notification.Kind.JOIN_REQUEST_WITHDRAWN,
                    withdrawn.teamName() + " withdrew its request for " + withdrawn.tournamentName(), null,
                    stored.tournamentId(), null, requestId);
        return withdrawn;
    }

    @Transactional(readOnly = true)
    public List<JoinRequest> forTournament(UUID tournamentId, UUID actor) {
        tournaments.owned(tournamentId, actor, false);
        return requests.forTournament(tournamentId);
    }

    @Transactional(readOnly = true)
    public List<JoinRequest> forTeam(UUID teamId, UUID actor) {
        if (teams.role(teamId, actor) == null) throw new AuthFailure(404, "TEAM_NOT_FOUND", "Team not found.");
        return requests.forTeam(teamId);
    }

    @Transactional
    public Tournament.Detail approve(UUID requestId, JoinRequest.Decision decision, UUID actor) {
        var stored = requests.lock(requestId);
        tournaments.owned(stored.tournamentId(), actor, true);
        if (stored.status().equals("APPROVED")) return tournaments.detail(stored.tournamentId(), actor);
        if (!stored.status().equals("PENDING")) throw conflict("REQUEST_DECIDED", "This request was already " + stored.status().toLowerCase() + ".");
        // The squad is read now, so approval copies the team as it is today.
        var squad = eligibleSquad(stored.teamId());
        clashes(stored.tournamentId(), requestId, squad.players());
        tournaments.registerSavedTeam(stored.tournamentId(), requestId, stored.teamId(), squad.name(), squad.players());
        requests.decide(requestId, "APPROVED", actor, note(decision == null ? null : decision.note()), requestId);
        tellTeam(requestId, actor, Notification.Kind.JOIN_REQUEST_APPROVED, " is in ");
        return tournaments.detail(stored.tournamentId(), actor);
    }

    @Transactional
    public JoinRequest reject(UUID requestId, JoinRequest.Decision decision, UUID actor) {
        var stored = requests.lock(requestId);
        tournaments.owned(stored.tournamentId(), actor, false);
        if (stored.status().equals("REJECTED")) return requests.one(requestId);
        if (!stored.status().equals("PENDING")) throw conflict("REQUEST_DECIDED", "This request was already " + stored.status().toLowerCase() + ".");
        requests.decide(requestId, "REJECTED", actor, note(decision == null ? null : decision.note()), null);
        tellTeam(requestId, actor, Notification.Kind.JOIN_REQUEST_REJECTED, " was not accepted into ");
        return requests.one(requestId);
    }

    @Transactional
    public Tournament.Detail registration(UUID tournamentId, boolean open, UUID actor) {
        tournaments.owned(tournamentId, actor, true);
        requests.setRegistrationOpen(tournamentId, open);
        return tournaments.detail(tournamentId, actor);
    }

    private void tellTeam(UUID requestId, UUID actor, Notification.Kind kind, String verb) {
        var decided = requests.one(requestId);
        notifications.resolveRequest(requestId);
        for (UUID recipient : notifications.teamDecisionRecipients(requestId))
            if (!recipient.equals(actor))
                notifications.notify(recipient, kind, decided.teamName() + verb + decided.tournamentName(),
                        decided.decisionNote() == null ? null : "Organizer: “" + decided.decisionNote() + "”",
                        decided.tournamentId(), decided.teamId(), requestId);
    }
    private void manager(UUID teamId, UUID actor) {
        var role = teams.role(teamId, actor);
        if (role == null) throw new AuthFailure(404, "TEAM_NOT_FOUND", "Team not found.");
        if (role == Team.Role.COACH) throw new AuthFailure(403, "TEAM_FORBIDDEN", "Only the team's owner or manager can do this.");
    }
    private TeamRepository.Squad eligibleSquad(UUID teamId) {
        var squad = teams.squad(teamId);
        if (squad.archived()) throw conflict("TEAM_ARCHIVED", "This team is archived.");
        if (squad.players().size() < Tournament.MIN_ROSTER)
            throw conflict("SQUAD_TOO_SMALL", "The squad needs at least " + Tournament.MIN_ROSTER + " players.");
        return squad;
    }
    private void clashes(UUID tournamentId, UUID excludeTeam, List<Tournament.RosterPlayer> players) {
        var clashes = tournaments.playersOnOtherTeams(tournamentId, excludeTeam, players);
        if (!clashes.isEmpty()) throw conflict("PLAYER_IN_OTHER_TEAM",
                "A player can play for only one team in a tournament. Already registered: " + String.join(", ", clashes) + ".");
    }
    static String note(String value) {
        if (value == null || value.isBlank()) return null;
        String stripped = value.strip();
        if (stripped.length() > 200 || stripped.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
            throw new IllegalArgumentException("Keep the note under 200 characters.");
        return stripped;
    }
    private static AuthFailure conflict(String code, String message) { return new AuthFailure(409, code, message); }
}
