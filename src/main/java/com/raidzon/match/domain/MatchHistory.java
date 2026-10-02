package com.raidzon.match.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.raidzon.match.domain.MatchState.require;

/** Immutable in-memory event history. Persistence and authorization belong to future application services. */
public final class MatchHistory {
    public record Input(String id, int baseVersion, String rulesetVersion, long occurredAt, MatchAction action) {
        public Input {
            require(id != null && !id.isBlank(), "Event ID is required.");
            require(baseVersion >= 0 && occurredAt >= 0, "Invalid event version or time.");
            require(MatchEngine.supports(rulesetVersion), "Unsupported ruleset version.");
            Objects.requireNonNull(action, "action");
        }
    }
    public record Event(Input input, int sequence, MatchState before, MatchTransition result) {}

    private final MatchState initial;
    private final MatchState state;
    private final List<Event> events;
    private MatchHistory(MatchState initial, MatchState state, List<Event> events) {
        this.initial = Objects.requireNonNull(initial); this.state = Objects.requireNonNull(state); this.events = List.copyOf(events);
    }
    public static MatchHistory fromSnapshot(MatchState snapshot) { return new MatchHistory(snapshot, snapshot, List.of()); }
    public MatchState state() { return state; }
    public List<Event> events() { return events; }
    public int version() { return events.size(); }

    public Optional<Event> undoTarget() {
        var reverted = new HashSet<String>();
        for (var event : events) if (event.input().action() instanceof MatchAction.Undo undo) reverted.add(undo.targetEventId());
        for (int i = events.size() - 1; i >= 0; i--) {
            var event = events.get(i);
            if (!(event.input().action() instanceof MatchAction.Undo) && !reverted.contains(event.input().id())) return Optional.of(event);
        }
        return Optional.empty();
    }

    public MatchHistory append(Input input) {
        Objects.requireNonNull(input, "input");
        for (var event : events) if (event.input().id().equals(input.id())) {
            require(event.input().equals(input), "Event ID was reused with different facts.");
            return this;
        }
        require(input.baseVersion() == version(), "Event version does not match current history.");
        require(events.isEmpty() || events.getFirst().input().rulesetVersion().equals(input.rulesetVersion()), "Cannot mix rulesets within a match.");
        require(events.isEmpty() || input.occurredAt() >= events.getLast().input().occurredAt(), "Event time cannot move backwards.");
        var before = state.settleClocks(input.occurredAt());
        MatchTransition transition;
        if (input.action() instanceof MatchAction.Undo undo) {
            var target = undoTarget();
            require(target.isPresent() && target.get().input().id().equals(undo.targetEventId()), "Only the latest unreverted event can be undone.");
            transition = new MatchTransition(target.get().before().restoreClocks(input.occurredAt()), List.of(), "Undo: " + target.get().result().summary());
        } else transition = new MatchEngine(input.rulesetVersion()).apply(before, input.action(), input.occurredAt());
        var next = new ArrayList<>(events);
        next.add(new Event(input, version() + 1, before, transition));
        return new MatchHistory(initial, transition.state(), next);
    }

    /**
     * Applies one non-undo input to a trusted projection without replaying earlier events, so the
     * cost of recording a tap does not grow with the length of the match. Same rules as {@link #append}.
     */
    public static Event step(MatchState state, int version, Input input) {
        Objects.requireNonNull(input, "input");
        require(!(input.action() instanceof MatchAction.Undo), "Undo needs the full history.");
        require(input.baseVersion() == version, "Event version does not match current history.");
        var before = state.settleClocks(input.occurredAt());
        return new Event(input, version + 1, before, new MatchEngine(input.rulesetVersion()).apply(before, input.action(), input.occurredAt()));
    }

    /** Re-evaluates recorded facts. Never accepts client-supplied score snapshots as truth. */
    public static MatchHistory replay(MatchState initial, List<Input> inputs) {
        var history = fromSnapshot(initial);
        for (var input : inputs) history = history.append(input);
        return history;
    }
    public MatchHistory rebuild() { return replay(initial, events.stream().map(Event::input).toList()); }
}
