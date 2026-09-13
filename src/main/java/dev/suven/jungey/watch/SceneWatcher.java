package dev.suven.jungey.watch;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Watches the camera for things that change and stay changed - an object put down, taken
 * away or moved - and keeps a before-and-after record of each.
 *
 * <p>Detection is plain pixel arithmetic, not a model. The picture is reduced to a grid of
 * brightness cells and compared with a reference of the scene at rest. Each cell is judged
 * on its own: once it differs from the reference and has held still for a couple of seconds,
 * away from anything still moving, it has changed. That way a person fidgeting in view does
 * not stop a mug being noticed on the desk beside them, and a hand passing through leaves
 * nothing behind. Settled changes are collected for a few seconds before being judged, so
 * something picked up in one spot and put down in another is recognised as one move.
 *
 * <p>Naming the objects is left to the vision model, and only happens when something did change.
 */
public final class SceneWatcher {

    public static final int WIDTH = 640;
    public static final int HEIGHT = 480;

    /** The picture is judged as a grid of 8x8 pixel cells: 80 across, 60 down. */
    private static final int CELL = 8;
    private static final int COLS = WIDTH / CELL;
    private static final int ROWS = HEIGHT / CELL;

    /** Two frames a second catches anything done to a desk, at almost no cost. */
    public static final int FPS = 2;
    /** Webcams open dark and adjust their exposure for a few seconds. */
    private static final int WARMUP_FRAMES = 3 * FPS;
    /** Someone in view may never hold still, so the reference is taken after this long regardless. */
    private static final int REFERENCE_WAIT_FRAMES = 6 * FPS;
    /** Share of the picture that must be still for an early reference. */
    private static final double REFERENCE_STILL_FRACTION = 0.8;
    /** How far a cell's brightness must move, once exposure changes are allowed for, to count. */
    private static final float CELL_THRESHOLD = 20f;
    /** Changed cells that mean something is happening - roughly a matchbox at arm's length. */
    private static final int TRIGGER_CELLS = 10;
    /** Frame-to-frame changed cells still counted as the whole picture holding still. */
    private static final int QUIET_CELLS = 3;
    /** How long a cell must hold still before its change is believed. */
    private static final int SETTLE_FRAMES = 2 * FPS;
    /** How long settled changes wait for a counterpart - the other half of a move - while things still move. */
    private static final int BATCH_WAIT_FRAMES = 8 * FPS;
    /** Smallest patch treated as an object rather than noise. */
    private static final int MIN_REGION_CELLS = 5;
    /** Most of the picture changing at once is the light or the camera, not an object. */
    private static final double LIGHTING_FRACTION = 0.5;

    /** Colour similarity above which a patch holds the same thing before and after. */
    private static final double SAME_OBJECT = 0.75;
    /** How much more a patch must stand out from its surroundings in one picture to hold the object. */
    private static final double STANDS_OUT_MARGIN = 0.12;
    /** Colour similarity for "what left here is what arrived there". */
    private static final double MOVE_MATCH = 0.55;

    private static final int MAX_CHANGES = 4;
    private static final int MAX_EVENTS = 50;

    public enum State {OFF, STARTING, WATCHING, ACTIVE}

    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<SceneEvent> events = new CopyOnWriteArrayList<>();
    private final List<Consumer<SceneEvent>> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.OFF;
    private volatile boolean running;
    private volatile int[] latest;
    private volatile LocalDateTime since;
    private volatile int motions;

    private volatile Thread thread;
    private Process process;
    private Path folder;
    private int eventCount;

    /** Called on the watcher thread for every event; keep it quick. */
    public void onEvent(Consumer<SceneEvent> listener) {
        listeners.add(listener);
    }

    public boolean running() {
        return running;
    }

    public State state() {
        return state;
    }

    /** When the reference picture was taken, or null while still waiting to take it. */
    public LocalDateTime since() {
        return since;
    }

    /** How many times something moved in view but left nothing changed. */
    public int motions() {
        return motions;
    }

    /** Events from the current (or most recent) watch, oldest first. */
    public List<SceneEvent> events() {
        return List.copyOf(events);
    }

