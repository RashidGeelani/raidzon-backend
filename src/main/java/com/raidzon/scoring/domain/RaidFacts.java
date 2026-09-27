package com.raidzon.scoring.domain;

public record RaidFacts(
    String outcome,
    int defendingCount,
    int touches,
    boolean bonus,
    int defenderSelfOuts
) {}
