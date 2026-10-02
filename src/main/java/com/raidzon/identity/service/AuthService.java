package com.raidzon.identity.service;

import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public final class AuthService {
    public record Challenge(UUID challengeId, long expiresAt, int resendAfterSeconds) {}
    public record Session(String token, long expiresAt, UUID accountId, UUID deviceId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SmsSender sms;
    private final Clock clock;
    private final String pepper;
    private final SecureRandom random = new SecureRandom();
    public AuthService(JdbcTemplate jdbc, TransactionTemplate tx, SmsSender sms, Clock clock, String pepper) {
        this.jdbc=jdbc; this.tx=tx; this.sms=sms; this.clock=clock; this.pepper=pepper;
        if (sms.available() && pepper.length() < 32) throw new IllegalStateException("Configure a strong authentication pepper before enabling SMS.");
    }
    public boolean available() { return sms.available() && pepper.length() >= 32; }
    public Session verifyWidget(String accessToken, UUID deviceId, String secret, String remoteAddress, Msg91WidgetClient widget) {
        require(widget != null && pepper.length() >= 32,503,"WIDGET_UNAVAILABLE","Phone sign-in is not configured yet.");
        device(deviceId, secret);
        require(accessToken != null && !accessToken.isBlank() && accessToken.length() <= 16384,400,"INVALID_WIDGET_TOKEN","A verification token is required.");
        tx.executeWithoutResult(status -> limit("widget-ip:"+mac(remoteAddress),30,0,clock.millis()));
        String phone = widget.verifyPhone(accessToken);
        return tx.execute(status -> {
            int inserted=jdbc.update("INSERT INTO widget_token_redemptions(token_hash,redeemed_at) VALUES (?,?) ON CONFLICT DO NOTHING",hash(accessToken),clock.millis());
            require(inserted==1,401,"WIDGET_TOKEN_USED","This verification was already used. Please sign in again.");
            jdbc.update("INSERT INTO auth_devices(id,secret_hash) VALUES (?,?) ON CONFLICT DO NOTHING",deviceId,hash(secret));
            require(hash(secret).equals(jdbc.queryForObject("SELECT secret_hash FROM auth_devices WHERE id=?",String.class,deviceId)),401,"INVALID_DEVICE","Device credentials do not match.");
            jdbc.update("INSERT INTO user_accounts(id,phone) VALUES (?,?) ON CONFLICT(phone) DO NOTHING",UUID.randomUUID(),phone);
            UUID account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE phone=?",UUID.class,phone);
            jdbc.update("UPDATE player_profiles SET claimed_by=? WHERE phone=? AND claimed_by IS NULL",account,phone);
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            long expiry=clock.millis()+43_200_000;
            jdbc.update("INSERT INTO auth_tokens(token_hash,account_id,device_id,expires_at,verified_at) VALUES (?,?,?,?,?)",hash(token),account,deviceId,expiry,clock.millis());
            return new Session(token,expiry,account,deviceId);
        });
    }
    private static void require(boolean value, int status, String code, String message) {
        if (!value) throw new AuthFailure(status,code,message);
    }
    private String mac(String value) {
        try {
            var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("Authentication hash unavailable.",error); }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    private void device(UUID id,String secret) {
        require(id!=null && secret!=null && secret.matches("[A-Za-z0-9_-]{43}"),400,"INVALID_DEVICE","Device credentials are required.");
    }
    public Challenge request(String phone, UUID deviceId, String secret, String remoteAddress) {
        require(available(),503,"SMS_UNAVAILABLE","SMS sign-in is not configured yet. You can continue scoring as a guest.");
        require(phone!=null && phone.matches("\\+[1-9][0-9]{7,14}"),400,"INVALID_PHONE","Use an international phone number.");
        device(deviceId,secret);
        long now=clock.millis(); UUID id=UUID.randomUUID();
        String code=String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1_000_000));
        tx.executeWithoutResult(status -> {
            limit("ip:"+mac(remoteAddress),30,0,now);
            limit("phone:"+mac(phone),5,60_000,now);
            jdbc.update("UPDATE auth_challenges SET consumed=true WHERE phone=? AND device_id=? AND NOT consumed",phone,deviceId);
            jdbc.update("INSERT INTO auth_challenges(id,phone,device_id,device_secret_hash,code_hash,expires_at) VALUES (?,?,?,?,?,?)",
                    id,phone,deviceId,hash(secret),mac(id+":"+code),now+300_000);
        });
        try {
            sms.sendCode(phone,code);
            jdbc.update("UPDATE auth_challenges SET delivered=true WHERE id=?",id);
        } catch (Exception failure) {
            jdbc.update("UPDATE auth_challenges SET consumed=true WHERE id=?",id);
            throw new AuthFailure(503,"SMS_DELIVERY_FAILED","Unable to send a code. Please try again later.");
        }
        return new Challenge(id,now+300_000,60);
    }
    private void limit(String key,int max,long cooldown,long now) {
        jdbc.update("INSERT INTO auth_rate_limits(key,window_start,last_request,requests) VALUES (?,?,?,0) ON CONFLICT DO NOTHING",key,now,0L);
        var row=jdbc.queryForMap("SELECT * FROM auth_rate_limits WHERE key=? FOR UPDATE",key);
        long start=((Number)row.get("window_start")).longValue(), last=((Number)row.get("last_request")).longValue();
        int count=((Number)row.get("requests")).intValue();
        if(now-start>=3_600_000){count=0;start=now;}
        require(count<max && (count==0 || now-last>=cooldown),429,"RATE_LIMITED","Too many code requests. Please wait before trying again.");
        jdbc.update("UPDATE auth_rate_limits SET window_start=?,last_request=?,requests=? WHERE key=?",start,now,count+1,key);
    }
    public Session verify(UUID id,String code,UUID deviceId,String secret) {
        require(available(),503,"SMS_UNAVAILABLE","SMS sign-in is not configured yet.");
        device(deviceId,secret);
        require(id!=null && code!=null && code.matches("[0-9]{6}"),400,"INVALID_CODE","Enter the six-digit code.");
        // Failed-attempt counters must commit; throw the public failure only after the transaction returns.
        var result=tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT * FROM auth_challenges WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty())return null;
            var row=rows.getFirst();
            if((Boolean)row.get("consumed") || !(Boolean)row.get("delivered") || ((Number)row.get("expires_at")).longValue()<=clock.millis() || ((Number)row.get("attempts")).intValue()>=5)return null;
            jdbc.update("UPDATE auth_challenges SET attempts=attempts+1 WHERE id=?",id);
            if(!deviceId.equals(row.get("device_id")) || !hash(secret).equals(row.get("device_secret_hash")) ||
                    !MessageDigest.isEqual(mac(id+":"+code).getBytes(StandardCharsets.UTF_8),row.get("code_hash").toString().getBytes(StandardCharsets.UTF_8)))return null;
            jdbc.update("INSERT INTO auth_devices(id,secret_hash) VALUES (?,?) ON CONFLICT DO NOTHING",deviceId,hash(secret));
            if(!hash(secret).equals(jdbc.queryForObject("SELECT secret_hash FROM auth_devices WHERE id=?",String.class,deviceId)))return null;
            String phone=row.get("phone").toString();
            jdbc.update("INSERT INTO user_accounts(id,phone) VALUES (?,?) ON CONFLICT(phone) DO NOTHING",UUID.randomUUID(),phone);
            UUID account=jdbc.queryForObject("SELECT id FROM user_accounts WHERE phone=?",UUID.class,phone);
            jdbc.update("UPDATE auth_challenges SET consumed=true WHERE id=?",id);
            jdbc.update("UPDATE player_profiles SET claimed_by=? WHERE phone=? AND claimed_by IS NULL",account,phone);
            byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            long expiry=clock.millis()+43_200_000;
            jdbc.update("INSERT INTO auth_tokens(token_hash,account_id,device_id,expires_at,verified_at) VALUES (?,?,?,?,?)",hash(token),account,deviceId,expiry,clock.millis());
            return new Session(token,expiry,account,deviceId);
        });
        require(result!=null,401,"INVALID_CODE","Code invalid, expired, or already used.");
        return result;
    }
    /** Verified tokens are reused for a few seconds so a burst of scoring taps skips the lookup. */
    private static final long TOKEN_CACHE_MS=20_000;
    private record CachedIdentity(AuthIdentity identity,long until){}
    private final java.util.concurrent.ConcurrentHashMap<String,CachedIdentity> verified=new java.util.concurrent.ConcurrentHashMap<>();
    public AuthIdentity authenticate(String token) {
        require(token!=null && token.matches("[A-Za-z0-9_-]{43}"),401,"UNAUTHORIZED","Sign in to synchronize matches.");
        String key=hash(token);long now=clock.millis();
        var cached=verified.get(key);
        if(cached!=null && cached.until()>now)return cached.identity();
        var rows=jdbc.query("SELECT account_id,device_id,expires_at FROM auth_tokens WHERE token_hash=? AND expires_at>? AND NOT revoked",
                (rs,index)->new CachedIdentity(new AuthIdentity(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),Math.min(rs.getLong(3),now+TOKEN_CACHE_MS)),key,now);
        if(rows.isEmpty()){verified.remove(key);}
        require(!rows.isEmpty(),401,"UNAUTHORIZED","Your session expired. Sign in again.");
        if(verified.size()>10_000)verified.clear();
        verified.put(key,rows.getFirst());
        return rows.getFirst().identity();
    }
    /** Forget cached verifications, e.g. after tokens are revoked. */
    public void forgetVerifiedTokens(){verified.clear();}
    public void logout(String token) { authenticate(token);verified.remove(hash(token));jdbc.update("UPDATE auth_tokens SET revoked=true WHERE token_hash=?",hash(token)); }
}
