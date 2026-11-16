package me.skerik.justlogs.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilTest {

    @TempDir Path dir;

    @Test
    void durationsParseAndFormat() {
        assertEquals(OptionalLong.of(90 * 60), Durations.parseSeconds("1h30m"));
        assertEquals(OptionalLong.of(14 * 86_400), Durations.parseSeconds("2w"));
        assertEquals(OptionalLong.of(45), Durations.parseSeconds("45"));
        assertTrue(Durations.parseSeconds("0s").isEmpty());
        assertTrue(Durations.parseSeconds("abc").isEmpty());
        assertTrue(Durations.parseSeconds("").isEmpty());
        assertTrue(Durations.parseSeconds("99999999d").isEmpty());
        assertEquals("1d 2h", Durations.format(86_400 + 2 * 3600 + 5));
        assertEquals("5m 30s", Durations.format(330));
        assertEquals("0s", Durations.format(-4));
    }

    @Test
    void secretsAreCreatedOnceAndReusedWithOwnerOnlyPermissions() throws Exception {
        Path file = dir.resolve("sub/secrets.key");
        Secrets first = Secrets.loadOrCreate(file);
        Secrets again = Secrets.loadOrCreate(file);
        assertEquals(IpHmac.hashIp(first.hmacKey(), "1.2.3.4"), IpHmac.hashIp(again.hmacKey(), "1.2.3.4"));
        try {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX filesystem
        }
        assertEquals("Secrets[redacted]", first.toString());
    }

    @Test
    void ipHashesAreKeyedNormalizedAndShort() {
        Secrets a = Secrets.ephemeral();
        Secrets b = Secrets.ephemeral();
        String h = IpHmac.hashIp(a.hmacKey(), "10.0.0.1");
        assertEquals(43, h.length());
        assertEquals(h, IpHmac.hashIp(a.hmacKey(), "/10.0.0.1"));
        assertNotEquals(h, IpHmac.hashIp(b.hmacKey(), "10.0.0.1"));
        assertNotEquals(h, IpHmac.hashIp(a.hmacKey(), "10.0.0.2"));
        assertEquals("::1", IpHmac.normalizeIp("[::1]"));
    }
}
