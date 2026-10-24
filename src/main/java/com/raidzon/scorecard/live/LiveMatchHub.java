package com.raidzon.scorecard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.identity.service.AuthFailure;
import com.raidzon.scorecard.repository.LiveMatchRepository;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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
 * Subscriptions live in memory per instance. With Redis on (raidzon.redis.enabled), every
 * message goes through a Redis channel so watchers on any instance receive it.
 */
@Component @Profile("postgres")
public class LiveMatchHub implements ServletContextAware, DisposableBean {
    public static final String PATH = "/ws/matches/{matchId}";
    static final int MAX_PER_MATCH = 2000;
    static final int MAX_TOTAL = 5000;
    /** One phone or browser needs a few sockets at most; this stops one client from using up the caps. */
    static final int MAX_PER_IP = 20;
    static final String UNAVAILABLE = "{\"type\":\"unavailable\"}";
    /** Endpoint user property holding the client IP read during the handshake. */
    static final String CLIENT_IP = "raidzon.clientIp";
    private static final Logger log = LoggerFactory.getLogger(LiveMatchHub.class);

    private static final String CHANNEL = "raidzon:live:";
    private final LiveViewCache views;
    private final RedisMessageListenerContainer subscriber; // null when Redis is off
    private final ObjectMapper json;
    private final Set<String> allowedOrigins;
    private final Map<UUID, Set<Session>> watchers = new ConcurrentHashMap<>();
    /** Whether the last view built for a watched match was public (fast pushes are only sent then). */
    private final Map<UUID, Boolean> visible = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final Map<String, Integer> perIp = new java.util.HashMap<>(); // guarded by this
    private final ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    public LiveMatchHub(LiveViewCache views, ObjectMapper json,
            @Value("${RAIDZON_CORS_ORIGINS:https://raidzon.com,https://www.raidzon.com,https://raidzon.in,https://www.raidzon.in,https://raidzon-frontend.vercel.app,http://127.0.0.1:5173,http://127.0.0.1:4173}") String origins) {
        this.views = views;
        this.json = json;
        this.allowedOrigins = Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList());
        // Proxies (e.g. Render) drop idle sockets; a small text frame every 25s keeps them open
        // and lets clients detect a dead connection.
        keepalive.scheduleAtFixedRate(() -> broadcastAll("{\"type\":\"keepalive\"}"), 25, 25, TimeUnit.SECONDS);
        subscriber = views.enabled() ? subscribe(views) : null;
        if (subscriber != null) startSubscriber();
    }

    /** Start fails while Redis is unreachable; keep retrying so the app still boots without it. */
    private void startSubscriber() {
        try { subscriber.start(); log.info("Live updates fan out through Redis."); }
        catch (RuntimeException down) {
            log.warn("Redis unavailable, live updates stay on this instance; retrying in 10s: {}", down.getMessage());
            keepalive.schedule(this::startSubscriber, 10, TimeUnit.SECONDS);
        }
    }

    /** Delivers every instance's messages to this instance's watchers. Reconnects on its own if Redis drops. */
    private RedisMessageListenerContainer subscribe(LiveViewCache views) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(views.redisConnections());
        container.addMessageListener((message, pattern) -> {
            try { deliver(UUID.fromString(new String(message.getChannel()).substring(CHANNEL.length())), new String(message.getBody())); }
            catch (RuntimeException bad) { log.warn("Ignored live message on {}.", new String(message.getChannel())); }
        }, new PatternTopic(CHANNEL + "*"));
        container.afterPropertiesSet();
        return container;
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
                    // Tomcat gives each handshake its own config copy, so this property is per connection.
                    @Override public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
                        config.getUserProperties().put(CLIENT_IP, clientIp(request.getHeaders().get("X-Forwarded-For")));
                    }
                }).build());
        } catch (DeploymentException error) {
            log.warn("Could not register live match WebSocket endpoint.", error);
        }
    }

    /**
     * The client's IP as Tomcat's RemoteIpValve would see it: the right-most X-Forwarded-For entry that is
     * not an internal proxy (Render appends the real peer, so a client cannot spoof it by sending its own header).
     */
    static String clientIp(java.util.List<String> forwardedFor) {
        if (forwardedFor == null || forwardedFor.isEmpty()) return "unknown";
        var hops = String.join(",", forwardedFor).split(",");
        String ip = "unknown";
        for (int i = hops.length - 1; i >= 0; i--) {
            ip = hops[i].strip();
            if (!internal(ip)) break;
        }
        return ip.isEmpty() ? "unknown" : ip;
    }
    private static boolean internal(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]{2,45}")) return false; // only literal addresses, never a DNS lookup
        try {
            var address = java.net.InetAddress.getByName(ip);
            byte[] b = address.getAddress();
            boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
            boolean uniqueLocal = b.length == 16 && (b[0] & 0xfe) == 0xfc;              // fc00::/7
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || cgnat || uniqueLocal;
        } catch (java.net.UnknownHostException invalid) { return false; }
    }

    /** Called when a watcher connects. Sends the current view right away, or closes when a cap is reached. */
    void open(UUID matchId, String ip, Session session) {
        if (!reserve(matchId, ip, session)) {
            close(session, CloseReason.CloseCodes.TRY_AGAIN_LATER, "Too many watchers. Try again shortly.");
            return;
        }
        sender.submit(() -> send(session, message(matchId, false)));
    }

    /** Checks every cap and registers the watcher in one step, so racing connects can't overshoot or leave empty entries. */
    // ponytail: one lock for connects/disconnects only (sends don't take it); stripe per match if connects ever contend.
    synchronized boolean reserve(UUID matchId, String ip, Session session) {
        var set = watchers.get(matchId);
        if (total.get() >= MAX_TOTAL || (set != null && set.size() >= MAX_PER_MATCH) || perIp.getOrDefault(ip, 0) >= MAX_PER_IP) return false;
        if (set == null) watchers.put(matchId, set = ConcurrentHashMap.newKeySet());
        if (set.add(session)) { total.incrementAndGet(); perIp.merge(ip, 1, Integer::sum); }
        return true;
    }

    synchronized void closed(UUID matchId, String ip, Session session) {
        var set = watchers.get(matchId);
        if (set != null && set.remove(session)) {
            total.decrementAndGet();
            perIp.computeIfPresent(ip, (key, count) -> count <= 1 ? null : count - 1);
            if (set.isEmpty()) { watchers.remove(matchId); visible.remove(matchId); }
        }
    }

    int total() { return total.get(); }
    synchronized int fromIp(String ip) { return perIp.getOrDefault(ip, 0); }
    boolean tracked(UUID matchId) { return watchers.containsKey(matchId); }

    /** Push the latest view of a match to everyone watching it. Never blocks the caller. */
    public void publish(UUID matchId) {
        // With Redis on, watchers may be on another instance and the cache must be refreshed anyway.
        if (subscriber == null && watching(matchId) == 0) return;
        sender.submit(() -> fanOut(matchId, message(matchId, true)));
    }

    /**
     * Push an accepted event immediately from the state the scorer's request just computed (no
     * database read), then the full view with the event list and players' own names.
     */
    public void publish(UUID matchId, int version, com.fasterxml.jackson.databind.JsonNode projection) {
        if (subscriber == null && watching(matchId) == 0) return;
        if (subscriber == null ? Boolean.TRUE.equals(visible.get(matchId)) : views.cachedPublic(matchId)) {
            try {
                String fast = json.writeValueAsString(Map.of("type", "state", "version", version, "serverTime", System.currentTimeMillis(),
                        "state", LiveMatchRepository.publicState(projection, json)));
                sender.submit(() -> fanOut(matchId, fast));
            } catch (Exception error) {
                log.warn("Fast live update for {} could not be built.", matchId, error);
            }
        }
        publish(matchId);
    }

    /** Stops fast pushes until the next full view confirms the match is public again. */
    public void recheckVisibility(UUID matchId) { visible.remove(matchId); views.evict(matchId); }

    public int watching(UUID matchId) {
        var set = watchers.get(matchId);
        return set == null ? 0 : set.size();
    }

    /** Sends to watchers on every instance through Redis, or straight to local watchers. */
    private void fanOut(UUID matchId, String text) {
        if (subscriber != null && subscriber.isRunning()) {
            try { views.redisPublish(CHANNEL + matchId, text); return; }
            catch (RuntimeException error) { log.warn("Redis publish failed, sending to this instance only: {}", error.getMessage()); }
        }
        deliver(matchId, text);
    }

    private void deliver(UUID matchId, String text) {
        var set = watchers.get(matchId);
        if (set != null) for (var session : set) sender.submit(() -> send(session, text));
    }

    private String message(UUID matchId, boolean fresh) {
        try {
            String text = json.writeValueAsString(Map.of("type", "view", "view", fresh ? views.refresh(matchId) : views.read(matchId)));
            if (watchers.containsKey(matchId)) visible.put(matchId, true);
            return text;
        } catch (AuthFailure notPublic) {
            visible.remove(matchId);
            return UNAVAILABLE;
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
            catch (IOException | IllegalStateException error) { close(session, CloseReason.CloseCodes.GOING_AWAY, "Send failed"); return; }
            // Nothing more will come for a private or missing match; the app polls and reconnects if it becomes public.
            if (UNAVAILABLE.equals(text)) close(session, CloseReason.CloseCodes.NORMAL_CLOSURE, "Match not available");
        }
    }

    static void close(Session session, CloseReason.CloseCode code, String reason) {
        try { session.close(new CloseReason(code, reason)); } catch (IOException ignored) {}
    }

    @Override public void destroy() {
        if (subscriber != null) try { subscriber.destroy(); } catch (Exception ignored) {}
        keepalive.shutdownNow();
        sender.shutdownNow();
    }
}
