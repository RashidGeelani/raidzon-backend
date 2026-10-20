package com.raidzon.seo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.raidzon.tournament.repository.CountingRules;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/** sitemap.xml for search engines: static pages plus every public match, scorecard, tournament and player page. */
@RestController @Profile("postgres")
public class SitemapController {
    private final JdbcTemplate jdbc;
    private final String site;
    public SitemapController(JdbcTemplate jdbc,@Value("${raidzon.site-origin:https://raidzon.com}") String site){this.jdbc=jdbc;this.site=site.replaceAll("/+$","");}

    static final int MIN_RAIDS=15;
    // ponytail: one sitemap capped at 50k URLs (the protocol limit); split into a sitemap index past that.
    @GetMapping(value="/api/v1/public/sitemap.xml",produces="application/xml")
    public String sitemap(){
        var xml=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for(String page:new String[]{"/","/help","/privacy","/guide","/guide/kabaddi-rules","/guide/how-to-score-kabaddi","/guide/kabaddi-points-table","/guide/run-a-kabaddi-tournament"})xml.append("<url><loc>").append(site).append(page).append("</loc></url>\n");
        // Matches: same visibility rule as the public live view (tournament fixture or published scorecard, never removed
        // or practice), and only finished ones with at least MIN_RAIDS raids: short or unfinished (often test) matches
        // are thin pages, and the frontend marks them noindex (api/page.js MIN_RAIDS). Scorecards repeat /watch, so
        // they are not listed.
        jdbc.query("""
            SELECT '/watch/'||m.id AS path,m.updated_at FROM matches m WHERE m.removed_at IS NULL AND NOT m.practice
                AND m.projection->>'status'='COMPLETED' AND (m.projection->>'raidNumber')::int>=?
                AND NOT """ + CountingRules.testMatch("m") + """
                AND NOT EXISTS(SELECT 1 FROM tournament_fixtures tf JOIN tournaments tt ON tt.id=tf.tournament_id
                    WHERE tf.match_id=m.id AND """ + CountingRules.testTournament("tt") + ")" + """
                AND (EXISTS(SELECT 1 FROM tournament_fixtures f WHERE f.match_id=m.id)
                    OR EXISTS(SELECT 1 FROM public_scorecards s WHERE s.match_id=m.id AND s.published))
            UNION ALL
            -- Tournaments with at least two teams (empty drafts are thin pages).
            SELECT '/tournaments/'||t.id,COALESCE((SELECT max(fm.updated_at) FROM tournament_fixtures ff JOIN matches fm ON fm.id=ff.match_id
                WHERE ff.tournament_id=t.id),t.starts_on::timestamptz)
            FROM tournaments t WHERE (SELECT count(*) FROM tournament_teams tt WHERE tt.tournament_id=t.id)>=2
                AND NOT """ + CountingRules.testTournament("t") + """
            UNION ALL
            -- Players with a public page: at least one match that counts in their stats (TournamentRepository.publicPlayer).
            -- (A tournament match: the public page groups stats by tournament, so legacy friendlies alone give no page.)
            SELECT '/players/'||l.profile_id,max(m.updated_at) FROM match_player_links l JOIN matches m ON m.id=l.match_id
            JOIN tournament_fixtures pf ON pf.match_id=m.id
            WHERE """ + com.raidzon.account.repository.AccountRepository.COUNTED + """
            GROUP BY l.profile_id
            ORDER BY updated_at DESC LIMIT 49000
            """,(org.springframework.jdbc.core.RowCallbackHandler) r->xml.append("<url><loc>").append(site).append(r.getString("path"))
                .append("</loc><lastmod>").append(DateTimeFormatter.ISO_LOCAL_DATE.format(r.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC))).append("</lastmod></url>\n"),MIN_RAIDS);
        return xml.append("</urlset>\n").toString();
    }
}
