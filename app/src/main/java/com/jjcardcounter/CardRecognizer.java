package com.jjcardcounter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 牌面识别（纯 Java，不依赖 OpenCV）。
 *
 * 算法与已在 PC 上用截图验证过的 Python 流程 1:1 对齐：
 *  - 每张牌的"牌角"（左上角花色+点数）在扇开的手牌和摊开的出牌区里都能看到，
 *    所以统一对牌角做识别。
 *  - 牌角统一裁成固定长宽比（0.75）再缩放到 36x48 灰度，避免不同分辨率/手牌区
 *    与出牌区长宽比不同导致的畸变。
 *  - 手牌区：相邻牌之间露出的桌面是暗谷 -> 在列均值上找暗谷（峰值）定位每张牌左缘。
 *  - 出牌区：亮牌在暗背景上是亮峰 -> 找亮峰定位每张牌，再按检测到的张数等分切片。
 *  - 用灰度归一化互相关(NCC)与模板比对，得分 >= 0.55 才计入。
 *
 * 模板文件：assets/cards/type{rank}_{i}.png（rank 0..14，每 rank 可有多张）。
 */
public class CardRecognizer {
    public static final int TW = 36, TH = 48;
    private static final double CORNER_ASPECT = 0.75;   // width / height
    private static final double NCC_THRESHOLD = 0.55;

    private final Context context;
    @SuppressWarnings("unchecked")
    private final List<double[]>[] templates = new List[15];
    private boolean loaded = false;

    private int bestRank = -1;
    private double bestScore = 0;

    public CardRecognizer(Context context) {
        this.context = context;
    }

