package com.raidzon.scoring.domain;

public record RaidScore(
        int attackingPoints,
        int defendingPoints,
        int raiderPoints,
        int defenderPoints,
        int attackingRevivals,
        int defendingRevivals,
        int allOutPoints,
        int superTackleExtra
) {}
