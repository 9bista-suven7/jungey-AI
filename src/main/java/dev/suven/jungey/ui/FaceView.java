package dev.suven.jungey.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.voice.VoiceMeter;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Jungey's face: a photograph that talks.
 *
 * <p>The lips move with the voice as it is heard - the jaw by how loud and how open each
 * sound is, the lips drawn wide over the teeth on an "s" - and the rest of the face does what
 * a face does while nobody is thinking about it. The eyes blink every few seconds and now and
 * then twice, glance about, look up and away while an answer is being worked out. The head
 * drifts and breathes, nods a little into what it is saying, and the brows lift on the words
 * said loudest. A light on the face, where there is one, glows brighter while thinking.
 *
 * <p>The picture and where its features are come from face.json - by default
 * ~/.local/share/jungey/face/face.json, which scripts/setup-face.sh writes. Without one
 * Jungey shows the reactor instead.
 */
public final class FaceView extends StackPane implements Avatar {

    /** Height on screen; the width follows the picture. */
    private static final double HEIGHT = 288;

    /**
     * Lips need 30 frames a second to read as speech. Blinks and a drifting head do not,
     * and each frame costs a few milliseconds, so a face that is not talking runs at half that.
     */
    private static final long TALKING_NANOS = 1_000_000_000L / 30;
    private static final long RESTING_NANOS = 1_000_000_000L / 15;

    /** A blink: shut in 70 ms, held for 30, open again over 140. */
    private static final double BLINK_S = 0.24;

    private static final Color CYAN = Color.web("#38e8ff");
    private static final Color RED = Color.web("#ff4d5e");

    private final VoiceMeter meter;
    private final FaceRig rig;
    private final FaceRig.Pose pose = new FaceRig.Pose();
    private final WritableImage frame;
    private final int[] pixels;
    private final int frameW, frameH;
    private final double unit;
    private final DropShadow halo;
    private final AnimationTimer timer;
    private final Random random = new Random();

    private State state = State.IDLE;
    private long lastFrame;
    private double time;

    private double open, part, width, emphasis, lean;
    private double untilBlink = 1.5, blinkAt = -1;
    private double untilGlance, lookX, lookY;
    private double haloGlow = -1;
    private boolean haloRed;

