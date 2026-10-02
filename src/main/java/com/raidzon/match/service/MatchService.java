package com.raidzon.match.service;

import com.raidzon.match.domain.MatchEngine;
import com.raidzon.match.domain.MatchHistory;
import com.raidzon.match.domain.MatchState;
import com.raidzon.match.dto.CreateMatchRequest;
import com.raidzon.match.dto.EventAcknowledgement;
import com.raidzon.match.dto.MatchEventRequest;
import com.raidzon.match.dto.MatchJsonCodec;
import com.raidzon.match.dto.MatchRegistration;
import com.raidzon.match.repository.MatchRepository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

/** Internal application boundary. Caller identities must come from future verified authentication, never request headers alone. */
public final class MatchService {
    private final MatchRepository repository;
    private final MatchJsonCodec codec;
    private final TransactionTemplate transaction;
    public MatchService(MatchRepository repository, MatchJsonCodec codec, TransactionTemplate transaction) {
        this.repository = repository; this.codec = codec; this.transaction = transaction;
    }

    public MatchRegistration create(CreateMatchRequest request, UUID actor, UUID session) {
        Objects.requireNonNull(actor); Objects.requireNonNull(session);
        var initial = request.initialState();
        var fingerprint = codec.fingerprint(request);
        return transaction.execute(status -> {
            boolean inserted = repository.insert(request.matchId(), actor, session, request.resolvedRuleset(), fingerprint, initial);
            var match = repository.lock(request.matchId()); authorize(match, actor, session);
            if (!match.ruleset().equals(request.resolvedRuleset())) throw new IllegalArgumentException("Cannot change a match ruleset.");
            if (!match.creationFingerprint().equals(fingerprint)) throw new IllegalArgumentException("Match ID was reused with different setup facts.");
            return new MatchRegistration(match.id(), match.ruleset(), match.version(), !inserted, match.state());
        });
    }

    public EventAcknowledgement append(UUID matchId, MatchEventRequest request, UUID actor, UUID session) {
        var input = codec.input(request);
        var fingerprint = codec.fingerprint(request);
        return transaction.execute(status -> {
            var match = repository.lock(matchId); authorize(match, actor, session);
            if (!match.ruleset().equals(request.rulesetVersion())) throw new IllegalArgumentException("Cannot mix rulesets within a match.");
            var nearby = repository.eventOrLatest(matchId, request.id(), match.version());
            for (var event : nearby) if (event.eventId().equals(request.id())) {
                if (!event.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Event ID was reused with different facts.");
                return new EventAcknowledgement(request.id(), event.sequence(), match.version(), true, match.state());
            }
            if (request.baseVersion() != match.version()) throw new IllegalArgumentException("Event version does not match current history.");
            Long started = match.initial().clock().startedAt(); // null for v4 until the first raid
            if (started != null && request.occurredAt() < started) throw new IllegalArgumentException("Event precedes match start.");
            MatchHistory.Event next;
            if (input.action() instanceof com.raidzon.match.domain.MatchAction.Undo) {
                // Undo restores an earlier step, so it needs the verified full history.
                next = replay(match, repository.events(matchId)).append(input).events().getLast();
            } else {
                // Every other tap applies to the stored projection: constant time however long the match is.
                for (var event : nearby)
                    if (event.sequence() == match.version() && request.occurredAt() < event.occurredAt())
                        throw new IllegalArgumentException("Event time cannot move backwards.");
                next = MatchHistory.step(match.state(), match.version(), input);
            }
            repository.append(matchId, request, fingerprint, next);
            return new EventAcknowledgement(request.id(), next.sequence(), next.sequence(), false, next.result().state());
        });
    }

    public MatchState rebuild(UUID matchId, UUID actor, UUID session) {
        return transaction.execute(status -> {
            var match = repository.lock(matchId); authorize(match, actor, session);
            return replay(match, repository.events(matchId)).state();
        });
    }
    private MatchHistory replay(MatchRepository.StoredMatch match, java.util.List<MatchRepository.StoredEvent> events) {
        var history = MatchHistory.fromSnapshot(match.initial());
        for (var event : events) {
            if (!match.ruleset().equals(event.request().rulesetVersion())) throw new IllegalStateException("Stored ruleset mismatch.");
            if (event.sequence() != history.version() + 1 || !event.fingerprint().equals(codec.fingerprint(event.request())))
                throw new IllegalStateException("Stored event sequence or fingerprint is inconsistent.");
            history = history.append(codec.input(event.request()));
        }
        if (history.version() != match.version() || !history.state().equals(match.state()))
            throw new IllegalStateException("Stored projection does not match replay.");
        return history;
    }
    private void authorize(MatchRepository.StoredMatch match, UUID actor, UUID session) {
        if (!match.scorer().equals(actor) || !match.session().equals(session)) throw new SecurityException("This account or scoring session is read-only.");
        if (!MatchEngine.supports(match.ruleset())) throw new IllegalArgumentException("Unsupported ruleset version.");
    }
}
