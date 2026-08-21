package io.wyrmgate.iam.platform.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Application-side UUIDv7 generator.
 *
 * <p>The encoded timestamp is an implementation detail used for index locality.
 * Callers must treat returned identifiers as opaque and use persisted timestamps
 * for business chronology.</p>
 */
public final class UuidV7Generator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long RAND_A_MASK = 0x0FFFL;
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long VERSION_7 = 0x7000L;
    private static final long RFC_4122_VARIANT = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final SecureRandom random;
    private long lastMillis = -1;
    private int randA;

    public UuidV7Generator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public synchronized UUID nextId() {
        long now = clock.millis();
        long timestamp = Math.max(now, lastMillis);

        if (timestamp > lastMillis) {
            randA = random.nextInt(1 << 12);
        } else if (randA < RAND_A_MASK) {
            randA++;
        } else {
            timestamp = lastMillis + 1;
            randA = random.nextInt(1 << 12);
        }

        lastMillis = timestamp;

        long mostSignificantBits = ((timestamp & TIMESTAMP_MASK) << 16)
                | VERSION_7
                | (randA & RAND_A_MASK);
        long leastSignificantBits = RFC_4122_VARIANT | (random.nextLong() & RAND_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
