package dev.suven.jungey.core;

import java.nio.file.Path;

/**
 * The parts of the window that skills are allowed to drive.
 *
 * <p>Skills return text; this is the narrow exception for the ones that produce something
 * to look at, so the camera lives inside the HUD instead of in someone else's window.
 */
public interface Viewport {

    /** Show the live camera feed in the transcript. */
    void showCamera(String device);

    void hideCamera();

    boolean cameraVisible();

    /**
     * The most recent frame from the live feed, or null if the camera is not running.
     * A webcam can only be opened once, so this is how a photo is taken during a preview.
     */
    byte[] currentFrame();

    /** Show a captured image inline, with a caption beneath it. */
    void showImage(Path file, String caption);

    /** Whatever text is on the system clipboard, or "" when it holds none. */
    String clipboardText();

    /**
     * Say something the user did not ask for just now - a timer finishing, a reminder
     * coming due. Reaches the transcript, the voice and the desktop's notifications,
     * since the window may well not be the thing being looked at.
     */
    void announce(String text);
}
