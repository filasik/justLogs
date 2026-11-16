package me.skerik.justlogs.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keys kept outside the database in {@code secrets.key} (0600 where the filesystem supports it): a database dump alone
 * must not allow reversing IP hashes or forging web tokens.
 */
public final class Secrets {

    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKey hmacKey;

    private Secrets(byte[] hmac) {
        this.hmacKey = new SecretKeySpec(hmac, "HmacSHA256");
    }

    public static Secrets ephemeral() {
        return new Secrets(random());
    }

    /** Loads the file or creates it (atomic write, owner-only permissions). */
    public static Secrets loadOrCreate(Path file) throws IOException {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        String raw = props.getProperty("hmac-key", "").strip();
        if (!raw.isEmpty()) {
            byte[] decoded = Base64.getDecoder().decode(raw);
            if (decoded.length != 32) throw new IOException("hmac-key in secrets.key must be 32 bytes (base64)");
            return new Secrets(decoded);
        }
        byte[] fresh = random();
        props.setProperty("hmac-key", Base64.getEncoder().encodeToString(fresh));
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "secrets-", ".tmp");
        restrict(tmp);
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "JustLogs secrets - NEVER share or commit this file. Losing it makes stored IP hashes unlinkable.");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrict(file);
        return new Secrets(fresh);
    }

    private static byte[] random() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return b;
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem (Windows)
        }
    }

    public SecretKey hmacKey() {
        return hmacKey;
    }

    @Override
    public String toString() {
        return "Secrets[redacted]";
    }
}
