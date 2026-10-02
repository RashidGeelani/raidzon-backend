package com.raidzon.team.dto;

import java.util.List;
import java.util.UUID;

/** Reusable team API shapes. Phones are only returned to the team's owner, managers and coaches. */
public final class Team {
    private Team() {}

    public static final int REQUIRED_PLAYERS = 7;
    public static final int RECOMMENDED_PLAYERS = 12;
    public static final int MAX_SQUAD = 20;
    public static final int MAX_STAFF = 10;

    public enum Role { OWNER, MANAGER, COACH }

    public record CreateInput(UUID id, String name, String city) {}
    public record DetailsInput(String name, String city) {}
    public record MemberInput(UUID id, String name, String phone, Integer jersey, String playingRole) {}
    public record MemberEdit(String name, Integer jersey, String playingRole) {}
    public record LeadershipInput(UUID captainMemberId, UUID viceCaptainMemberId) {}
    public record StaffInput(String name, String phone, String role) {}
    public record StaffRemoval(UUID profileId, String role) {}

    public record Summary(UUID id, String name, String city, boolean archived, String myRole,
                          int squadSize, String captainName) {}
    /** name = the player's own name if they set one, otherwise the organizer's squadName. */
    public record Member(UUID id, UUID profileId, String name, String squadName, String phone,
                         Integer jersey, String playingRole, String leadership, boolean claimed) {}
    public record Staff(UUID profileId, String name, String phone, String role) {}
    public record Detail(UUID id, String name, String city, boolean archived, int revision, String myRole,
                         String ownerName, List<Member> members, List<Staff> staff) {}
}
