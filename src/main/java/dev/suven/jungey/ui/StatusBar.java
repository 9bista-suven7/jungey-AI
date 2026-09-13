package dev.suven.jungey.ui;

import dev.suven.jungey.core.SysInfo;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/** Always-on vitals along the bottom edge: clock, CPU, memory, battery. */
public class StatusBar extends HBox {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);

    private final Label clock = cell();
    private final Label cpu = cell();
    private final Label mem = cell();
    private final Label battery = cell();
    private final Label voice = cell();
    private final Label ears = cell();

    private final Timeline ticker;
    private final Supplier<String> voiceLabel;
    private final Supplier<String> earsLabel;

    public StatusBar(Supplier<String> voiceLabel, Supplier<String> earsLabel) {
        this.voiceLabel = voiceLabel;
        this.earsLabel = earsLabel;
        setSpacing(0);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(7, 16, 7, 16));
        getStyleClass().add("status-bar");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(clock, sep(), cpu, sep(), mem, sep(), battery, spacer, ears, sep(), voice);

        // One second is frequent enough to feel live without the CPU reading being noise.
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        ticker.setCycleCount(Animation.INDEFINITE);
        refresh();
    }

    public void start() {
        ticker.play();
    }

    public void stop() {
        ticker.stop();
    }

    private void refresh() {
        clock.setText(LocalTime.now().format(CLOCK));
        voice.setText("VOICE " + voiceLabel.get().toUpperCase(Locale.ENGLISH));
        ears.setText("MIC " + earsLabel.get().toUpperCase(Locale.ENGLISH));
        cpu.setText(String.format("CPU %3.0f%%", SysInfo.cpuPercent()));

        long[] m = SysInfo.memory();
        mem.setText(String.format("MEM %3.0f%%", 100.0 * m[0] / Math.max(1, m[1])));

        int pct = SysInfo.batteryPercent();
        if (pct < 0) {
            battery.setText("PWR AC");
        } else {
            battery.setText("BAT " + pct + "%" + (SysInfo.onAcPower() ? "+" : ""));
            battery.getStyleClass().removeAll("status-warn");
            if (pct < 20 && !SysInfo.onAcPower()) {
                battery.getStyleClass().add("status-warn");
            }
        }
    }

    private static Label cell() {
        Label l = new Label();
        l.getStyleClass().add("status-cell");
        return l;
    }

    private static Label sep() {
        Label l = new Label("│");
        l.getStyleClass().add("status-sep");
        return l;
    }
}
