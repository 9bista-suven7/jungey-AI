package dev.suven.jungeynotepad;

import dev.suven.jungeynotepad.ui.Notepad;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * jungey-notepad on its own, or with files, opens the window; with a command it does that
 * and exits.
 *
 * <p>Not an {@code Application} itself: a class that extends it cannot be the main class of
 * a jar with JavaFX on the classpath, and a command should not start the toolkit anyway.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (Cli.isCommand(args)) {
            System.exit(Cli.run(args, System.out));
        }
        // What is left is files to open, as a file manager or "Open with" passes them - to
        // the window already open, if there is one.
        String[] files = Arrays.stream(args).filter(a -> !a.startsWith("--")).toArray(String[]::new);
        List<Path> paths = Arrays.stream(files).map(Path::of).toList();
        if (Instance.handOff(paths)) return;
        Notepad.main(files);
    }
}
