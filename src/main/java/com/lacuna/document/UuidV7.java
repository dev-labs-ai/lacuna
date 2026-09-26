package com.lacuna.document;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562, section 5.7): a Unix timestamp in milliseconds followed by 74 random bits. Unlike random
 * (version 4) UUIDs, they sort by creation time, so new rows land at the end of a primary key index instead of on a
 * random page of it. Java 26 creates them with {@code UUID.ofEpochMillis}; this class can go once the project uses it.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID at(Instant time) {
        // unix_ts_ms (48 bits) | ver (4) | rand_a (12)
        long mostSignificant = (time.toEpochMilli() << 16) | 0x7000L | (RANDOM.nextLong() & 0x0fffL);
        // var (2 bits, 0b10) | rand_b (62)
        long leastSignificant = 0x8000000000000000L | (RANDOM.nextLong() & 0x3fffffffffffffffL);
        return new UUID(mostSignificant, leastSignificant);
    }
}
