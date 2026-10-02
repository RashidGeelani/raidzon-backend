package com.raidzon.team.service;

import com.raidzon.identity.service.AuthFailure;
import com.raidzon.team.dto.Team;
import com.raidzon.team.repository.TeamRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable teams. Owner: everything. Manager: team details, squad and leadership.
 * Coach: read the squad (and pick match-day lineups on their own device). Mutations lock the team row.
 */
@Service @Profile("postgres")
public class TeamService {
    private static final Set<String> PLAYING_ROLES = Set.of("RAIDER", "DEFENDER", "ALL_ROUNDER");
    private static final Set<String> STAFF_ROLES = Set.of("MANAGER", "COACH");
    private final TeamRepository teams;
    public TeamService(TeamRepository teams) { this.teams = teams; }

    public List<Team.Summary> mine(UUID account) { return teams.mine(account); }

    @Transactional(readOnly = true)
    public Team.Detail detail(UUID teamId, UUID account) {
        return teams.detail(teamId, access(teamId, account));
    }

    @Transactional
    public Team.Detail create(Team.CreateInput input, UUID account) {
        required(input.id());
        teams.create(input.id(), account, name(input.name(), 60, "team name"), city(input.city()));
        return teams.detail(input.id(), Team.Role.OWNER);
    }