    /** Watch the webcam. ffmpeg holds the device, so nothing else can open it meanwhile. */
    public synchronized void start(String device) throws IOException {
        if (running) return;
        process = new ProcessBuilder(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "v4l2", "-video_size", WIDTH + "x" + HEIGHT, "-i", device,
                "-vf", "fps=" + FPS, "-pix_fmt", "rgb24", "-f", "rawvideo", "-"))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        start(process.getInputStream());
    }

    /** Watch any stream of raw 640x480 RGB frames taken FPS times a second - the webcam, or recorded footage. */
    public synchronized void start(InputStream frames) {
        if (running) return;

        folder = Path.of(System.getProperty("user.home"), "Pictures", "Jungey", "watch",
                LocalDateTime.now().format(FOLDER));
        events.clear();
        eventCount = 0;
        motions = 0;
        since = null;
        latest = null;
        state = State.STARTING;
        running = true;

        Thread t = new Thread(() -> loop(frames), "jungey-watch");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        running = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        state = State.OFF;
    }

    /** The newest frame as a JPEG, or null before the first one arrives. */
    public byte[] latestJpeg() {
        int[] px = latest;
        if (px == null) return null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image(px, WIDTH, HEIGHT), "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void loop(InputStream in) {
        Thread self = Thread.currentThread();
        int cells = COLS * ROWS;
        byte[] raw = new byte[WIDTH * HEIGHT * 3];
        int[] still = new int[cells];

        float[] previous = null;
        float[] reference = null;
        int[] referencePixels = null;

        // Changes that have settled but are still being collected, so the two halves of a move are judged together.
        boolean[] pending = null;
        int[] pendingBefore = null;
        LocalDateTime pendingSince = null;
        int lastAdded = 0;

        boolean episode = false;
        boolean episodeRecorded = false;
        LocalDateTime episodeStart = null;
        int quiet = 0;
        int frame = 0;

        try (in) {
            while (running && thread == self) {
                if (in.readNBytes(raw, 0, raw.length) < raw.length) break;

                int[] pixels = pixels(raw);
                float[] grid = grid(pixels);
                latest = pixels;
                frame++;

                if (previous == null || frame <= WARMUP_FRAMES) {
                    previous = grid;
                    continue;
                }

                boolean[] movingCells = changed(grid, previous);
                int motion = solid(movingCells);
                boolean[] moving = grow(movingCells);
                previous = grid;
                for (int i = 0; i < cells; i++) {
                    still[i] = moving[i] ? 0 : Math.min(still[i] + 1, 1 << 20);
                }
                quiet = motion <= QUIET_CELLS ? quiet + 1 : 0;

                if (reference == null) {
                    int stillCells = 0;
                    for (int s : still) if (s >= SETTLE_FRAMES) stillCells++;
                    if (stillCells >= REFERENCE_STILL_FRACTION * cells || frame >= WARMUP_FRAMES + REFERENCE_WAIT_FRAMES) {
                        reference = grid.clone();
                        referencePixels = pixels.clone();
                        since = LocalDateTime.now();
                        state = State.WATCHING;
                    }
                    continue;
                }

                if (motion >= TRIGGER_CELLS && !episode) {
                    episode = true;
                    episodeRecorded = false;
                    episodeStart = LocalDateTime.now();
                }

                // A cell has changed once it differs from the reference and has held still - and is
                // not at the edge of something still moving, like the hand that put the object there.
                boolean[] differs = changed(grid, reference);
                boolean[] nearMotion = grow(moving);
                boolean[] settled = new boolean[cells];
                for (int i = 0; i < cells; i++) {
                    settled[i] = differs[i] && still[i] >= SETTLE_FRAMES && !nearMotion[i]
                            && (pending == null || !pending[i]);
                }

                if (solid(settled) >= (pending == null ? TRIGGER_CELLS : MIN_REGION_CELLS)) {
                    if (pending == null) {
                        pending = new boolean[cells];
                        pendingBefore = referencePixels.clone();
                        pendingSince = episodeStart != null ? episodeStart : LocalDateTime.now();
                    }
                    // Take the change into the reference now, so it is not found again next frame.
                    for (int i = 0; i < cells; i++) {
                        if (!settled[i]) continue;
                        pending[i] = true;
                        reference[i] = grid[i];
                        copyCell(pixels, referencePixels, i);
                    }
                    lastAdded = frame;
                }

                // Let slow changes in daylight drift into the reference, so they never look like an event,
                // and keep its picture current wherever nothing is happening - otherwise the faint edges
                // of a removed object, too slight to count as a change, linger in later pictures.
                for (int i = 0; i < cells; i++) {
                    if (moving[i] || differs[i]) continue;
                    reference[i] += (grid[i] - reference[i]) * 0.05f;
                    copyCell(pixels, referencePixels, i);
                }

                if (pending != null) {
                    int waited = frame - lastAdded;
                    if ((waited >= SETTLE_FRAMES && quiet >= SETTLE_FRAMES) || waited >= BATCH_WAIT_FRAMES) {
                        if (record(pendingSince, pendingBefore, referencePixels.clone(), pending)) {
                            episodeRecorded = true;
                        }
                        pending = null;
                        pendingBefore = null;
                    }
                }

                if (episode && pending == null && quiet >= SETTLE_FRAMES) {
                    if (!episodeRecorded) motions++;
                    episode = false;
                    episodeStart = null;
                }

                state = episode || pending != null ? State.ACTIVE : State.WATCHING;
            }
        } catch (IOException e) {
            // The camera closed or the footage ended.
        } finally {
            if (thread == self) {
                running = false;
                state = State.OFF;
            }
        }
    }

    /** @return false if what changed turned out to be only specks of noise */
    private boolean record(LocalDateTime started, int[] before, int[] after, boolean[] mask) {
        int changedCells = 0;
        for (boolean m : mask) if (m) changedCells++;
        boolean lighting = changedCells > LIGHTING_FRACTION * COLS * ROWS;

        int id = eventCount + 1;
        List<SceneEvent.Change> changes = lighting ? List.of() : analyse(id, before, after, mask);
        if (!lighting && changes.isEmpty()) return false;
        eventCount = id;

        Path picture = picture(id, before, after, changes, started);
        SceneEvent event = new SceneEvent(id, started, LocalDateTime.now(),
                lighting ? SceneEvent.Kind.LIGHTING : SceneEvent.Kind.CHANGE, changes, picture);

        events.add(event);
        while (events.size() > MAX_EVENTS) events.remove(0);

        for (Consumer<SceneEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                System.err.println("[jungey] watch listener failed: " + e);
            }
        }
        return true;
    }

    /** A patch of connected changed cells, and what its colours say about it. */
    private static final class Blob {
        final int id;
        int top = Integer.MAX_VALUE, left = Integer.MAX_VALUE, bottom = -1, right = -1;
        int cells;
        double[] objectBefore, objectAfter;
        SceneEvent.Type type;

        Blob(int id) {
            this.id = id;
        }

        SceneEvent.Region region() {
            return new SceneEvent.Region(left * CELL, top * CELL, (right - left + 1) * CELL, (bottom - top + 1) * CELL);
        }
    }

    /**
     * Split what changed into separate things and decide what happened to each.
     *
     * <p>A patch holds the object in whichever picture its colours stand out from the
     * surroundings: standing out before and blending in after means something was taken
     * away. Something taken from one patch and matching the colours of something that
     * arrived at another was moved.
     */
    private List<SceneEvent.Change> analyse(int id, int[] before, int[] after, boolean[] mask) {
        boolean[] grown = grow(mask);
        int[] label = new int[mask.length];
        int[] queue = new int[mask.length];
        List<Blob> blobs = new ArrayList<>();
        int next = 0;

        for (int start = 0; start < mask.length; start++) {
            if (!grown[start] || label[start] != 0) continue;

            Blob blob = new Blob(++next);
            int head = 0, tail = 0;
            queue[tail++] = start;
            label[start] = blob.id;

            while (head < tail) {
                int i = queue[head++];
                int r = i / COLS, c = i % COLS;
                blob.top = Math.min(blob.top, r);
                blob.bottom = Math.max(blob.bottom, r);
                blob.left = Math.min(blob.left, c);
                blob.right = Math.max(blob.right, c);
                if (mask[i]) blob.cells++;

                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || rr >= ROWS || cc < 0 || cc >= COLS) continue;
                        int j = rr * COLS + cc;
                        if (grown[j] && label[j] == 0) {
                            label[j] = blob.id;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            if (blob.cells >= MIN_REGION_CELLS) blobs.add(blob);
        }

        blobs.sort(Comparator.comparingInt((Blob b) -> b.cells).reversed());
        if (blobs.size() > MAX_CHANGES * 2) blobs = new ArrayList<>(blobs.subList(0, MAX_CHANGES * 2));

        for (Blob b : blobs) {
            IntPredicate inside = i -> label[i] == b.id && mask[i];
            IntPredicate ring = ringAround(b, grown);

            b.objectBefore = histogram(before, inside);
            b.objectAfter = histogram(after, inside);
            double standsOutBefore = 1 - similarity(b.objectBefore, histogram(before, ring));
            double standsOutAfter = 1 - similarity(b.objectAfter, histogram(after, ring));

            if (similarity(b.objectBefore, b.objectAfter) > SAME_OBJECT) b.type = SceneEvent.Type.SHIFTED;
            else if (standsOutBefore > standsOutAfter + STANDS_OUT_MARGIN) b.type = SceneEvent.Type.REMOVED;
            else if (standsOutAfter > standsOutBefore + STANDS_OUT_MARGIN) b.type = SceneEvent.Type.APPEARED;
            else b.type = SceneEvent.Type.CHANGED;
        }

        List<SceneEvent.Change> changes = new ArrayList<>();
        List<Blob> left = new ArrayList<>(blobs);

        // Pair what was taken away with what turned up elsewhere, best colour match first.
        while (true) {
            Blob from = null, to = null;
            double best = MOVE_MATCH;
            for (Blob gone : left) {
                if (gone.type != SceneEvent.Type.REMOVED) continue;
                for (Blob arrived : left) {
                    if (arrived.type != SceneEvent.Type.APPEARED) continue;
                    double s = similarity(gone.objectBefore, arrived.objectAfter);
                    if (s > best) {
                        best = s;
                        from = gone;
                        to = arrived;
                    }
                }
            }
            if (from == null) break;
            left.remove(from);
            left.remove(to);
            int n = changes.size() + 1;
            changes.add(new SceneEvent.Change(SceneEvent.Type.MOVED, from.region(), to.region(),
                    crop(before, from.region(), "event-" + id + "-" + n + "-before.jpg"),
                    crop(after, to.region(), "event-" + id + "-" + n + "-after.jpg")));
        }

        for (Blob b : left) {
            if (changes.size() >= MAX_CHANGES) break;
            int n = changes.size() + 1;
            SceneEvent.Region r = b.region();
            changes.add(new SceneEvent.Change(b.type, r, r,
                    crop(before, r, "event-" + id + "-" + n + "-before.jpg"),
                    crop(after, r, "event-" + id + "-" + n + "-after.jpg")));
        }
        return changes;
    }

    /** Unchanged cells just outside a blob - the background it is judged against. */
    private static IntPredicate ringAround(Blob b, boolean[] grown) {
        int top = Math.max(0, b.top - 2), bottom = Math.min(ROWS - 1, b.bottom + 2);
        int left = Math.max(0, b.left - 2), right = Math.min(COLS - 1, b.right + 2);
        return i -> {
            int r = i / COLS, c = i % COLS;
            return r >= top && r <= bottom && c >= left && c <= right && !grown[i];
        };
    }

    // ---- pixels and cells -------------------------------------------------------------

    private static int[] pixels(byte[] raw) {
        int[] px = new int[WIDTH * HEIGHT];
        for (int i = 0, j = 0; i < px.length; i++, j += 3) {
            px[i] = (raw[j] & 255) << 16 | (raw[j + 1] & 255) << 8 | (raw[j + 2] & 255);
        }
        return px;
    }

    /** Average brightness of each cell. */
    private static float[] grid(int[] px) {
        float[] g = new float[COLS * ROWS];
        for (int y = 0; y < HEIGHT; y++) {
            int row = (y / CELL) * COLS;
            for (int x = 0; x < WIDTH; x++) {
                int rgb = px[y * WIDTH + x];
                g[row + x / CELL] += ((rgb >> 16 & 255) * 77 + (rgb >> 8 & 255) * 150 + (rgb & 255) * 29) >> 8;
            }
        }
        for (int i = 0; i < g.length; i++) g[i] /= CELL * CELL;
        return g;
    }

    /**
     * Cells that differ between two grids. The median difference is taken off first: an
     * exposure change moves every cell together, where an object moves only a few.
     */
    private static boolean[] changed(float[] now, float[] then) {
        float[] diff = new float[now.length];
        for (int i = 0; i < diff.length; i++) diff[i] = now[i] - then[i];
        float[] sorted = diff.clone();
        Arrays.sort(sorted);
        float shift = sorted[sorted.length / 2];

        boolean[] mask = new boolean[now.length];
        for (int i = 0; i < mask.length; i++) mask[i] = Math.abs(diff[i] - shift) > CELL_THRESHOLD;
        return mask;
    }

    /** Changed cells with a changed neighbour - sensor noise comes in lone cells, objects do not. */
    private static int solid(boolean[] mask) {
        int n = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int i = r * COLS + c;
                if (mask[i] && ((c > 0 && mask[i - 1]) || (c < COLS - 1 && mask[i + 1])
                        || (r > 0 && mask[i - COLS]) || (r < ROWS - 1 && mask[i + COLS]))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Widen every changed cell by one in each direction, so the parts of one object join up. */
    private static boolean[] grow(boolean[] mask) {
        boolean[] grown = new boolean[mask.length];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (!mask[r * COLS + c]) continue;
                for (int rr = Math.max(0, r - 1); rr <= Math.min(ROWS - 1, r + 1); rr++) {
                    for (int cc = Math.max(0, c - 1); cc <= Math.min(COLS - 1, c + 1); cc++) {
                        grown[rr * COLS + cc] = true;
                    }
                }
            }
        }
        return grown;
    }

    private static void copyCell(int[] from, int[] to, int cell) {
        int y0 = (cell / COLS) * CELL, x0 = (cell % COLS) * CELL;
        for (int y = y0; y < y0 + CELL; y++) {
            System.arraycopy(from, y * WIDTH + x0, to, y * WIDTH + x0, CELL);
        }
    }

    /** Coarse colour histogram (4 levels per channel) of the given cells, sampling every other pixel. */
    private static double[] histogram(int[] px, IntPredicate cells) {
        double[] h = new double[64];
        double n = 0;
        for (int i = 0; i < COLS * ROWS; i++) {
            if (!cells.test(i)) continue;
            int y0 = (i / COLS) * CELL, x0 = (i % COLS) * CELL;
            for (int y = y0; y < y0 + CELL; y += 2) {
                for (int x = x0; x < x0 + CELL; x += 2) {
                    int rgb = px[y * WIDTH + x];
                    h[((rgb >> 22) & 3) << 4 | ((rgb >> 14) & 3) << 2 | ((rgb >> 6) & 3)]++;
                    n++;
                }
            }
        }
        if (n > 0) {
            for (int k = 0; k < h.length; k++) h[k] /= n;
        }
        return h;
    }

    /** Histogram intersection: 1 for identical colour mixes, 0 for nothing in common. */
    private static double similarity(double[] a, double[] b) {
        double s = 0;
        for (int k = 0; k < a.length; k++) s += Math.min(a[k], b[k]);
        return s;
    }

    // ---- pictures ---------------------------------------------------------------------

    /** A close-up around a region, wide enough to show the object with some of its surroundings. */
    private Path crop(int[] px, SceneEvent.Region r, String name) {
        int size = Math.max(112, (int) (Math.max(r.width(), r.height()) * 1.8));
        int w = Math.min(WIDTH, size), h = Math.min(HEIGHT, size);
        int x = Math.max(0, Math.min(WIDTH - w, r.x() + r.width() / 2 - w / 2));
        int y = Math.max(0, Math.min(HEIGHT - h, r.y() + r.height() / 2 - h / 2));

        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(px, (y + row) * WIDTH + x, out, row * w, w);
        }
        return save(image(out, w, h), name);
    }

    /** Before and after side by side, each change boxed in its own colour. */
    private Path picture(int id, int[] before, int[] after, List<SceneEvent.Change> changes, LocalDateTime started) {
        int gap = 8, width = WIDTH * 2 + gap;
        int[] out = new int[width * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(before, y * WIDTH, out, y * width, WIDTH);
            System.arraycopy(after, y * WIDTH, out, y * width + WIDTH + gap, WIDTH);
        }
        BufferedImage pic = image(out, width, HEIGHT);

        Graphics2D g = pic.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(3));
        for (SceneEvent.Change c : changes) {
            g.setColor(colour(c.type));
            if (c.type != SceneEvent.Type.APPEARED) g.drawRect(c.from.x(), c.from.y(), c.from.width(), c.from.height());
            if (c.type != SceneEvent.Type.REMOVED) g.drawRect(c.to.x() + WIDTH + gap, c.to.y(), c.to.width(), c.to.height());
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        caption(g, "BEFORE  " + CLOCK.format(started), 10, 26);
        caption(g, "AFTER  " + CLOCK.format(LocalDateTime.now()), WIDTH + gap + 10, 26);
        g.dispose();

        return save(pic, "event-" + id + ".jpg");
    }

    private static Color colour(SceneEvent.Type type) {
        return switch (type) {
            case MOVED -> new Color(255, 160, 40);
            case APPEARED -> new Color(60, 220, 90);
            case REMOVED -> new Color(255, 70, 80);
            case SHIFTED -> new Color(255, 220, 60);
            case CHANGED -> new Color(60, 200, 255);
        };
    }

    private static void caption(Graphics2D g, String text, int x, int y) {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 1, y + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }

    private Path save(BufferedImage image, String name) {
        try {
            Files.createDirectories(folder);
            Path file = folder.resolve(name);
            ImageIO.write(image, "jpg", file.toFile());
            return file;
        } catch (IOException e) {
            System.err.println("[jungey] could not save watch picture: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage image(int[] px, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        System.arraycopy(px, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, px.length);
        return img;
    }
}
