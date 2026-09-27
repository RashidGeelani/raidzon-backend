package com.raidzon.assignment.dto;
import java.util.UUID;
public record Assignment(UUID matchId,String teamA,String teamB,boolean accepted,String rulesetVersion) {}
