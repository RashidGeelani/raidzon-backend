package com.raidzon.scorecard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.scorecard.dto.LiveMatchView;
import com.raidzon.scorecard.repository.LiveMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.UUID;

/**
 * Public live match views, cached in Redis so viewer reads skip the database round trip.
 * Every change to a match goes through LiveMatchHub.publish, which calls refresh; the TTL only
 * covers a missed refresh. With Redis off (the default) or unreachable, reads go to the database.
 */
@Component @Profile("postgres")
public class LiveViewCache {
    static final Duration TTL = Duration.ofSeconds(60);
    private static final String HIDDEN = "-";
    private static final Logger log = LoggerFactory.getLogger(LiveViewCache.class);

    private final LiveMatchRepository matches;
    private final ObjectMapper json;
    private final StringRedisTemplate redis; // null when Redis is off

    public LiveViewCache(LiveMatchRepository matches, ObjectMapper json, ObjectProvider<StringRedisTemplate> redis,
            @Value("${raidzon.redis.enabled:false}") boolean enabled) {
        this.matches = matches;
        this.json = json;
        this.redis = enabled ? redis.getObject() : null;
    }

    boolean enabled() { return redis != null; }

    org.springframework.data.redis.connection.RedisConnectionFactory redisConnections() { return redis.getRequiredConnectionFactory(); }

    void redisPublish(String channel, String text) { redis.convertAndSend(channel, text); }

    /** The current view, from Redis when cached. Throws AuthFailure when the match is not public. */
    public LiveMatchView read(UUID id) {
        String cached = get(id);
        if (cached == null) return refresh(id);
        if (HIDDEN.equals(cached)) throw LiveMatchRepository.notPublic();
        try {
            var v = json.readValue(cached, LiveMatchView.class);
            // serverTime anchors viewers' match clocks, so it must be the time of this read.
            return new LiveMatchView(v.matchId(), v.state(), v.events(), v.version(), System.currentTimeMillis(),
                    v.lastSyncedAt(), v.clockOffset(), v.tournamentId(), v.startedAt(), v.venue());
        } catch (Exception unreadable) {
            return refresh(id);
        }
    }

    /** Reads the view from the database and stores it. */
    public LiveMatchView refresh(UUID id) {
        try {
            var view = matches.read(id);
            if (redis != null) put(id, json.writeValueAsString(view));
            return view;
        } catch (AuthFailure notPublic) {
            put(id, HIDDEN);
            throw notPublic;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Unable to cache match view.", error);
        }
    }

    /** True when the cached view says the match is public. Always false with Redis off. */
    boolean cachedPublic(UUID id) {
        String cached = get(id);
        return cached != null && !HIDDEN.equals(cached);
    }

    void evict(UUID id) {
        if (redis == null) return;
        try { redis.delete(key(id)); } catch (RuntimeException error) { warn(error); }
    }

    private String get(UUID id) {
        if (redis == null) return null;
        try { return redis.opsForValue().get(key(id)); } catch (RuntimeException error) { warn(error); return null; }
    }

    private void put(UUID id, String value) {
        if (redis == null) return;
        try { redis.opsForValue().set(key(id), value, TTL); } catch (RuntimeException error) { warn(error); }
    }

    private static String key(UUID id) { return "raidzon:live-view:" + id; }

    private static void warn(RuntimeException error) { log.warn("Redis unavailable, using the database: {}", error.getMessage()); }
}
