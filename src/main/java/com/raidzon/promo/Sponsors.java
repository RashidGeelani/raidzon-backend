package com.raidzon.promo;

import com.raidzon.identity.service.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Tournament sponsors: name, level (one Title sponsor plus partners, 4 in all), an optional website
 * or phone link, and a small logo. The public tournament view lists them with a logo URL; the logo
 * itself is served by SponsorController with long caching.
 */
@Component @Profile("postgres")
public class Sponsors {
    public static final int MAX_SPONSORS = 4;
    public static final int MAX_LOGO_BYTES = 150 * 1024;

    /** logo: base64 image bytes; leave it out when editing to keep the current logo. */
    public record Input(UUID id, String name, String level, String link, String logo) {}
    /** logoUrl: path on the API, with the last change time so a new logo is fetched past the cache. */
    public record Sponsor(UUID id, String name, String level, String link, String logoUrl) {}
    public record Logo(byte[] bytes, String contentType) {}

    private final JdbcTemplate jdbc;
    public Sponsors(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Title sponsor first, then partners in the order they were added. */
    public List<Sponsor> list(UUID tournamentId) {
        return jdbc.query("""
            SELECT id,name,level,link,updated_at FROM tournament_sponsors WHERE tournament_id=?
            ORDER BY level='TITLE' DESC, sort_order, created_at, id""",
            (r, i) -> new Sponsor(r.getObject("id", UUID.class), r.getString("name"), r.getString("level"), r.getString("link"),
                "/api/v1/public/sponsors/" + r.getObject("id", UUID.class) + "/logo?v=" + r.getTimestamp("updated_at").getTime()),
            tournamentId);
    }

    /** Adds a sponsor, or edits one of this tournament's (same id). The caller has checked and locked the tournament. */
    public void save(UUID tournamentId, Input input) {
        if (input == null || input.id() == null) throw new IllegalArgumentException("Choose a sponsor.");
        String name = input.name() == null ? "" : input.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("Sponsor names are 1 to 60 characters.");
        if (!"TITLE".equals(input.level()) && !"PARTNER".equals(input.level())) throw new IllegalArgumentException("Choose Title sponsor or partner.");
        String link = link(input.link());
        var existing = jdbc.queryForList("SELECT tournament_id FROM tournament_sponsors WHERE id=?", UUID.class, input.id());
        if (!existing.isEmpty() && !existing.getFirst().equals(tournamentId)) throw new IllegalArgumentException("Sponsor ID was reused.");
        int others = jdbc.queryForObject("SELECT count(*) FROM tournament_sponsors WHERE tournament_id=? AND id<>?", Integer.class, tournamentId, input.id());
        if (others >= MAX_SPONSORS) throw new IllegalArgumentException("A tournament can have up to " + MAX_SPONSORS + " sponsors.");
        if ("TITLE".equals(input.level()) && jdbc.queryForObject(
                "SELECT count(*) FROM tournament_sponsors WHERE tournament_id=? AND id<>? AND level='TITLE'", Integer.class, tournamentId, input.id()) > 0)
            throw new IllegalArgumentException("This tournament already has a Title sponsor.");
        Logo logo = input.logo() == null ? null : logo(input.logo());
        if (existing.isEmpty()) {
            if (logo == null) throw new IllegalArgumentException("Add the sponsor's logo.");
            jdbc.update("""
                INSERT INTO tournament_sponsors(id,tournament_id,name,level,link,logo,content_type,sort_order)
                VALUES (?,?,?,?,?,?,?,(SELECT COALESCE(max(sort_order)+1,0) FROM tournament_sponsors WHERE tournament_id=?))""",
                input.id(), tournamentId, name, input.level(), link, logo.bytes(), logo.contentType(), tournamentId);
        } else if (logo == null) {
            jdbc.update("UPDATE tournament_sponsors SET name=?,level=?,link=?,updated_at=now() WHERE id=?", name, input.level(), link, input.id());
        } else {
            jdbc.update("UPDATE tournament_sponsors SET name=?,level=?,link=?,logo=?,content_type=?,updated_at=now() WHERE id=?",
                name, input.level(), link, logo.bytes(), logo.contentType(), input.id());
        }
    }

    /** Removes a sponsor; tournamentId null means any tournament (admin). Returns the sponsor's tournament, or null when not found. */
    public UUID remove(UUID sponsorId, UUID tournamentId) {
        var rows = jdbc.queryForList("DELETE FROM tournament_sponsors WHERE id=? AND (?::uuid IS NULL OR tournament_id=?) RETURNING tournament_id",
            UUID.class, sponsorId, tournamentId, tournamentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Logo logo(UUID sponsorId) {
        var rows = jdbc.query("SELECT logo,content_type FROM tournament_sponsors WHERE id=?",
            (r, i) -> new Logo(r.getBytes("logo"), r.getString("content_type")), sponsorId);
        if (rows.isEmpty()) throw new AuthFailure(404, "SPONSOR_NOT_FOUND", "Sponsor not found.");
        return rows.getFirst();
    }

    /** A website (http or https) or a phone number; blank means no link. */
    static String link(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return null;
        if (value.length() <= 200 && value.matches("(?i)https?://[^\\s/$.?#][^\\s<>\"]*")) return value;
        String phone = value.replaceAll("[\\s()-]", "");
        if (phone.matches("\\+?[0-9]{7,15}")) return phone;
        throw new IllegalArgumentException("The sponsor link must be a website starting with http:// or https://, or a phone number.");
    }

    /** Decodes the base64 logo and checks it is a PNG, JPEG or WebP image (by its first bytes) of at most 150 KB. */
    static Logo logo(String base64) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64.replaceFirst("^data:[^,]*,", "")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("The logo could not be read. Choose the image again."); }
        if (bytes.length == 0 || bytes.length > MAX_LOGO_BYTES) throw new IllegalArgumentException("Logos must be 150 KB or smaller.");
        String type = imageType(bytes);
        if (type == null) throw new IllegalArgumentException("Logos must be PNG, JPG or WebP images.");
        return new Logo(bytes, type);
    }

    static String imageType(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
