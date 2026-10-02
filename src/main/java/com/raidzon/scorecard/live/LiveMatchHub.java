package com.raidzon.scorecard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.scorecard.repository.LiveMatchRepository;
import jakarta.servlet.ServletContext;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.ServletContextAware;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pushes live match views to watchers over WebSocket at /ws/matches/{matchId}.
 * Uses Tomcat's built-in Jakarta WebSocket support, so no extra dependency is needed.
 * One database read per accepted event is shared by every watcher of that match.
 * Single-instance: subscriptions live in memory (fine for one Render instance).
 */
@Component @Profile("postgres")
public class LiveMatchHub implements ServletContextAware, DisposableBean {
    public static final String PATH = "/ws/matches/{matchId}";
    static final int MAX_PER_MATCH = 2000;
    static final int MAX_TOTAL = 5000;
    private static final Logger log = LoggerFactory.getLogger(LiveMatchHub.class);

    private final LiveMatchRepository matches;
    private final ObjectMapper json;
    private final Set<String> allowedOrigins;
    private final Map<UUID, Set<Session>> watchers = new ConcurrentHashMap<>();
    /** Whether the last view built for a watched match was public (fast pushes are only sent then). */
    private final Map<UUID, Boolean> visible = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public LiveMatchHub(LiveMatchRepository matches, ObjectMapper json,
            @Value("${RAIDZON_CORS_ORIGINS:https://raidzon.com,https://www.raidzon.com,https://raidzon.in,https://www.raidzon.in,https://raidzon-frontend.vercel.app,http://127.0.0.1:5173,http://127.0.0.1:4173}") String origins) {
        this.matches = matches;
        this.json = json;
        this.allowedOrigins = Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList());
        // Proxies (e.g. Render) drop idle sockets; a small text frame every 25s keeps them open
        // and lets clients detect a dead connection.
        keepalive.scheduleAtFixedRate(() -> broadcastAll("{\"type\":\"keepalive\"}"), 25, 25, TimeUnit.SECONDS);
    }

    @Override public void setServletContext(ServletContext context) {
        var container = (ServerContainer) context.getAttribute(ServerContainer.class.getName());
        if (container == null) { log.warn("WebSocket container unavailable; live matches fall back to polling."); return; }
        var hub = this;
        try {
            container.addEndpoint(ServerEndpointConfig.Builder.create(LiveMatchEndpoint.class, PATH)
                .configurator(new ServerEndpointConfig.Configurator() {
                    @Override public <T> T getEndpointInstance(Class<T> type) { return type.cast(new LiveMatchEndpoint(hub)); }
                    @Override public boolean checkOrigin(String origin) { return origin == null || allowedOrigins.contains(origin); }
                    @Override public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {}
                }).build());
        } catch (DeploymentException error) {
            log.warn("Could not register live match WebSocket endpoint.", error);
        }
    }

    /** Called when a watcher connects. Sends the current view right away. */
    void open(UUID matchId, Session session) {
        var set = watchers.computeIfAbsent(matchId, id -> ConcurrentHashMap.newKeySet());
        if (total.get() >= MAX_TOTAL || set.size() >= MAX_PER_MATCH) {
            close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, "Too many watchers. Try again shortly.");
            return;
        }
        if (set.add(session)) total.incrementAndGet();
        sender.submit(() -> send(session, message(matchId)));
    }

    void closed(UUID matchId, Session session) {
        var set = watchers.get(matchId);
        if (set != null && set.remove(session)) {
            total.decrementAndGet();
            if (set.isEmpty() && watchers.remove(matchId, set)) visible.remove(matchId);
        }
    }

    /** Push the latest view of a match to everyone watching it. Never blocks the caller. */
    public void publish(UUID matchId) {
        var set = watchers.get(matchId);
        if (set == null || set.isEmpty()) return;
        sender.submit(() -> {
            String text = message(matchId);
            for (var session : set) send(session, text);
        });
    }

    /**
     * Push an accepted event immediately from the state the scorer's request just computed (no
     * database read), then the full view with the event list and players' own names.
     */
    public void publish(UUID matchId, int version, com.fasterxml.jackson.databind.JsonNode projection) {
        var set = watchers.get(matchId);
        if (set == null || set.isEmpty()) return;
        if (Boolean.TRUE.equals(visible.get(matchId))) {
            try {
                String fast = json.writeValueAsString(Map.of("type", "state", "version", version, "serverTime", System.currentTimeMillis(),
                        "state", LiveMatchRepository.publicState(projection, json)));
                for (var session : set) sender.submit(() -> send(session, fast));
            } catch (Exception error) {
                log.warn("Fast live update for {} could not be built.", matchId, error);
            }
        }
        publish(matchId);
    }

    /** Stops fast pushes until the next full view confirms the match is public again. */
    public void recheckVisibility(UUID matchId) { visible.remove(matchId); }

    public int watching(UUID matchId) {
        var set = watchers.get(matchId);
        return set == null ? 0 : set.size();
    }

    private String message(UUID matchId) {
        try {
            String text = json.writeValueAsString(Map.of("type", "view", "view", matches.read(matchId)));
            if (watchers.containsKey(matchId)) visible.put(matchId, true);
            return text;
        } catch (AuthFailure notPublic) {
            visible.remove(matchId);
            return "{\"type\":\"unavailable\"}";
        } catch (Exception error) {
            log.warn("Live view for {} could not be built.", matchId, error);
            return "{\"type\":\"error\"}";
        }
    }

    private void broadcastAll(String text) {
        for (var set : watchers.values()) for (var session : set) sender.submit(() -> send(session, text));
    }

    private static void send(Session session, String text) {
        // Basic remote is not thread-safe per session; serialize writes per session.
        synchronized (session) {
            if (!session.isOpen()) return;
            try { session.getBasicRemote().sendText(text); }
            catch (IOException | IllegalStateException error) { close(session, CloseReason.CloseCodes.GOING_AWAY, "Send failed"); }
        }
    }

    static void close(Session session, CloseReason.CloseCode code, String reason) {
        try { session.close(new CloseReason(code, reason)); } catch (IOException ignored) {}
    }

    @Override public void destroy() {
        keepalive.shutdownNow();
        sender.shutdownNow();
    }
}
