package com.simi.common.ulid;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID generator — 26-char Crockford base32, timestamp-sortable.
 * Format: {prefix}_{26-char ULID} (or VAR_{26-char ULID} cho Variable ID).
 * Spec: https://github.com/ulid/spec
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String generate() {
        return generate(Instant.now().toEpochMilli());
    }

    public static String generate(long timestampMs) {
        if (timestampMs < 0 || timestampMs > (1L << 48) - 1) {
            throw new IllegalArgumentException("Timestamp out of ULID 48-bit range");
        }
        char[] out = new char[26];
        // 10 chars timestamp (48 bits) — big endian, 5 bits per char
        for (int i = 9; i >= 0; i--) {
            out[i] = ENCODING[(int) (timestampMs & 0x1F)];
            timestampMs >>>= 5;
        }
        // 16 chars randomness (80 bits)
        byte[] rand = new byte[10];
        RANDOM.nextBytes(rand);
        long hi = 0L;
        long lo = 0L;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (rand[i] & 0xFFL);
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (rand[i] & 0xFFL);
        for (int i = 25; i >= 18; i--) {
            out[i] = ENCODING[(int) (lo & 0x1F)];
            lo >>>= 5;
        }
        // remaining 2 bits of lo + 38 bits from hi → 8 chars
        long mid = (hi << 2) | (lo & 0x3);
        for (int i = 17; i >= 10; i--) {
            out[i] = ENCODING[(int) (mid & 0x1F)];
            mid >>>= 5;
        }
        return new String(out);
    }

    public static String prefixed(String prefix) {
        if (IdPrefix.VARIABLE.equals(prefix)) {
            return prefix + "_" + generate();
        }
        return prefix + "_" + generate();
    }
}
