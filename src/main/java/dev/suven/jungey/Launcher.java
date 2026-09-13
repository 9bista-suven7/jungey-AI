package dev.suven.jungey;

/**
 * Plain entry point for the shaded jar.
 *
 * <p>A class that extends {@code Application} cannot be the main class of a jar that
 * bundles JavaFX on the classpath - the toolkit refuses to start. Launching through a
 * class that does not extend it sidesteps that entirely.
 */
public final class Launcher {

    public static void main(String[] args) {
        JungeyApp.main(args);
    }
}
