package dev.suven.jungey.ui;

/**
 * A photograph of a face, made to talk.
 *
 * <p>Nothing is painted onto the picture but the inside of a mouth. Everything else is the
 * photograph's own pixels, moved: the jaw drops by stretching the skin between the lips and
 * the neck, an eyelid closes by drawing the skin above the eye down over it, the head sways
 * by moving the face against its own shoulders. Each frame is found backwards - for every
 * pixel on screen, where in the photograph it came from - which leaves no holes and needs
 * nothing but arithmetic, so it runs on any graphics in a few milliseconds.
 *
 * <p>The work is done in the face's own frame, turned so the eyes are level, so a head held
 * at an angle still opens its mouth along its own chin rather than straight down the screen.
 *
 * <p>No JavaFX in here: {@link FaceView} decides what the face is doing, this only draws it.
 */
final class FaceRig {

    /** Where the face is, in pixels of the picture. */
    static final class Landmarks {
        /** x, y, width, height of each eye's opening, lid to lid. */
        double[] leftEye, rightEye;
        /** The corners of the mouth and the middle of the line where the lips meet. */
        double[] mouthLeft, mouthRight, mouthMiddle;
        /** The base of the nose, the point of the chin, and what the head turns about. */
        double[] nose, chin, neck;
        /** x, y and the two radii of the head, for where the face stops and the shoulders begin. */
        double[] head;
        /** Half the width of the jaw, as far out as it moves when the mouth opens. */
        double jaw;
        /** x, y and radius of a light on the face - Vision's gem - or null for none. */
        double[] gem;
        int gemColor = 0xffc94a;
        int mouthColor = 0x1c0709;
        int teethColor = 0x857c76;
        int tongueColor = 0x4a1519;

        Landmarks scaled(double k) {
            Landmarks s = new Landmarks();
            s.leftEye = times(leftEye, k);
            s.rightEye = times(rightEye, k);
            s.mouthLeft = times(mouthLeft, k);
            s.mouthRight = times(mouthRight, k);
            s.mouthMiddle = times(mouthMiddle, k);
            s.nose = times(nose, k);
            s.chin = times(chin, k);
            s.neck = times(neck, k);
            s.head = times(head, k);
            s.jaw = jaw * k;
            s.gem = gem == null ? null : times(gem, k);
            s.gemColor = gemColor;
            s.mouthColor = mouthColor;
            s.teethColor = teethColor;
            s.tongueColor = tongueColor;
            return s;
        }

        private static double[] times(double[] v, double k) {
            double[] out = v.clone();
            for (int i = 0; i < out.length; i++) out[i] *= k;
            return out;
        }
    }

    /** Everything that moves, set afresh for every frame. */
    static final class Pose {
        /** How far the jaw has dropped, 0..1. */
        double open;
        /** Lips drawn back from closed teeth, as for an "s", 0..1. */
        double part;
        /** Lip shape, -1 rounded .. 1 wide. */
        double width;
        /** 0 eyes open .. 1 shut. */
        double blink;
        /** 0 at rest .. 1 raised. */
        double brow;
        /** Where the eyes look, -1..1 each way; positive is right and down. */
        double gazeX, gazeY;
        /** Head roll in radians, and its drift in pixels. */
        double tilt, shiftX, shiftY;
        /** The light on the face, 0..1. */
        double glow;
        /** A red wash over everything, for when something has gone wrong, 0..1. */
        double alarm;
    }

    /** Knots per column: four for an eye, five for the mouth, room to spare. */
    private static final int K = 12;

    private final int w, h;
    private final int[] src;
    private final int[] still;
    private final float[] vignette;
    private final float[] headWeight;
    private final int boxX0, boxY0, boxX1, boxY1;

    // The face's frame: origin between the eyes, turned to level them.
    private final double ox, oy, cos, sin;

