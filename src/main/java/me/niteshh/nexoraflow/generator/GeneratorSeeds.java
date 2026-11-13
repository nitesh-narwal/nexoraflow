package me.niteshh.nexoraflow.generator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

import net.datafaker.Faker;

/**
 * Deterministic random sources. Every generation stage derives its own {@link Random} from the master seed and
 * the stage name (SHA-256), so a stage's output depends only on the seed, its inputs and its own draws - never on
 * how many numbers another stage happened to consume. {@code java.util.Random} is an LCG whose algorithm is
 * specified by the JDK, so sequences are identical across JVMs and platforms.
 */
public final class GeneratorSeeds {

    private final long seed;

    public GeneratorSeeds(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    public Random random(String stage) {
        return new Random(derive(stage));
    }

    /** Faker whose every draw comes from the stage's seeded random source. Locale falls back to English. */
    public Faker faker(String stage, String languageTag) {
        return new Faker(Locale.forLanguageTag(languageTag), random(stage + "|faker|" + languageTag));
    }

    long derive(String stage) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((seed + ":" + stage).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
