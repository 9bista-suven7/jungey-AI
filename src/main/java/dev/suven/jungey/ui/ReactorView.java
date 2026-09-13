package dev.suven.jungey.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;

/**
 * The reactor: concentric rings that rotate at different speeds around a glowing core.
 *
 * <p>It is the only status indicator Jungey needs. Idle breathes slowly, thinking spins
 * up and tightens, speaking pulses with the cadence of a voice, and an error washes the
 * whole assembly red. Everything is drawn on one canvas from a single animation timer,
 * so it costs one repaint per frame rather than a tree of animated nodes.
 */
public class ReactorView extends Canvas {

    public enum State {IDLE, THINKING, SPEAKING, ERROR}

    private static final Color CYAN = Color.web("#38e8ff");
    private static final Color DEEP = Color.web("#0a6f8a");
    private static final Color RED = Color.web("#ff4d5e");

    /**
     * 30 frames a second is plenty for rings this slow, and halves the cost of redrawing
     * the glow - JavaFX would otherwise repaint the canvas on every 60Hz pulse.
     */
    private static final long FRAME_NANOS = 1_000_000_000L / 30;

    private State state = State.IDLE;

    /** Master rotation angle, advanced every frame. */
    private double angle = 0;
    /** Eases 0..1 toward the current state's target intensity so transitions are not abrupt. */
    private double intensity = 0.35;
    private long lastFrame = 0;
    private double pulsePhase = 0;

    private final AnimationTimer timer;

    public ReactorView(double size) {
        super(size, size);

        setEffect(new DropShadow(BlurType.GAUSSIAN, CYAN.deriveColor(0, 1, 1, 0.55), 26, 0.3, 0, 0));

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (lastFrame != 0 && now - lastFrame < FRAME_NANOS) return;
                double dt = lastFrame == 0 ? 0 : Math.min(0.05, (now - lastFrame) / 1_000_000_000.0);
                lastFrame = now;
                step(dt);
                draw();
            }
        };
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
        lastFrame = 0;
    }

    public void setState(State next) {
        this.state = next;
    }

    private void step(double dt) {
        double spin = switch (state) {
            case IDLE -> 18;
            case THINKING -> 165;
            case SPEAKING -> 55;
            case ERROR -> 8;
        };
        angle = (angle + spin * dt) % 360;

        double target = switch (state) {
            case IDLE -> 0.35;
            case THINKING -> 1.0;
            case SPEAKING -> 0.8;
            case ERROR -> 0.9;
        };
        // Exponential ease - fast enough to feel responsive, slow enough to read as physical.
        intensity += (target - intensity) * Math.min(1, dt * 6);

        double pulseRate = switch (state) {
            case IDLE -> 1.1;
            case THINKING -> 4.5;
            case SPEAKING -> 7.0;
            case ERROR -> 3.0;
        };
        pulsePhase = (pulsePhase + pulseRate * dt) % (Math.PI * 2);
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        double cx = w / 2, cy = h / 2;
        double r = Math.min(w, h) / 2 - 6;

        g.clearRect(0, 0, w, h);

        Color accent = (state == State.ERROR) ? RED : CYAN;
        Color deep = (state == State.ERROR) ? RED.darker() : DEEP;
        double pulse = 0.5 + 0.5 * Math.sin(pulsePhase);

        // --- Outer tick ring: 60 marks, every fifth one long. A dial, not decoration.
        g.setLineWidth(1);
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6 - angle * 0.25);
            boolean major = i % 5 == 0;
            double inner = r * (major ? 0.88 : 0.93);
            double outer = r;
            g.setStroke(accent.deriveColor(0, 1, 1, major ? 0.55 * intensity + 0.15 : 0.22 * intensity + 0.06));
            g.strokeLine(cx + Math.cos(a) * inner, cy + Math.sin(a) * inner,
                    cx + Math.cos(a) * outer, cy + Math.sin(a) * outer);
        }

        // --- Two counter-rotating arc pairs.
        g.setLineWidth(2.4);
        g.setStroke(accent.deriveColor(0, 1, 1, 0.75 * intensity + 0.2));
        double rA = r * 0.78;
        for (int i = 0; i < 2; i++) {
            g.strokeArc(cx - rA, cy - rA, rA * 2, rA * 2, angle + i * 180, 62, ArcType.OPEN);
        }

        g.setLineWidth(1.6);
        g.setStroke(deep.deriveColor(0, 1, 1.25, 0.7 * intensity + 0.15));
        double rB = r * 0.62;
        for (int i = 0; i < 3; i++) {
            g.strokeArc(cx - rB, cy - rB, rB * 2, rB * 2, -angle * 1.7 + i * 120, 40, ArcType.OPEN);
        }

        // --- Static inner bezel, so the spinning parts have something to read against.
        g.setLineWidth(1);
        g.setStroke(accent.deriveColor(0, 1, 1, 0.3 * intensity + 0.1));
        double rC = r * 0.48;
        g.strokeOval(cx - rC, cy - rC, rC * 2, rC * 2);

        // --- Core: radial gradient that swells with the pulse.
        double coreR = r * (0.30 + 0.05 * pulse * intensity);
        RadialGradient core = new RadialGradient(
                0, 0, cx, cy, coreR, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE.deriveColor(0, 1, 1, 0.92 * (0.5 + 0.5 * intensity))),
                new Stop(0.35, accent.deriveColor(0, 1, 1, 0.85 * intensity + 0.25)),
                new Stop(1, accent.deriveColor(0, 1, 1, 0)));
        g.setFill(core);
        g.fillOval(cx - coreR, cy - coreR, coreR * 2, coreR * 2);

        // --- Halo, strongest while thinking.
        double haloR = r * (0.9 + 0.08 * pulse);
        RadialGradient halo = new RadialGradient(
                0, 0, cx, cy, haloR, false, CycleMethod.NO_CYCLE,
                new Stop(0.7, accent.deriveColor(0, 1, 1, 0)),
                new Stop(1, accent.deriveColor(0, 1, 1, 0.16 * intensity)));
        g.setFill(halo);
        g.fillOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2);
    }
}
