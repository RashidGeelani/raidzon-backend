package com.raidzon.scorecard.service;
import com.raidzon.scorecard.dto.PublicScorecard;
import com.raidzon.scorecard.repository.ScorecardRepository;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service @Profile("postgres")
public class ScorecardService {
    private final ScorecardRepository repository;
    public ScorecardService(ScorecardRepository repository){this.repository=repository;}
    public UUID publish(UUID match,AuthIdentity actor,boolean published){return repository.publish(match,actor.accountId(),published);}
    public PublicScorecard read(UUID share){return repository.read(share);}
}
