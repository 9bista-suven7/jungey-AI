package dev.suven.jungey.ui;

import javafx.scene.Node;

/**
 * What the window shows of Jungey itself: a face when one is installed, the reactor when not.
 * Either way it is the one place that says whether Jungey is idle, thinking or talking.
 */
public interface Avatar {

    enum State {IDLE, THINKING, SPEAKING, ERROR}

    void setState(State next);

    /** Begin animating. */
    void start();

    /** Stop animating - nobody is looking. */
    void stop();

    Node node();
}