    public void loadTemplates() {
        try {
            for (int rank = 0; rank < 15; rank++) {
                templates[rank] = new ArrayList<>();
                String prefix = "type" + rank + "_";
                String[] files = context.getAssets().list("cards");
                if (files == null) continue;
                Arrays.sort(files);
                for (String f : files) {
                    if (f.startsWith(prefix) && f.endsWith(".png")) {
                        InputStream is = context.getAssets().open("cards/" + f);
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        is.close();
                        if (bmp != null) templates[rank].add(loadGrayScaled(bmp));
                    }
                }
            }
            loaded = true;
        } catch (IOException e) {
            loaded = false;
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** 识别手牌区（扇开的一排牌），返回每种牌型(0..14)的出现次数 */
    public int[] recognizeHand(Bitmap area) {
        int[] counts = new int[15];
        if (!loaded || area == null) return counts;
        int w = area.getWidth(), h = area.getHeight();
        int[] gray = toGrayArray(area);
        int[] edges = detectCardEdges(gray, w, h);
        double cardW = 0.046 * w;
        int cw = Math.max(TW, (int) (0.45 * cardW));
        int ch = (int) (cw / CORNER_ASPECT);
        if (ch > h) ch = h;
        for (int ex : edges) {
            int x0 = Math.max(0, Math.min(w - cw, ex));
            Bitmap crop = Bitmap.createBitmap(area, x0, 0, cw, ch);
            classify(loadGrayScaled(crop));
            if (bestScore >= NCC_THRESHOLD) counts[bestRank]++;
        }
        return counts;
    }

    /**
     * 识别出牌区（摊开的一排牌），返回每种牌型(0..14)的出现次数。
     * 出牌区的牌是亮牌在暗背景上 -> detectPlayCenters 找亮峰得到牌数 N，
     * 然后按区域宽度均分成 N 份，取每份左缘的牌角做 NCC。
     * 注意：这种方式要求"出牌区裁剪框"正好包住那一排牌（牌从框左缘开始铺满），
     * 所以每张出牌区要裁剪得紧一点；坐标不准时 NCC 会偏低被阈值过滤，不会
     * 产生错误计数，只是该区暂时识别不到，待在手机上校准即可。
     */
    public int[] recognizePlay(Bitmap area) {
        int[] counts = new int[15];
        if (!loaded || area == null) return counts;
        int w = area.getWidth(), h = area.getHeight();
        int[] gray = toGrayArray(area);
        int[] centers = detectPlayCenters(gray, w, h);
        int n = centers.length;
        if (n == 0) return counts;
        double cwZone = (double) w / n;
        int cw = Math.max(TW, (int) (0.45 * cwZone));
        int ch = (int) (cw / CORNER_ASPECT);
        if (ch > h) ch = h;
        for (int i = 0; i < n; i++) {
            int x0 = (int) (i * cwZone);
            x0 = Math.max(0, Math.min(w - cw, x0));
            Bitmap crop = Bitmap.createBitmap(area, x0, 0, cw, ch);
            classify(loadGrayScaled(crop));
            if (bestScore >= NCC_THRESHOLD) counts[bestRank]++;
        }
        return counts;
    }

    // ---------------- internals ----------------

    private void classify(double[] corner) {
        bestRank = -1;
        bestScore = 0;
        for (int r = 0; r < 15; r++) {
            if (templates[r] == null) continue;
            for (double[] t : templates[r]) {
                double s = ncc(corner, t);
                if (s > bestScore) {
                    bestScore = s;
                    bestRank = r;
                }
            }
        }
    }

    private static double[] loadGrayScaled(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, TW, TH, true);
        int[] px = new int[TW * TH];
        s.getPixels(px, 0, TW, 0, 0, TW, TH);
        double[] g = new double[TW * TH];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            double gray = 0.299 * ((c >> 16) & 0xff) + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff);
            g[i] = gray;
        }
        return g;
    }

    private static int[] toGrayArray(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] g = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (int) (0.299 * ((c >> 16) & 0xff) + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff));
        }
        return g;
    }

    private static int[] detectCardEdges(int[] gray, int w, int h) {
        double[] colMean = columnMean(gray, w, h);
        double[] neg = new double[w];
        for (int i = 0; i < w; i++) neg[i] = -colMean[i];
        int minDist = Math.max(40, (int) (0.033 * w));
        int[] edges = findPeaks(neg, -170, minDist, 12);
        return restrictRange(edges, (int) (0.16 * w), (int) (0.87 * w));
    }

    private static int[] detectPlayCenters(int[] gray, int w, int h) {
        double[] colMean = columnMean(gray, w, h);
        double mean = 0;
        for (double v : colMean) mean += v;
        mean /= colMean.length;
        int minDist = Math.max(20, (int) (0.03 * w));
        int[] centers = findPeaks(colMean, mean + 8, minDist, 15);
        return restrictRange(centers, (int) (0.02 * w), (int) (0.98 * w));
    }

    private static double[] columnMean(int[] gray, int w, int h) {
        double[] cm = new double[w];
        for (int x = 0; x < w; x++) {
            double s = 0;
            for (int y = 0; y < h; y++) s += gray[y * w + x];
            cm[x] = s / h;
        }
        return cm;
    }

    /** 一维峰值检测：局部极大、>=height、满足 minDist 与 prominence */
    private static int[] findPeaks(double[] vals, double height, int minDist, double prom) {
        List<Integer> cand = new ArrayList<>();
        for (int i = 1; i < vals.length - 1; i++) {
            if (vals[i] >= height && vals[i] >= vals[i - 1] && vals[i] > vals[i + 1])
                cand.add(i);
        }
        List<Integer> promo = new ArrayList<>();
        for (int i : cand) {
            int lo = Math.max(0, i - minDist), hi = Math.min(vals.length - 1, i + minDist);
            double mn = Double.MAX_VALUE;
            for (int j = lo; j <= hi; j++) mn = Math.min(mn, vals[j]);
            if (vals[i] - mn >= prom) promo.add(i);
        }
        cand = promo;
        cand.sort((a, b) -> Double.compare(vals[b], vals[a]));
        boolean[] taken = new boolean[vals.length];
        List<Integer> out = new ArrayList<>();
        for (int i : cand) {
            if (taken[i]) continue;
            out.add(i);
            int lo = Math.max(0, i - minDist), hi = Math.min(vals.length - 1, i + minDist);
            for (int j = lo; j <= hi; j++) taken[j] = true;
        }
        out.sort(Integer::compare);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static int[] restrictRange(int[] xs, int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        for (int x : xs) if (x >= lo && x <= hi) out.add(x);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static double ncc(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        if (da == 0 || db == 0) return 0;
        return num / Math.sqrt(da * db);
    }
}
