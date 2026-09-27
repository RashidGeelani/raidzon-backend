package com.raidzon.identity.dto;

import java.util.UUID;

public record AuthIdentity(UUID accountId, UUID deviceId) {}
