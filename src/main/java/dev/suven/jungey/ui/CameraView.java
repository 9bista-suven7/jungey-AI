package dev.suven.jungey.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live webcam feed, rendered inside the HUD.
 *
 * <p>ffmpeg is asked for an MJPEG stream on stdout and the frames are pulled off it one
 * at a time. That keeps the camera a process call like everything else here, with no
 * native image library pulled into the build.
 */
public class CameraView extends VBox {

    private static final int FPS = 12;
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private final ImageView frame = new ImageView();
    private final Label caption = new Label("CAMERA");

    /**
     * Holds the newest frame until the UI thread picks it up. Frames arrive faster than
     * JavaFX will repaint, so the pending one is overwritten rather than queued - showing
     * the latest image matters, showing every image does not.
     */
    private final AtomicReference<byte[]> pending = new AtomicReference<>();
    private final AtomicReference<byte[]> latest = new AtomicReference<>();

    private Process process;
    private Thread pump;
    private volatile boolean running;

    public CameraView() {
        setSpacing(6);
        setPadding(new Insets(10));
        getStyleClass().add("camera-view");

        caption.getStyleClass().add("camera-caption");

        frame.setFitWidth(440);
        frame.setPreserveRatio(true);
        frame.setSmooth(true);

        getChildren().addAll(caption, frame);
    }

    public boolean isRunning() {
        return running;
    }

    /** The newest frame as JPEG bytes, or null before the first one arrives. */
    public byte[] latestFrame() {
        return latest.get();
    }

    public void start(String device) throws IOException {
        if (running) return;

        process = new ProcessBuilder(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "v4l2", "-video_size", WIDTH + "x" + HEIGHT, "-r", String.valueOf(FPS),
                "-i", device,
                "-f", "mjpeg", "-q:v", "6", "-"))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        running = true;
        pump = new Thread(this::readFrames, "jungey-camera");
        pump.setDaemon(true);
        pump.start();
    }

    public void stop() {
        running = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (pump != null) {
            pump.interrupt();
            pump = null;
        }
        pending.set(null);
        latest.set(null);
        Platform.runLater(() -> frame.setImage(null));
    }

    /**
     * Split the MJPEG stream on JPEG boundaries. Every frame starts FF D8 and ends FF D9,
     * and those bytes cannot occur inside the compressed data, so scanning for them is
     * enough to cut one picture from the next.
     */
    private void readFrames() {
        try (InputStream in = new BufferedInputStream(process.getInputStream(), 1 << 16)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
            boolean inFrame = false;
            int previous = -1;
            int current;

            while (running && (current = in.read()) != -1) {
                if (!inFrame && previous == 0xFF && current == 0xD8) {
                    buffer.reset();
                    buffer.write(0xFF);
                    buffer.write(0xD8);
                    inFrame = true;
                    previous = -1;
                    continue;
                }

                if (inFrame) {
                    buffer.write(current);
                    if (previous == 0xFF && current == 0xD9) {
                        inFrame = false;
                        publish(buffer.toByteArray());
                    }
                }
                previous = current;
            }
        } catch (IOException e) {
            // The stream ends when the camera is closed; nothing to report.
        } finally {
            running = false;
        }
    }

    private void publish(byte[] jpeg) {
        latest.set(jpeg);

        // Only schedule a repaint when the previous one has already been collected.
        if (pending.getAndSet(jpeg) == null) {
            Platform.runLater(() -> {
                byte[] data = pending.getAndSet(null);
                if (data != null) {
                    frame.setImage(new Image(new ByteArrayInputStream(data)));
                }
            });
        }
    }
}
