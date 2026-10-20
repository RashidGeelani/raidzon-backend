package com.raidzon.scorecard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.scorecard.dto.LiveMatchView;
import com.raidzon.scorecard.repository.LiveMatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveViewCacheTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LiveMatchRepository db = mock(LiveMatchRepository.class);
    private final UUID id = UUID.randomUUID();
    private final LiveMatchView view = new LiveMatchView(id, json.createObjectNode().put("home", 7), List.of(), 3, 1_000, 900, 0, null, 800L, "Srinagar");

    @SuppressWarnings("unchecked")
    private LiveViewCache cache(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(redis);
        return new LiveViewCache(db, json, provider, redis != null);
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedis(HashMap<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(a -> store.get(a.<String>getArgument(0)));
        doAnswer(a -> store.put(a.getArgument(0), a.getArgument(1))).when(ops).set(anyString(), anyString(), any(Duration.class));
        return redis;
    }

    @Test
    void secondReadComesFromRedisWithFreshServerTime() {
        when(db.read(id)).thenReturn(view);
        var cache = cache(fakeRedis(new HashMap<>()));
        cache.read(id);
        var cached = cache.read(id);
        verify(db, times(1)).read(id);
        assertEquals(7, cached.state().get("home").asInt());
        assertEquals(3, cached.version());
        assertTrue(cached.serverTime() > view.serverTime(), "serverTime must be the time of the read");
        assertTrue(cache.cachedPublic(id));
    }

    @Test
    void notPublicIsCachedToo() {
        when(db.read(id)).thenThrow(LiveMatchRepository.notPublic());
        var cache = cache(fakeRedis(new HashMap<>()));
        assertThrows(AuthFailure.class, () -> cache.read(id));
        assertThrows(AuthFailure.class, () -> cache.read(id));
        verify(db, times(1)).read(id);
        assertFalse(cache.cachedPublic(id));
    }

    @Test
    void redisDownFallsBackToDatabase() {
        when(db.read(id)).thenReturn(view);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        var cache = cache(redis);
        assertEquals(3, cache.read(id).version());
        assertEquals(3, cache.read(id).version());
        verify(db, times(2)).read(id);
    }

    @Test
    void offReadsDatabaseEveryTime() {
        when(db.read(id)).thenReturn(view);
        var cache = cache(null);
        cache.read(id);
        cache.read(id);
        verify(db, times(2)).read(id);
        assertFalse(cache.enabled());
    }
}
