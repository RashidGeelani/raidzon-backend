package com.raidzon.scorecard.dto;
import java.util.UUID;
public record PublicScorecard(UUID shareId,String teamA,String teamB,int scoreA,int scoreB,
    int tieScoreA,int tieScoreB,String status,String phase,int half,int raidNumber,
    String winner,int version,long lastSyncedAt) {}
