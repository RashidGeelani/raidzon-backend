package com.raidzon.scorecard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import jakarta.websocket.CloseReason;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveMatchHubTest {
    private final LiveViewCache views = mock(LiveViewCache.class);
    private final LiveMatchHub hub = new LiveMatchHub(views, new ObjectMapper(), "https://raidzon.com");
    @AfterEach void stop() { hub.destroy(); }

    private static Session session() {
        var session = mock(Session.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(mock(RemoteEndpoint.Basic.class));
        return session;
    }

    @Test void perIpCapRejectsWithoutLeavingEmptyEntriesAndFreesOnClose() throws Exception {
        var sessions = new ArrayList<Session>();
        for (int i = 0; i < LiveMatchHub.MAX_PER_IP; i++) {
            var s = session(); sessions.add(s);
            assertTrue(hub.reserve(UUID.randomUUID(), "198.51.100.1", s));
        }
        UUID fresh = UUID.randomUUID();
        var rejected = session();
        hub.open(fresh, "198.51.100.1", rejected);
        verify(rejected).close(argThat(r -> r.getCloseCode() == CloseReason.CloseCodes.TRY_AGAIN_LATER));
        assertFalse(hub.tracked(fresh), "a rejected socket must not leave an empty watcher set");
        assertEquals(LiveMatchHub.MAX_PER_IP, hub.total());
        assertTrue(hub.reserve(fresh, "198.51.100.2", session()), "other clients are unaffected");

        UUID first = UUID.randomUUID();
        var s = session();
        hub.closed(first, "198.51.100.1", s); // unknown session: no change
        assertEquals(LiveMatchHub.MAX_PER_IP, hub.fromIp("198.51.100.1"));
    }

    @Test void closingTwiceReleasesOnceAndDropsEmptyMatch() {
        UUID match = UUID.randomUUID();
        var s = session();
        assertTrue(hub.reserve(match, "198.51.100.3", s));
        hub.closed(match, "198.51.100.3", s);
        hub.closed(match, "198.51.100.3", s); // onError then onClose
        assertEquals(0, hub.total());
        assertEquals(0, hub.fromIp("198.51.100.3"));
        assertFalse(hub.tracked(match));
    }

    @Test void racingConnectsNeverOvershootTheIpCap() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        UUID match = UUID.randomUUID();
        for (int i = 0; i < 200; i++) results.add(pool.submit(() -> { start.await(); return hub.reserve(match, "198.51.100.4", session()); }));
        start.countDown();
        int accepted = 0;
        for (var r : results) if (r.get()) accepted++;
        pool.shutdown();
        assertEquals(LiveMatchHub.MAX_PER_IP, accepted);
        assertEquals(LiveMatchHub.MAX_PER_IP, hub.total());
    }

    @Test void unavailableMatchIsToldThenClosed() throws Exception {
        UUID match = UUID.randomUUID();
        when(views.read(match)).thenThrow(new AuthFailure(404, "MATCH_NOT_FOUND", "Not found."));
        var s = session();
        hub.open(match, "198.51.100.5", s);
        verify(s.getBasicRemote(), timeout(2000)).sendText(LiveMatchHub.UNAVAILABLE);
        verify(s, timeout(2000)).close(argThat(r -> r.getCloseCode() == CloseReason.CloseCodes.NORMAL_CLOSURE));
    }

    @Test void clientIpIsTheRightMostNonInternalHop() {
        assertEquals("203.0.113.9", LiveMatchHub.clientIp(List.of("203.0.113.9")));
        assertEquals("203.0.113.9", LiveMatchHub.clientIp(List.of("1.1.1.1, 203.0.113.9")), "a spoofed left-most entry is ignored");
        assertEquals("203.0.113.9", LiveMatchHub.clientIp(List.of("203.0.113.9, 10.0.0.4, 100.64.1.1")));
        assertEquals("unknown", LiveMatchHub.clientIp(null));
        assertEquals("2001:db8::1", LiveMatchHub.clientIp(List.of("2001:db8::1")));
    }
}
