package dev.suven.jungey.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

/**
 * Which Jungey this is: the version, and when it was built.
 *
 * <p>The build time is how two Jungeys decide which of them should be running. Maven
 * writes it into {@code jungey-build.properties} on every build, so a jar built from code
 * pulled this morning is always newer than the one that has been open since yesterday.
 */
public final class Build {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = Build.class.getResourceAsStream("/jungey-build.properties")) {
            if (in != null) PROPS.load(in);
        } catch (Exception e) {
            // Falls back to the jar's own timestamp below.
        }
    }

    private Build() {
    }

    public static String version() {
        String v = PROPS.getProperty("version", "");
        return v.isBlank() || v.startsWith("${") ? "dev" : v;
    }

    /** When this build was made, as UTC ISO-8601 - so comparing two as text compares them in time. */
    public static String stamp() {
        String built = PROPS.getProperty("built", "");
        if (!built.isBlank() && !built.startsWith("${")) return built;

        // Unfiltered resources, as from an IDE: the classes' own age is the next best thing.
        try {
            Path code = Path.of(Build.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.getLastModifiedTime(code).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (Exception e) {
            return Instant.EPOCH.toString();
        }
    }
}
