package dev.suven.jungey.watch;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Something in view that changed and stayed changed, with before-and-after pictures. */
public final class SceneEvent {

    public enum Kind {CHANGE, LIGHTING}

    public enum Type {MOVED, APPEARED, REMOVED, SHIFTED, CHANGED}

    /** A rectangle of the 640x480 frame. */
    public record Region(int x, int y, int width, int height) {

        /** Where it sits in the picture, in words: "left", "top right", "middle". */
        public String position() {
            double cx = (x + width / 2.0) / SceneWatcher.WIDTH;
            double cy = (y + height / 2.0) / SceneWatcher.HEIGHT;
            String across = cx < 0.36 ? "left" : cx > 0.64 ? "right" : "";
            String down = cy < 0.36 ? "top" : cy > 0.64 ? "bottom" : "";
            String both = (down + " " + across).trim();
            return both.isEmpty() ? "middle" : both;
        }
    }

    /** One thing that changed. What the object actually is gets filled in later, by the vision model. */
    public static final class Change {

        public final Type type;
        /** Where the object was - or, for something new, where it now is. */
        public final Region from;
        /** Where the object is now; the same as from unless it moved. */
        public final Region to;
        /** Close-ups of the spot before and after, for naming the object. Null if they could not be saved. */
        public final Path before;
        public final Path after;

        private volatile String label;
        private volatile String afterLabel;

        Change(Type type, Region from, Region to, Path before, Path after) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.before = before;
            this.after = after;
        }

        /** What the object is, or null if it has not been named (or could not be). */
        public String label() {
            return label;
        }

        public void label(String value) {
            label = value;
        }

        /** CHANGED only: what is in the spot now, when it is not the thing that was there. */
        public String afterLabel() {
            return afterLabel;
        }

        public void afterLabel(String value) {
            afterLabel = value;
        }
    }

    public final int id;
    /** When movement started. */
    public final LocalDateTime started;
    /** When the scene held still again and the change was judged. */
    public final LocalDateTime settled;
    public final Kind kind;
    public final List<Change> changes;
    /** Before and after side by side, with the changes boxed; null if it could not be saved. */
    public final Path picture;

    SceneEvent(int id, LocalDateTime started, LocalDateTime settled, Kind kind, List<Change> changes, Path picture) {
        this.id = id;
        this.started = started;
        this.settled = settled;
        this.kind = kind;
        this.changes = List.copyOf(changes);
        this.picture = picture;
    }
}
