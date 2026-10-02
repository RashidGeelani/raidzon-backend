package com.raidzon.scorecard.live;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import java.util.UUID;

/** One watcher's connection. Watchers only receive; anything they send is ignored. */
public class LiveMatchEndpoint extends Endpoint {
    private final LiveMatchHub hub;
    private UUID matchId;
    LiveMatchEndpoint(LiveMatchHub hub) { this.hub = hub; }

    @Override public void onOpen(Session session, EndpointConfig config) {
        try { matchId = UUID.fromString(session.getPathParameters().get("matchId")); }
        catch (RuntimeException invalid) { LiveMatchHub.close(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid match"); return; }
        session.setMaxTextMessageBufferSize(256);
        session.setMaxIdleTimeout(90_000);
        session.addMessageHandler(String.class, (MessageHandler.Whole<String>) ignored -> {});
        hub.open(matchId, session);
    }
    @Override public void onClose(Session session, CloseReason reason) { if (matchId != null) hub.closed(matchId, session); }
    @Override public void onError(Session session, Throwable error) { if (matchId != null) hub.closed(matchId, session); }
}
