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
            boolean inserted = repository.insert(request.matchId(), actor, session, request.resolvedRuleset(), fingerprint, initial, request.practiceMatch());
            var match = repository.lock(request.matchId()); authorize(match, actor, session); refuseDeleted(match);
            if (!match.ruleset().equals(request.resolvedRuleset())) throw new IllegalArgumentException("Cannot change a match ruleset.");
            if (!match.creationFingerprint().equals(fingerprint)) throw new IllegalArgumentException("Match ID was reused with different setup facts.");
            return new MatchRegistration(match.id(), match.ruleset(), match.version(), !inserted, match.state());
        });
    }

    public EventAcknowledgement append(UUID matchId, MatchEventRequest request, UUID actor, UUID session) {
        var input = codec.input(request);
        var fingerprint = codec.fingerprint(request);
        return transaction.execute(status -> {
            var match = repository.lock(matchId); authorize(match, actor, session); refuseDeleted(match);
            if (!match.ruleset().equals(request.rulesetVersion())) throw new IllegalArgumentException("Cannot mix rulesets within a match.");
            var nearby = repository.eventOrLatest(matchId, request.id(), match.version());
            for (var event : nearby) if (event.eventId().equals(request.id())) {
                if (!event.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Event ID was reused with different facts.");
                return new EventAcknowledgement(request.id(), event.sequence(), match.version(), true, match.state());
            }
            if (request.baseVersion() != match.version()) throw new IllegalArgumentException("Event version does not match current history.");
            Long started = match.initial().clock().startedAt(); // null for v4 until the first raid
            if (started != null && request.occurredAt() < started) throw new IllegalArgumentException("Event precedes match start.");
            if (input.action() == com.raidzon.match.domain.MatchAction.Lifecycle.DRAW && repository.linkedToKnockout(matchId))
                throw new IllegalArgumentException("A knockout match can't end in a draw. Start the tie-break instead.");
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

    /** Everything a phone needs to rebuild a match and keep scoring it. events are the stored requests in order. */
    public record Takeover(UUID matchId, String rulesetVersion, int version, MatchState setup, MatchState state,
                           java.util.List<MatchEventRequest> events, MatchRepository.TakeoverFacts facts) {}
    /** Takeovers (accepted or refused) per account and match per hour. */
    static final int TAKEOVER_LIMIT = 6;
    /**
     * "Continue scoring here": the scoring account moves a live match to the phone making this request.
     * Needs a fresh OTP proof (see AuthService.consumeFreshProof) unless this phone already scores the match;
     * then it only returns the history again (a retried import, or the phone checking it is still the scorer).
     * Moving the scoring device fences the old phone: its next upload gets SCORING_MOVED.
     */
    public Takeover takeover(UUID matchId, com.raidzon.identity.dto.AuthIdentity actor, String sessionToken, String proof) {
        try {
            return transaction.execute(status -> {
                var match = repository.lock(matchId);
                if (!match.scorer().equals(actor.accountId()))
                    throw new com.raidzon.identity.service.AuthFailure(403, "NOT_MATCH_SCORER", "Only the account scoring this match can move it to another phone.");
                if (!MatchEngine.supports(match.ruleset())) throw new IllegalArgumentException("Unsupported ruleset version.");
                refuseDeleted(match);
                var facts = repository.takeoverFacts(matchId);
                if (!match.session().equals(actor.deviceId())) {
                    if (proof == null) throw scoringMoved();
                    if (repository.takeoversInLastHour(matchId, actor.accountId()) >= TAKEOVER_LIMIT)
                        throw new com.raidzon.identity.service.AuthFailure(429, "RATE_LIMITED", "This match was moved too many times. Wait an hour and try again.");
                    if (facts.removed()) throw new com.raidzon.identity.service.AuthFailure(409, "MATCH_REMOVED", "This match was removed by RaidzOn and can't be scored.");
                    if (match.state().status() == MatchState.Status.COMPLETED)
                        throw new com.raidzon.identity.service.AuthFailure(409, "MATCH_FINISHED", "This match is already finished. There is nothing left to score.");
                    if (!com.raidzon.identity.service.AuthService.consumeFreshProof(repository.jdbc(), actor, sessionToken, proof, System.currentTimeMillis()))
                        throw new com.raidzon.identity.service.AuthFailure(403, "VERIFICATION_REQUIRED", "Verify your phone number again. The code is valid for five minutes.");
                    repository.recordTakeover(matchId, actor.accountId(), actor.deviceId(), "MOVED");
                    repository.moveScoring(matchId, actor.deviceId());
                }
                var events = repository.events(matchId);
                var history = replay(match, events); // refuses to hand out a history that doesn't rebuild to the stored score
                return new Takeover(matchId, match.ruleset(), history.version(), match.initial(), history.state(),
                        events.stream().map(MatchRepository.StoredEvent::request).toList(), facts);
            });
        } catch (com.raidzon.identity.service.AuthFailure refused) {
            // Refused attempts are kept too (they count towards the limit). An old phone being told it lost the match is not an attempt.
            if (!refused.code().equals("SCORING_MOVED") && !refused.code().equals("RATE_LIMITED"))
                repository.recordTakeover(matchId, actor.accountId(), actor.deviceId(), refused.code());
            throw refused;
        }
    }
    private static com.raidzon.identity.service.AuthFailure scoringMoved() {
        return new com.raidzon.identity.service.AuthFailure(403, "SCORING_MOVED", "This match is now being scored on another phone.");
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
    /** A match its organizer deleted can't be uploaded or scored again, even from a phone that still has it. */
    private static void refuseDeleted(MatchRepository.StoredMatch match) {
        if (match.deleted()) throw new com.raidzon.identity.service.AuthFailure(410, "MATCH_DELETED", "This match was deleted by its organizer.");
    }
    private void authorize(MatchRepository.StoredMatch match, UUID actor, UUID session) {
        if (!match.scorer().equals(actor)) throw new SecurityException("This account or scoring session is read-only.");
        // Same account, other phone: the match was moved (or never scored here). The app locks it instead of retrying.
        if (!match.session().equals(session)) throw scoringMoved();
        if (!MatchEngine.supports(match.ruleset())) throw new IllegalArgumentException("Unsupported ruleset version.");
    }
}