    @Transactional
    public Team.Detail details(UUID teamId, Team.DetailsInput input, UUID account) {
        var role = editable(teamId, account, false);
        teams.updateDetails(teamId, name(input.name(), 60, "team name"), city(input.city()));
        teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail archive(UUID teamId, UUID account) {
        var role = editable(teamId, account, true);
        teams.archive(teamId);
        teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail addMember(UUID teamId, Team.MemberInput input, UUID account) {
        var role = editable(teamId, account, false);
        required(input.id());
        String name = name(input.name(), 70, "player name");
        String phone = phone(input.phone());
        Integer jersey = jersey(input.jersey());
        String playingRole = playingRole(input.playingRole());
        UUID profile = teams.profileFor(phone, name);
        var existing = teams.member(input.id());
        if (existing != null) {
            // Retried request: same member ID for the same person in the same team is a no-op.
            if (!existing.teamId().equals(teamId) || !existing.profileId().equals(profile) || !existing.active())
                throw conflict("ID_REUSED", "Member ID was reused with different details.");
            return teams.detail(teamId, role);
        }
        if (teams.activeProfile(teamId, profile)) throw conflict("ALREADY_IN_SQUAD", "This phone number is already in the squad.");
        if (teams.activeMembers(teamId) >= Team.MAX_SQUAD)
            throw conflict("SQUAD_FULL", "A squad can have up to " + Team.MAX_SQUAD + " players.");
        if (jersey != null && teams.jerseyTaken(teamId, jersey, input.id())) throw conflict("JERSEY_TAKEN", "Jersey " + jersey + " is already taken.");
        teams.addMember(input.id(), teamId, profile, name, jersey, playingRole);
        teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail editMember(UUID teamId, UUID memberId, Team.MemberEdit input, UUID account) {
        var role = editable(teamId, account, false);
        activeMember(teamId, memberId);
        Integer jersey = jersey(input.jersey());
        if (jersey != null && teams.jerseyTaken(teamId, jersey, memberId)) throw conflict("JERSEY_TAKEN", "Jersey " + jersey + " is already taken.");
        teams.editMember(memberId, name(input.name(), 70, "player name"), jersey, playingRole(input.playingRole()));
        teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail removeMember(UUID teamId, UUID memberId, UUID account) {
        var role = editable(teamId, account, false);
        var member = teams.member(memberId);
        if (member == null || !member.teamId().equals(teamId)) throw new AuthFailure(404, "MEMBER_NOT_FOUND", "Player not found in this squad.");
        if (member.active()) { teams.removeMember(memberId); teams.touch(teamId); }
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail leadership(UUID teamId, Team.LeadershipInput input, UUID account) {
        var role = editable(teamId, account, false);
        checkLeadership(input.captainMemberId(), input.viceCaptainMemberId());
        if (input.captainMemberId() != null) activeMember(teamId, input.captainMemberId());
        if (input.viceCaptainMemberId() != null) activeMember(teamId, input.viceCaptainMemberId());
        teams.setLeadership(teamId, input.captainMemberId(), input.viceCaptainMemberId());
        teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail addStaff(UUID teamId, Team.StaffInput input, UUID account) {
        var role = editable(teamId, account, true);
        String staffRole = staffRole(input.role());
        String name = name(input.name(), 70, "staff name");
        UUID profile = teams.profileFor(phone(input.phone()), name);
        if (teams.staffCount(teamId) >= Team.MAX_STAFF) throw conflict("STAFF_FULL", "A team can have up to " + Team.MAX_STAFF + " staff.");
        if (teams.addStaff(teamId, profile, staffRole, name)) teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    @Transactional
    public Team.Detail removeStaff(UUID teamId, Team.StaffRemoval input, UUID account) {
        var role = editable(teamId, account, true);
        required(input.profileId());
        if (teams.removeStaff(teamId, input.profileId(), staffRole(input.role()))) teams.touch(teamId);
        return teams.detail(teamId, role);
    }

    // ---- permissions ----
    private Team.Role access(UUID teamId, UUID account) {
        var role = teams.role(teamId, account);
        // Missing and foreign teams look the same, so team IDs cannot be probed.
        if (role == null) throw new AuthFailure(404, "TEAM_NOT_FOUND", "Team not found.");
        return role;
    }
    private Team.Role editable(UUID teamId, UUID account, boolean ownerOnly) {
        var role = access(teamId, account);
        teams.lock(teamId);
        if (ownerOnly ? role != Team.Role.OWNER : role == Team.Role.COACH)
            throw new AuthFailure(403, "TEAM_FORBIDDEN", ownerOnly ? "Only the team owner can do this." : "Coaches can view the squad but not change it.");
        if (teams.archived(teamId)) throw conflict("TEAM_ARCHIVED", "This team is archived.");
        return role;
    }
    private void activeMember(UUID teamId, UUID memberId) {
        var member = memberId == null ? null : teams.member(memberId);
        if (member == null || !member.teamId().equals(teamId) || !member.active())
            throw new AuthFailure(404, "MEMBER_NOT_FOUND", "Player not found in this squad.");
    }

    // ---- validation (package-private for unit tests) ----
    static String name(String value, int max, String label) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > max || stripped.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a " + label + " of 1–" + max + " characters.");
        return stripped;
    }
    static String city(String value) {
        return value == null || value.isBlank() ? null : name(value, 60, "city");
    }
    static String phone(String value) {
        if (value == null || !value.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Use an international phone number.");
        return value;
    }
    static Integer jersey(Integer value) {
        if (value != null && (value < 0 || value > 99)) throw new IllegalArgumentException("Jersey numbers are 0–99.");
        return value;
    }
    static String playingRole(String value) {
        if (value == null || value.isBlank()) return null;
        if (!PLAYING_ROLES.contains(value)) throw new IllegalArgumentException("Playing role must be RAIDER, DEFENDER or ALL_ROUNDER.");
        return value;
    }
    static String staffRole(String value) {
        if (value == null || !STAFF_ROLES.contains(value)) throw new IllegalArgumentException("Staff role must be MANAGER or COACH.");
        return value;
    }
    static void checkLeadership(UUID captain, UUID viceCaptain) {
        if (captain != null && captain.equals(viceCaptain)) throw new IllegalArgumentException("Captain and vice-captain must be different players.");
    }
    private static void required(UUID id) { if (id == null) throw new IllegalArgumentException("An ID is required."); }
    private static AuthFailure conflict(String code, String message) { return new AuthFailure(409, code, message); }
}
