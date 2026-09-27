package com.raidzon.assignment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.raidzon.assignment.repository.ScorerAssignmentRepository;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service @Profile("postgres")
public class ScorerAssignmentService {
    private final ScorerAssignmentRepository repository;
    public ScorerAssignmentService(ScorerAssignmentRepository repository){this.repository=repository;}
    public List<com.raidzon.assignment.dto.Assignment> list(AuthIdentity actor){return repository.list(actor);}
    public void assign(UUID matchId,String phone,AuthIdentity actor){repository.assign(matchId,phone,actor);}
    public JsonNode accept(UUID matchId,AuthIdentity actor){return repository.accept(matchId,actor);}
}