    private final double[] eyeU = new double[2], eyeV = new double[2], eyeW = new double[2], eyeH = new double[2];
    private final double mouthU, mouthHalf, mouthLeftU, mouthRightU, seamA, seamB, seamC;
    private final double noseV, chinV, jawHalf, lipReach;
    private final double jawMax, upperMax, partMax, mouthW;
    private final double neckX, neckY;
    private final double gemX, gemY, gemR;
    private final boolean hasGem;
    private final int gemColor, mouthColor, teethColor, tongueColor;

    // Per column of the face's frame: how its pixels move vertically this frame.
    private final double uMin;
    private final int cols;
    private final float[] knotSrc, knotDst;
    private final int[] knots, gapAt;

    FaceRig(int[] pixels, int width, int height, Landmarks lm) {
        this.w = width;
        this.h = height;
        this.src = pixels;

        double[] a = lm.leftEye[0] <= lm.rightEye[0] ? lm.leftEye : lm.rightEye;
        double[] b = a == lm.leftEye ? lm.rightEye : lm.leftEye;
        double roll = Math.atan2(b[1] - a[1], b[0] - a[0]);
        cos = Math.cos(roll);
        sin = Math.sin(roll);
        ox = (a[0] + b[0]) / 2;
        oy = (a[1] + b[1]) / 2;

        double[][] eyes = {a, b};
        for (int i = 0; i < 2; i++) {
            eyeU[i] = u(eyes[i][0], eyes[i][1]);
            eyeV[i] = v(eyes[i][0], eyes[i][1]);
            eyeW[i] = eyes[i][2];
            eyeH[i] = eyes[i][3];
        }

        double u1 = u(lm.mouthLeft[0], lm.mouthLeft[1]), v1 = v(lm.mouthLeft[0], lm.mouthLeft[1]);
        double u2 = u(lm.mouthMiddle[0], lm.mouthMiddle[1]), v2 = v(lm.mouthMiddle[0], lm.mouthMiddle[1]);
        double u3 = u(lm.mouthRight[0], lm.mouthRight[1]), v3 = v(lm.mouthRight[0], lm.mouthRight[1]);
        // The line the lips meet along, as a parabola through the corners and the middle.
        double d = (u1 - u2) * (u1 - u3) * (u2 - u3);
        seamA = (u3 * (v2 - v1) + u2 * (v1 - v3) + u1 * (v3 - v2)) / d;
        seamB = (u3 * u3 * (v1 - v2) + u2 * u2 * (v3 - v1) + u1 * u1 * (v2 - v3)) / d;
        seamC = (u2 * u3 * (u2 - u3) * v1 + u3 * u1 * (u3 - u1) * v2 + u1 * u2 * (u1 - u2) * v3) / d;
        mouthLeftU = Math.min(u1, u3);
        mouthRightU = Math.max(u1, u3);
        mouthU = (mouthLeftU + mouthRightU) / 2;
        mouthHalf = (mouthRightU - mouthLeftU) / 2;
        mouthW = mouthHalf * 2;

        noseV = v(lm.nose[0], lm.nose[1]);
        chinV = v(lm.chin[0], lm.chin[1]);
        jawHalf = lm.jaw > 0 ? lm.jaw : mouthW * 0.9;
        lipReach = (chinV - noseV) * 0.55;

        // How far things move at their fullest, in proportion to the mouth.
        double gapMax = mouthW * 0.30;
        jawMax = gapMax * 0.72;
        upperMax = gapMax * 0.28;
        partMax = mouthW * 0.07;

        neckX = lm.neck[0];
        neckY = lm.neck[1];

        hasGem = lm.gem != null;
        gemX = hasGem ? lm.gem[0] : 0;
        gemY = hasGem ? lm.gem[1] : 0;
        gemR = hasGem ? lm.gem[2] : 0;
        gemColor = lm.gemColor;
        mouthColor = lm.mouthColor;
        teethColor = lm.teethColor;
        tongueColor = lm.tongueColor;

        // Columns of the face's frame, wide enough for the whole picture turned.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double[] corner : new double[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) {
            double cu = u(corner[0], corner[1]);
            lo = Math.min(lo, cu);
            hi = Math.max(hi, cu);
        }
        uMin = Math.floor(lo) - 1;
        cols = (int) Math.ceil(hi - uMin) + 2;
        knotSrc = new float[cols * K];
        knotDst = new float[cols * K];
        knots = new int[cols];
        gapAt = new int[cols];