    /** The face ui.face describes, or empty for the reactor: none installed, turned off, or unreadable. */
    public static Optional<FaceView> load(VoiceMeter meter) {
        Config cfg = Config.get();
        String mode = cfg.str("ui.avatar", "auto").toLowerCase(Locale.ENGLISH);
        if (mode.equals("reactor")) return Optional.empty();

        String home = System.getProperty("user.home");
        Path file = Path.of(cfg.str("ui.face", home + "/.local/share/jungey/face/face.json")
                .replaceFirst("^~", home));
        if (!Files.isRegularFile(file)) {
            if (mode.equals("face")) {
                System.err.println("[jungey] no face at " + file + " - scripts/setup-face.sh installs one");
            }
            return Optional.empty();
        }
        try {
            return Optional.of(new FaceView(file, meter));
        } catch (IOException | RuntimeException e) {
            System.err.println("[jungey] could not use the face at " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private FaceView(Path file, VoiceMeter meter) throws IOException {
        this.meter = meter;
        JsonNode spec = new ObjectMapper().readTree(file.toFile());
        Path picture = file.resolveSibling(spec.path("image").asText("face.jpg"));
        String url = picture.toUri().toString();

        Image full = new Image(url);
        if (full.isError()) throw new IOException("cannot read " + picture);

        // Drawn at the screen's own resolution, so a HiDPI screen gets a sharp face.
        double scale = Math.max(1, Math.min(2, Screen.getPrimary().getOutputScaleY()));
        frameH = (int) Math.round(HEIGHT * scale);
        frameW = (int) Math.round(frameH * full.getWidth() / full.getHeight());
        Image sized = new Image(url, frameW, frameH, false, true);
        int[] source = new int[frameW * frameH];
        sized.getPixelReader().getPixels(0, 0, frameW, frameH, PixelFormat.getIntArgbInstance(), source, 0, frameW);

        rig = new FaceRig(source, frameW, frameH, landmarks(spec).scaled(frameW / full.getWidth()));
        pixels = new int[frameW * frameH];
        frame = new WritableImage(frameW, frameH);
        unit = frameH / 300.0;

        double viewW = frameW / scale;
        ImageView view = new ImageView(frame);
        view.setFitWidth(viewW);
        view.setFitHeight(HEIGHT);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(viewW, HEIGHT);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        view.setClip(clip);

        // The glow sits on a plate behind the picture, so it is blurred once per change of
        // colour rather than recomputed from the picture every frame.
        Region plate = new Region();
        plate.getStyleClass().add("face-plate");
        halo = new DropShadow(BlurType.GAUSSIAN, CYAN.deriveColor(0, 1, 1, 0.35), 22, 0.25, 0, 0);
        plate.setEffect(halo);

        Region rim = new Region();
        rim.getStyleClass().add("face-rim");
        rim.setMouseTransparent(true);

        getChildren().addAll(plate, view, rim);
        setMaxSize(viewW, HEIGHT);
        getStyleClass().add("face");

        draw();

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                boolean talking = open > 0.01 || part > 0.01 || meter.now().energy() > 0;
                long interval = talking || blinkAt >= 0 ? TALKING_NANOS : RESTING_NANOS;
                if (lastFrame != 0 && now - lastFrame < interval) return;
                double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
                lastFrame = now;
                step(dt);
                draw();
            }
        };
    }

    @Override
    public void setState(State next) {
        if (next == state) return;
        state = next;
        untilGlance = 0;   // a change of mind shows in the eyes first
    }

    @Override
    public void start() {
        timer.start();
    }

    @Override
    public void stop() {
        timer.stop();
        lastFrame = 0;
    }

    @Override
    public Node node() {
        return this;
    }

    private void draw() {
        rig.render(pose, pixels);
        frame.getPixelWriter().setPixels(0, 0, frameW, frameH,
                PixelFormat.getIntArgbPreInstance(), pixels, 0, frameW);
    }

    private void step(double dt) {
        time += dt;
        VoiceMeter.Reading voice = meter.now();

        // A jaw snaps open on a syllable and settles back a little more slowly.
        open = ease(open, voice.open(), voice.open() > open ? 0.03 : 0.06, dt);
        part = ease(part, Math.max(0, voice.width()) * Math.min(1, voice.energy() * 1.6), 0.04, dt);
        width = ease(width, voice.width() * Math.min(1, voice.energy() * 2), 0.06, dt);
        // The loudness of the phrase rather than the syllable, for nods and raised brows.
        emphasis = ease(emphasis, voice.energy(), 0.3, dt);

        pose.open = open;
        pose.part = part;
        pose.width = width;
        pose.brow = ease(pose.brow,
                (state == State.THINKING ? 0.3 : 0) + Math.max(0, emphasis - 0.45) * 1.4, 0.15, dt);

        blink(dt);
        glance(dt);

        // Never quite still: two slow drifts that never line up, breathing, and a small nod
        // that comes and goes with the voice. Thinking tips the head to one side.
        lean = ease(lean, state == State.THINKING ? Math.toRadians(1.6) : 0, 0.4, dt);
        pose.tilt = Math.toRadians(0.8 * Math.sin(0.37 * time) + 0.45 * Math.sin(0.91 * time + 1.7)
                + 0.6 * emphasis * Math.sin(2.3 * time)) + lean;
        pose.shiftX = unit * (1.0 * Math.sin(0.29 * time + 0.5) + 0.45 * Math.sin(0.83 * time));
        pose.shiftY = unit * (0.5 * Math.sin(2 * Math.PI * 0.2 * time) + 0.35 * Math.sin(0.53 * time + 2.1)
                + 1.6 * emphasis * (0.5 + 0.5 * Math.sin(4.7 * time)));

        double shine = switch (state) {
            case IDLE -> 0.3 + 0.08 * Math.sin(1.3 * time);
            case THINKING -> 0.5 + 0.4 * (0.5 + 0.5 * Math.sin(7 * time));
            case SPEAKING -> 0.4;
            case ERROR -> 0.7;
        };
        pose.glow = ease(pose.glow, Math.max(shine, 0.3 + 0.55 * open), 0.08, dt);
        pose.alarm = ease(pose.alarm, state == State.ERROR ? 1 : 0, 0.12, dt);

        // Blurring the glow again is the costly part of drawing it; only when it shows.
        boolean red = state == State.ERROR;
        if (red != haloRed || Math.abs(pose.glow - haloGlow) > 0.04) {
            haloRed = red;
            haloGlow = pose.glow;
            halo.setColor((red ? RED : CYAN).deriveColor(0, 1, 1, 0.22 + 0.35 * haloGlow));
            halo.setRadius(18 + 12 * haloGlow);
        }
    }

    private void blink(double dt) {
        if (blinkAt < 0) {
            untilBlink -= dt;
            if (untilBlink > 0) return;
            blinkAt = 0;
        }
        blinkAt += dt;
        pose.blink = lid(blinkAt);
        if (blinkAt >= BLINK_S) {
            blinkAt = -1;
            pose.blink = 0;
            // Now and then twice in a row; more often while thinking, as people do.
            untilBlink = random.nextDouble() < 0.12 ? 0.12
                    : (state == State.THINKING ? 1.4 : 2.2) + random.nextDouble() * 4;
        }
    }

    private static double lid(double t) {
        if (t < 0.07) return (t / 0.07) * (t / 0.07);
        if (t < 0.10) return 1;
        double k = Math.min(1, (t - 0.10) / 0.14);
        return (1 - k) * (1 - k);
    }

    /** The eyes jump between points - never a slow slide - and rest on each a moment. */
    private void glance(double dt) {
        untilGlance -= dt;
        if (untilGlance <= 0) {
            boolean thinking = state == State.THINKING;
            double spread = state == State.SPEAKING ? 0.12 : 0.22;
            lookX = (thinking ? 0.5 : 0) + (random.nextDouble() * 2 - 1) * spread;
            lookY = (thinking ? -0.55 : 0) + (random.nextDouble() * 2 - 1) * spread * 0.6;
            untilGlance = 0.6 + random.nextDouble() * 2.2;
        }
        pose.gazeX = ease(pose.gazeX, lookX, 0.04, dt);
        pose.gazeY = ease(pose.gazeY, lookY, 0.04, dt);
    }

    private static double ease(double from, double to, double seconds, double dt) {
        return from + (to - from) * (1 - Math.exp(-dt / seconds));
    }

    private static FaceRig.Landmarks landmarks(JsonNode spec) throws IOException {
        FaceRig.Landmarks lm = new FaceRig.Landmarks();
        lm.leftEye = eye(spec.path("eyes").path(0));
        lm.rightEye = eye(spec.path("eyes").path(1));
        JsonNode mouth = spec.path("mouth");
        lm.mouthLeft = point(mouth, "left");
        lm.mouthRight = point(mouth, "right");
        lm.mouthMiddle = point(mouth, "middle");
        lm.nose = point(spec, "nose");
        lm.chin = point(spec, "chin");
        lm.neck = spec.has("neck") ? point(spec, "neck")
                : new double[]{lm.chin[0], lm.chin[1] + (lm.chin[1] - lm.nose[1]) * 0.6};
        JsonNode head = spec.path("head");
        lm.head = new double[]{number(head, "x"), number(head, "y"), number(head, "rx"), number(head, "ry")};
        lm.jaw = spec.path("jaw").asDouble(0);
        JsonNode gem = spec.path("gem");
        if (gem.isObject()) {
            lm.gem = new double[]{number(gem, "x"), number(gem, "y"), gem.path("r").asDouble(10)};
            lm.gemColor = color(gem.path("color"), lm.gemColor);
        }
        JsonNode colors = spec.path("colors");
        lm.mouthColor = color(colors.path("mouth"), lm.mouthColor);
        lm.teethColor = color(colors.path("teeth"), lm.teethColor);
        lm.tongueColor = color(colors.path("tongue"), lm.tongueColor);
        return lm;
    }

    private static double[] eye(JsonNode e) throws IOException {
        return new double[]{number(e, "x"), number(e, "y"), number(e, "w"), number(e, "h")};
    }

    private static double[] point(JsonNode parent, String key) throws IOException {
        JsonNode p = parent.path(key);
        if (!p.isArray() || p.size() < 2 || !p.get(0).isNumber() || !p.get(1).isNumber()) {
            throw new IOException("face.json: \"" + key + "\" should be [x, y]");
        }
        return new double[]{p.get(0).asDouble(), p.get(1).asDouble()};
    }

    private static double number(JsonNode parent, String key) throws IOException {
        JsonNode n = parent.path(key);
        if (!n.isNumber()) throw new IOException("face.json: \"" + key + "\" should be a number");
        return n.asDouble();
    }

    private static int color(JsonNode node, int fallback) {
        if (!node.isTextual()) return fallback;
        try {
            return Integer.parseInt(node.asText().replace("#", ""), 16) & 0xffffff;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
