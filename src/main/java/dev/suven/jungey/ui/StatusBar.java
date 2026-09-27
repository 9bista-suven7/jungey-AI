package dev.suven.jungey.ui;

import dev.suven.jungey.core.SysInfo;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Always-on vitals along the bottom edge: clock, CPU, memory, battery - and the microphone
 * and voice, which are switches: click either to turn it on or off.
 */
public class StatusBar extends HBox {

    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");

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

    public StatusBar(Supplier<String> voiceLabel, Supplier<String> earsLabel,
                     Runnable toggleVoice, Runnable toggleEars) {
        this.voiceLabel = voiceLabel;
        this.earsLabel = earsLabel;
        switchable(voice, toggleVoice, "Click to turn Jungey's voice on or off");
        switchable(ears, toggleEars, "Click to turn the microphone on or off");
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
        String speech = voiceLabel.get(), hearing = earsLabel.get();
        voice.setText("VOICE " + speech.toUpperCase(Locale.ENGLISH));
        ears.setText("MIC " + hearing.toUpperCase(Locale.ENGLISH));
        voice.pseudoClassStateChanged(ON, !speech.equals("off"));
        ears.pseudoClassStateChanged(ON, !hearing.equals("off"));
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

    private void switchable(Label cell, Runnable toggle, String tip) {
        cell.getStyleClass().add("status-switch");
        cell.setTooltip(new Tooltip(tip));
        cell.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            toggle.run();
            refresh();
        });
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