        // The head, weighted 1 inside and fading to 0 at the shoulders and the background.
        double hu = u(lm.head[0], lm.head[1]), hv = v(lm.head[0], lm.head[1]);
        double hrx = lm.head[2], hry = lm.head[3];
        headWeight = new float[w * h];
        vignette = new float[w * h];
        still = new int[w * h];
        int x0 = w, y0 = h, x1 = 0, y1 = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double du = (u(x, y) - hu) / hrx, dv = (v(x, y) - hv) / hry;
                double weight = 1 - smooth(0.9, 1.2, Math.sqrt(du * du + dv * dv));
                headWeight[i] = (float) weight;
                if (weight > 0) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
                // A soft darkening towards the edges, deepest at the foot of the picture,
                // so the portrait sinks into the panel rather than ending at a hard line.
                double side = Math.min(x, w - 1 - x) / (w * 0.16);
                double top = y / (h * 0.08);
                double foot = (h - 1 - y) / (h * 0.24);
                double edge = Math.min(Math.min(side, top), foot);
                vignette[i] = (float) (0.3 + 0.7 * smooth(0, 1, edge));
                still[i] = finish(src[i], vignette[i], 0);
            }
        }
        // The jaw stretches the neck below the head's outline too, so that is in the box.
        double seamMid = seam(mouthU);
        double neckEndV = chinV + (chinV - seamMid) * 0.9;
        for (double[] c : new double[][]{{mouthU - jawHalf, noseV}, {mouthU + jawHalf, noseV},
                {mouthU - jawHalf, neckEndV}, {mouthU + jawHalf, neckEndV}}) {
            double cx = ox + c[0] * cos - c[1] * sin, cy = oy + c[0] * sin + c[1] * cos;
            x0 = Math.min(x0, (int) cx);
            x1 = Math.max(x1, (int) Math.ceil(cx));
            y0 = Math.min(y0, (int) cy);
            y1 = Math.max(y1, (int) Math.ceil(cy));
        }
        boxX0 = Math.max(0, x0 - 2);
        boxY0 = Math.max(0, y0 - 2);
        boxX1 = Math.min(w, x1 + 3);
        boxY1 = Math.min(h, y1 + 3);
    }

    /** Draw the face in this pose into out, which is w by h, ARGB. */
    void render(Pose p, int[] out) {
        prepare(p);

        double ca = Math.cos(p.tilt), sa = Math.sin(p.tilt);
        boolean tinted = p.alarm > 0.002;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            boolean inBox = y >= boxY0 && y < boxY1;
            int from = inBox ? boxX0 : w, to = inBox ? boxX1 : w;

            if (tinted) {
                for (int x = 0; x < from; x++) out[row + x] = finish(src[row + x], vignette[row + x], p.alarm);
                for (int x = to; x < w; x++) out[row + x] = finish(src[row + x], vignette[row + x], p.alarm);
            } else {
                System.arraycopy(still, row, out, row, from);
                if (to < w) System.arraycopy(still, row + to, out, row + to, w - to);
            }

            for (int x = from; x < to; x++) {
                int i = row + x;
                double px = x, py = y;
                double hw = headWeight[i];
                if (hw > 0) {
                    // Where this point of the head was before it moved.
                    double dx = x - neckX - p.shiftX, dy = y - neckY - p.shiftY;
                    double bx = neckX + dx * ca + dy * sa, by = neckY - dx * sa + dy * ca;
                    px += hw * (bx - x);
                    py += hw * (by - y);
                }
                out[i] = finish(face(px, py, p), vignette[i], p.alarm);
            }
        }

        if (hasGem && p.glow > 0.01) shine(p, out, ca, sa);
    }

    /** The colour at a point of the still head, after the face itself has moved. */
    private int face(double px, double py, Pose p) {
        double rx = px - ox, ry = py - oy;
        double u = rx * cos + ry * sin, v = -rx * sin + ry * cos;

        // The eyes look about: what is inside each opening slides, the lids stay put.
        if (p.gazeX != 0 || p.gazeY != 0) {
            for (int e = 0; e < 2; e++) {
                double du = (u - eyeU[e]) / (eyeW[e] * 0.5), dv = (v - eyeV[e]) / (eyeH[e] * 0.6);
                if (du <= -1 || du >= 1 || dv <= -1 || dv >= 1) continue;
                double d = du * du + dv * dv;
                if (d < 1) {
                    double m = (1 - d) * (1 - d);
                    u -= p.gazeX * eyeW[e] * 0.13 * m;
                    v -= p.gazeY * eyeH[e] * 0.16 * m;
                }
            }
        }

        // Lips drawn wide or pursed.
        double across = (u - mouthU) / (mouthHalf * 1.8);
        if (p.width != 0 && across > -1 && across < 1) {
            double down = (v - seam(u)) / lipReach;
            if (down > -1 && down < 1) {
                double k = p.width * (p.width > 0 ? 0.07 : 0.13) * bump(across) * bump(down);
                u = mouthU + (u - mouthU) / (1 + k);
            }
        }

        int c = (int) (u - uMin + 0.5);
        if (c >= 0 && c < cols && knots[c] > 0) {
            int base = c * K, n = knots[c];
            int g = gapAt[c];
            if (g >= 0) {
                double g0 = knotDst[base + g], g1 = knotDst[base + g + 1];
                double cover = Math.min(v + 0.5, g1) - Math.max(v - 0.5, g0);
                if (cover > 0) {
                    int inside = mouth(u, v, g0, g1);
                    if (cover >= 0.999) return inside;
                    // Half a pixel of lip: blend, so the edge of the mouth is not a staircase.
                    double lipV = v < (g0 + g1) / 2 ? Math.min(v, g0 - 0.01) : Math.max(v, g1 + 0.01);
                    return mix(sampleAt(u, column(base, n, lipV)), inside, cover);
                }
            }
            v = column(base, n, v);
        }
        return sampleAt(u, v);
    }

    /** Follow a column's knots from where a point is on screen to where it is in the picture. */
    private double column(int base, int n, double v) {
        if (v < knotDst[base] || v >= knotDst[base + n - 1]) return v;
        int k = 0;
        while (k < n - 2 && v >= knotDst[base + k + 1]) k++;
        double d0 = knotDst[base + k], d1 = knotDst[base + k + 1];
        double s0 = knotSrc[base + k], s1 = knotSrc[base + k + 1];
        return d1 > d0 ? s0 + (v - d0) * (s1 - s0) / (d1 - d0) : s0;
    }

    /** Lay out this frame's knots: which rows of each column go where. */
    private void prepare(Pose p) {
        for (int c = 0; c < cols; c++) {
            double u = uMin + c;
            int base = c * K, n = 0;
            gapAt[c] = -1;

            for (int e = 0; e < 2; e++) {
                double reach = eyeW[e] * 0.95;
                double off = Math.abs(u - eyeU[e]);
                if (off >= reach) continue;
                double x = (u - eyeU[e]) / (eyeW[e] * 0.5);
                double open = Math.abs(x) < 1 ? Math.pow(1 - x * x, 0.4) : 0;
                double ev = eyeV[e], eh = eyeH[e];
                double top = ev - eh * 0.5 * open;
                double bottom = ev + eh * 0.45 * open;
                double brow = ev - eh * 1.15;
                n = knot(base, n, ev - eh * 2.4, ev - eh * 2.4);
                n = knot(base, n, brow, brow - p.brow * eh * 0.35 * bump(off / reach));
                n = knot(base, n, top, top + p.blink * (bottom - top) * 0.97);
                n = knot(base, n, bottom, bottom);
            }

            double fromMouth = Math.abs(u - mouthU);
            if (fromMouth < jawHalf) {
                double s = seam(Math.max(mouthLeftU, Math.min(mouthRightU, u)));
                double x = (u - mouthU) / mouthHalf;
                double lips = Math.abs(x) < 1 ? Math.pow(1 - x * x, 0.6) : 0;
                double jaw = p.open * jawMax * (1 - smooth(0.45, 1, fromMouth / jawHalf));
                double up = Math.min((p.open * upperMax + p.part * partMax) * lips, (s - noseV) * 0.7);
                double down = jaw * lips;
                double neckEnd = chinV + (chinV - s) * 0.9;
                n = knot(base, n, noseV, noseV);
                if (up + down > 1e-3) gapAt[c] = n;
                n = knot(base, n, s, s - up);
                n = knot(base, n, s, s + down);
                n = knot(base, n, chinV, chinV + jaw);
                n = knot(base, n, neckEnd, neckEnd);
            }
            knots[c] = n;
        }
    }

    private int knot(int base, int n, double from, double to) {
        knotSrc[base + n] = (float) from;
        knotDst[base + n] = (float) to;
        return n + 1;
    }

    /** The inside of the mouth: teeth under the upper lip, the lower ones on a wide vowel, dark behind. */
    private int mouth(double u, double v, double g0, double g1) {
        // Edge pixels half inside the mouth are asked for too; they count as on its edge.
        double gap = Math.max(g1 - g0, 1e-3), below = Math.max(0, v - g0), above = Math.max(0, g1 - v);
        double x = (u - mouthU) / mouthHalf;
        double side = Math.max(0, 1 - x * x);
        double shade = 0.3 + 0.7 * side;
        // Teeth show across the middle of the mouth; towards the corners there is only dark.
        double teeth = clamp((0.7 - Math.abs(x)) / 0.3, 0, 1);
        double upper = Math.min(gap * 0.38, mouthW * 0.07) * teeth;
        double lower = Math.max(0, gap * 0.2 - mouthW * 0.03) * teeth;

        double tongue = clamp((below / gap - 0.5) / 0.5, 0, 1) * side;
        int dark = scale(mixRgb(mouthColor, tongueColor, tongue), 0.55 + 0.45 * shade);
        if (below < upper + 0.5) {
            // In the lip's shadow at the top, and blended into the dark at the bottom edge.
            double lit = 0.55 + 0.35 * Math.min(1, below / (upper * 0.5 + 1e-6));
            int tooth = scale(teethColor, lit * shade);
            return mix(tooth, dark, clamp(below - upper + 0.5, 0, 1));
        }
        if (above < lower + 0.5) {
            return mix(scale(teethColor, 0.45 * shade), dark, clamp(above - lower + 0.5, 0, 1));
        }
        return dark;
    }

    /** Light at the gem, carried with the head, added over whatever is under it. */
    private void shine(Pose p, int[] out, double ca, double sa) {
        double dx = gemX - neckX, dy = gemY - neckY;
        double cx = neckX + dx * ca - dy * sa + p.shiftX;
        double cy = neckY + dx * sa + dy * ca + p.shiftY;
        double reach = gemR * 3.2;
        int x0 = Math.max(0, (int) (cx - reach)), x1 = Math.min(w - 1, (int) (cx + reach) + 1);
        int y0 = Math.max(0, (int) (cy - reach)), y1 = Math.min(h - 1, (int) (cy + reach) + 1);
        int gr = gemColor >> 16 & 0xff, gg = gemColor >> 8 & 0xff, gb = gemColor & 0xff;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (gemR * gemR);
                double a = p.glow * (0.85 * Math.exp(-d2 * 1.4) + 0.3 * Math.exp(-d2 * 0.22));
                if (a < 0.004) continue;
                int i = y * w + x, c = out[i];
                out[i] = 0xff000000
                        | screen(c >> 16 & 0xff, gr, a) << 16
                        | screen(c >> 8 & 0xff, gg, a) << 8
                        | screen(c & 0xff, gb, a);
            }
        }
    }

    private int sampleAt(double u, double v) {
        return sample(ox + u * cos - v * sin, oy + u * sin + v * cos);
    }

    /** Bilinear, clamped to the picture, in 8-bit fixed point. */
    private int sample(double x, double y) {
        if (x < 0) x = 0;
        else if (x > w - 1.001) x = w - 1.001;
        if (y < 0) y = 0;
        else if (y > h - 1.001) y = h - 1.001;
        int x0 = (int) x, y0 = (int) y;
        int fx = (int) ((x - x0) * 256), fy = (int) ((y - y0) * 256);
        int i = y0 * w + x0;
        int p00 = src[i], p10 = src[i + 1], p01 = src[i + w], p11 = src[i + w + 1];
        // Red and blue ride together in one int, sixteen bits apart, so neither spills into the other.
        int rb = lerpRb(lerpRb(p00, p10, fx), lerpRb(p01, p11, fx), fy);
        int g = lerpG(lerpG(p00, p10, fx), lerpG(p01, p11, fx), fy);
        return rb | g;
    }

    private static int lerpRb(int a, int b, int t) {
        return ((a & 0xff00ff) * (256 - t) + (b & 0xff00ff) * t) >>> 8 & 0xff00ff;
    }

    private static int lerpG(int a, int b, int t) {
        return ((a & 0xff00) * (256 - t) + (b & 0xff00) * t) >>> 8 & 0xff00;
    }

    private static int finish(int rgb, float vignette, double alarm) {
        double r = (rgb >> 16 & 0xff) * vignette, g = (rgb >> 8 & 0xff) * vignette, b = (rgb & 0xff) * vignette;
        if (alarm > 0) {
            r += (255 - r) * 0.22 * alarm;
            g *= 1 - 0.3 * alarm;
            b *= 1 - 0.3 * alarm;
        }
        return 0xff000000 | (int) r << 16 | (int) g << 8 | (int) b;
    }

    private double seam(double u) {
        return (seamA * u + seamB) * u + seamC;
    }

    private double u(double x, double y) {
        return (x - ox) * cos + (y - oy) * sin;
    }

    private double v(double x, double y) {
        return -(x - ox) * sin + (y - oy) * cos;
    }

    private static int screen(int base, int light, double amount) {
        double l = light * amount;
        return (int) (255 - (255 - base) * (255 - l) / 255);
    }

    private static int scale(int rgb, double k) {
        k = Math.max(0, k);
        return (int) Math.min(255, (rgb >> 16 & 0xff) * k) << 16
                | (int) Math.min(255, (rgb >> 8 & 0xff) * k) << 8
                | (int) Math.min(255, (rgb & 0xff) * k);
    }

    private static int mixRgb(int a, int b, double t) {
        return (int) ((a >> 16 & 0xff) * (1 - t) + (b >> 16 & 0xff) * t) << 16
                | (int) ((a >> 8 & 0xff) * (1 - t) + (b >> 8 & 0xff) * t) << 8
                | (int) ((a & 0xff) * (1 - t) + (b & 0xff) * t);
    }

    private static int mix(int a, int b, double t) {
        return mixRgb(a, b, clamp(t, 0, 1));
    }

    /** 1 at the middle, easing to 0 at -1 and 1. */
    private static double bump(double x) {
        return x <= -1 || x >= 1 ? 0 : 0.5 + 0.5 * Math.cos(Math.PI * x);
    }

    private static double smooth(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
