package com.edu.submission.shared.ids;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class Ids {

    private Ids() {
    }

    public static UUID newUuidV7() {
        long timestamp = System.currentTimeMillis();
        long randomA = ThreadLocalRandom.current().nextLong() & 0x0FFFL;
        long randomB = ThreadLocalRandom.current().nextLong();
        long mostSignificant = (timestamp << 16) | 0x7000L | randomA;
        long leastSignificant = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}

