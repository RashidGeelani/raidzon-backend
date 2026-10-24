package com.raidzon.team.dto;

import java.util.List;
import java.util.UUID;

/**
 * Reusable team API shapes. Phones are only returned to the team's owner, managers and coaches.
 * People added by phone are invited until they accept; until then only the organizer's typed name is shown.
 */
public final class Team {
    private Team() {}

    public static final int REQUIRED_PLAYERS = 7;
    public static final int RECOMMENDED_PLAYERS = 12;
    public static final int MAX_SQUAD = 20;
    public static final int MAX_STAFF = 10;

    /** PLAYER = an accepted squad member: view-only, no phone numbers. */
    public enum Role {
        OWNER, MANAGER, COACH, PLAYER;
        /** Owner and managers change the squad and register the team; coaches and players only view. */
        public boolean manages() { return this == OWNER || this == MANAGER; }
    }

    public record CreateInput(UUID id, String name, String city) {}
    public record DetailsInput(String name, String city) {}
    public record MemberInput(UUID id, String name, String phone, Integer jersey, String playingRole) {}
    public record MemberEdit(String name, Integer jersey, String playingRole) {}
    public record LeadershipInput(UUID captainMemberId, UUID viceCaptainMemberId) {}
    public record StaffInput(String name, String phone, String role) {}
    public record StaffRemoval(UUID id) {}

    /** myLeadership is CAPTAIN / VICE_CAPTAIN when the viewer is in the squad with that role. */
    public record Summary(UUID id, String name, String city, boolean archived, String myRole, String myLeadership,
                          int squadSize, String captainName) {}
    /** name = the player's own name once they accepted, otherwise the organizer's squadName. phone is null for PLAYER viewers. */
    public record Member(UUID id, String name, String squadName, String phone, Integer jersey, String playingRole,
                         String leadership, boolean claimed, boolean invited) {}
    public record Staff(UUID id, String name, String phone, String role, boolean invited) {}
    public record Detail(UUID id, String name, String city, boolean archived, int revision, String myRole,
                         String ownerName, List<Member> members, List<Staff> staff) {}
    /** A pending invitation for the signed-in account. role is PLAYER, MANAGER or COACH. */
    public record Invitation(UUID teamId, String teamName, String city, String ownerName, String role, String squadName) {}
}
