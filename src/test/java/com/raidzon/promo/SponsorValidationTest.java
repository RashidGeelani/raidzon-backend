package com.raidzon.promo;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

/** Sponsor links and logos, and short codes: checks that need no database. */
class SponsorValidationTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};
    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0};
    static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    @Test void linksAreWebsitesOrPhoneNumbers() {
        assertNull(Sponsors.link(null));
        assertNull(Sponsors.link("  "));
        assertEquals("https://abcmotors.in/offers", Sponsors.link(" https://abcmotors.in/offers "));
        assertEquals("http://shop.example", Sponsors.link("http://shop.example"));
        assertEquals("+919876543210", Sponsors.link("+91 98765 43210"));
        assertEquals("01942456789", Sponsors.link("(0194) 245-6789"));
        for (String bad : new String[] {"javascript:alert(1)", "ftp://files.example", "abcmotors.in", "https://", "data:text/html,x", "12345", "https://a.in/" + "x".repeat(200)})
            assertThrows(IllegalArgumentException.class, () -> Sponsors.link(bad), bad);
    }

    @Test void logosArePngJpegOrWebpByTheirFirstBytes() {
        assertEquals("image/png", Sponsors.logo(b64(PNG)).contentType());
        assertEquals("image/jpeg", Sponsors.logo("data:image/jpeg;base64," + b64(JPEG)).contentType());
        assertEquals("image/webp", Sponsors.logo(b64(WEBP)).contentType());
        // An SVG or HTML file named .png is refused: the type comes from the bytes, not the name.
        assertThrows(IllegalArgumentException.class, () -> Sponsors.logo(b64("<svg onload=alert(1)>".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> Sponsors.logo("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> Sponsors.logo(""));
    }

    @Test void logosAreAtMost150Kb() {
        byte[] big = new byte[Sponsors.MAX_LOGO_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        assertThrows(IllegalArgumentException.class, () -> Sponsors.logo(b64(big)));
        byte[] fits = java.util.Arrays.copyOf(big, Sponsors.MAX_LOGO_BYTES);
        assertEquals("image/png", Sponsors.logo(b64(fits)).contentType());
    }

    @Test void shortCodesAvoidLookAlikeCharacters() {
        var links = new ShortLinkController(null, null, null);
        for (int i = 0; i < 500; i++) {
            String code = links.code();
            assertTrue(code.matches("[2-9A-HJ-NP-Z]{5}"), code);
        }
        assertFalse(ShortLinkController.ALPHABET.matches(".*[01OI].*"));
    }
}
