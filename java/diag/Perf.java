package starmp.diag;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import starmp.NetManager;
import starmp.StarMPPlugin;

/**
 * 0.1.5.3 performance metrics: frame times, time spent in each part of the mod, Java memory and garbage collection,
 * network traffic per packet type, plus counts the campaign / combat code reports. One PERF line per second goes to
 * the debug log; the F10 overlay shows the same numbers; a summary goes to starsector.log every 30 s.
 */
public final class Perf {
    private Perf() {}

    // ---- frames (game thread only)
    private static long lastFrameNs, secStartNs, lastSummaryNs;
    private static float[] frames = new float[512];
    private static int nFrames;
    private static String ctx = "title";
    // 30 s summary
    private static int sumFrames, sumHitches; private static double sumMs, sumWorst;

    // ---- mod systems (game thread mostly; synchronized for the rare other thread)
    private static final Map<String, long[]> sys = new LinkedHashMap<>(); // name -> {ns this second, calls}

    // ---- network (any thread)
    private static final AtomicLongArray inBytes = new AtomicLongArray(256), outBytes = new AtomicLongArray(256), inPk = new AtomicLongArray(256), outPk = new AtomicLongArray(256);
    private static final AtomicLong pipeNs = new AtomicLong(), pipeMaxNs = new AtomicLong(), pipeWrites = new AtomicLong();
    private static long[] lastIn = new long[256], lastOut = new long[256];

    // ---- memory
    private static long lastGcCount, lastGcMs;

    /** Counts reported by the campaign / combat code for the current second (free text). */
    public static volatile String counts = "";
    /** Latest overlay lines (built once a second). */
    public static volatile String[] overlayLines = new String[0];

    public static long begin() { return Diag.enabled() ? System.nanoTime() : 0L; }
    public static void end(String name, long t0) {
        if (t0 == 0L) return; // logger off
        long d = System.nanoTime() - t0;
        synchronized (sys) {
            long[] a = sys.get(name);
            if (a == null) { a = new long[2]; sys.put(name, a); }
            a[0] += d; a[1]++;
        }
    }

    public static void netOut(byte[] d, int copies) { if (d == null || d.length == 0) return; int t = d[0] & 0xFF; outBytes.addAndGet(t, (long) d.length * copies); outPk.addAndGet(t, copies); }
    public static void netIn(byte[] d) { if (d == null || d.length == 0) return; int t = d[0] & 0xFF; inBytes.addAndGet(t, d.length); inPk.incrementAndGet(t); }
    public static void pipeWrite(long ns) { pipeNs.addAndGet(ns); pipeWrites.incrementAndGet(); long m; while (ns > (m = pipeMaxNs.get()) && !pipeMaxNs.compareAndSet(m, ns)) {} }

    /** Once per rendered frame from the screen that is up: "title", "campaign" or "combat". */
    public static void frame(String where) {
        if (!Diag.enabled()) return;
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            float ms = (now - lastFrameNs) / 1e6f;
            if (ms < 10_000f) {
                if (nFrames == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
                frames[nFrames++] = ms;
            }
        }
        lastFrameNs = now;
        if (!where.equals(ctx)) { ctx = where; }
        if (secStartNs == 0) { secStartNs = now; lastSummaryNs = now; return; }
        if (now - secStartNs < 1_000_000_000L) return;
        float secs = (now - secStartNs) / 1e9f;
        secStartNs = now;
        long t0 = begin();
        try { emit(secs); } catch (Throwable t) { StarMPPlugin.flog("perf: " + t); }
        end("Diag", t0);
    }

    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static void emit(float secs) {
        int n = nFrames; nFrames = 0;
        float[] fr = Arrays.copyOf(frames, n);
        Arrays.sort(fr);
        double sum = 0; int hitches = 0;
        for (float x : fr) { sum += x; if (x > 100f) hitches++; }
        float avg = n == 0 ? 0 : (float) (sum / n);
        float p95 = n == 0 ? 0 : fr[Math.min(n - 1, (int) (n * 0.95))], p99 = n == 0 ? 0 : fr[Math.min(n - 1, (int) (n * 0.99))], worst = n == 0 ? 0 : fr[n - 1];
        float fps = n / secs;
        sumFrames += n; sumMs += sum; sumHitches += hitches; sumWorst = Math.max(sumWorst, worst);

        // mod systems: ms per frame
        StringBuilder ms = new StringBuilder(); double modTotal = 0;
        List<Map.Entry<String, double[]>> sl = new ArrayList<>();
        synchronized (sys) {
            for (Map.Entry<String, long[]> e : sys.entrySet()) {
                double perFrame = n == 0 ? 0 : e.getValue()[0] / 1e6 / n;
                sl.add(new AbstractMap.SimpleEntry<>(e.getKey(), new double[]{perFrame, e.getValue()[0] / 1e6}));
                e.getValue()[0] = 0; e.getValue()[1] = 0;
            }
        }
        sl.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, double[]> e : sl) {
            // top-level names count toward the mod total; "Vanilla:" parts (the never-pause engine step) are the
            // game's own work done inside our tick and are taken back out; "Sub:" parts are breakdowns
            String k = e.getKey();
            if (k.startsWith("Vanilla:")) modTotal -= e.getValue()[0];
            else if (!k.startsWith("Sub:")) modTotal += e.getValue()[0];
            if (e.getValue()[1] < 0.05) continue;
            if (ms.length() > 0) ms.append(", ");
            ms.append(e.getKey()).append(' ').append(String.format(Locale.ROOT, "%.2f", e.getValue()[0]));
        }

        // memory + GC
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576, max = rt.maxMemory() / 1_048_576;
        long gcC = 0, gcT = 0;
        try { for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) { gcC += Math.max(0, g.getCollectionCount()); gcT += Math.max(0, g.getCollectionTime()); } } catch (Throwable ignored) {}
        long dGc = gcC - lastGcCount, dGcMs = gcT - lastGcMs; lastGcCount = gcC; lastGcMs = gcT;

        // network
        long inB = 0, outB = 0, inP = 0, outP = 0;
        StringBuilder top = new StringBuilder();
        long[] perType = new long[256];
        for (int t = 0; t < 256; t++) {
            long i = inBytes.get(t), o = outBytes.get(t);
            long di = i - lastIn[t], dO = o - lastOut[t]; lastIn[t] = i; lastOut[t] = o;
            inB += di; outB += dO; perType[t] = di + dO;
        }
        for (int t = 0; t < 256; t++) { inP += inPk.getAndSet(t, 0); outP += outPk.getAndSet(t, 0); }
        Integer[] order = new Integer[256]; for (int t = 0; t < 256; t++) order[t] = t;
        Arrays.sort(order, (a, b) -> Long.compare(perType[b], perType[a]));
        for (int k = 0; k < 4 && perType[order[k]] > 0; k++) { if (top.length() > 0) top.append(", "); top.append('#').append(order[k]).append(' ').append(f1(perType[order[k]] / 1024.0 / secs)).append("KB/s"); }
        long pw = pipeWrites.getAndSet(0), pns = pipeNs.getAndSet(0), pmax = pipeMaxNs.getAndSet(0);

        NetManager net = StarMPPlugin.net();
        StringBuilder peers = new StringBuilder();
        if (net != null) for (Integer id : net.peerIds()) {
            NetManager.Peer p = net.peer(id);
            if (p == null) continue;
            if (peers.length() > 0) peers.append(", ");
            peers.append(p.name).append(' ').append(p.pingMs).append("ms ").append(p.path);
        }

        String line = "ctx=" + ctx + " fps=" + f1(fps) + " frame avg=" + f1(avg) + " p95=" + f1(p95) + " p99=" + f1(p99) + " worst=" + f1(worst) + " hitches>100ms=" + hitches
                + " | mod ms/frame=" + String.format(Locale.ROOT, "%.2f", modTotal) + (ms.length() > 0 ? " [" + ms + "]" : "")
                + " | heap " + used + "/" + max + " MB gc " + dGc + " (" + dGcMs + " ms)"
                + " | net in " + f1(inB / 1024.0 / secs) + " KB/s " + inP + " pk, out " + f1(outB / 1024.0 / secs) + " KB/s " + outP + " pk" + (top.length() > 0 ? " [" + top + "]" : "")
                + " | pipe " + pw + " writes avg " + (pw == 0 ? "0" : f1(pns / 1e3 / pw)) + "us max " + f1(pmax / 1e3) + "us"
                + (peers.length() > 0 ? " | peers " + peers : "")
                + (counts.isEmpty() ? "" : " | " + counts);
        Diag.line("PERF", line);

        // overlay
        List<String> ov = new ArrayList<>();
        ov.add("StarMP " + StarMPPlugin.VERSION + " debug   F10 closes   F9 = bug report to desktop");
        ov.add("FPS " + Math.round(fps) + "   frame " + f1(avg) + " ms   1% low " + f1(p99) + " ms   worst " + f1(worst) + " ms   hitches " + hitches);
        ov.add("Mod " + String.format(Locale.ROOT, "%.2f", modTotal) + " ms/frame" + (ms.length() > 0 ? ": " + (ms.length() > 110 ? ms.substring(0, 110) + "..." : ms) : ""));
        ov.add("Java heap " + used + " / " + max + " MB   GC " + dGc + " (" + dGcMs + " ms) last second");
        ov.add("Net in " + f1(inB / 1024.0 / secs) + " KB/s   out " + f1(outB / 1024.0 / secs) + " KB/s   pipe max " + f1(pmax / 1e3) + " us");
        if (peers.length() > 0) ov.add("Ping " + peers + (Diag.hostRttMs() >= 0 ? "   clock vs host " + (-Diag.hostOffsetMs()) + " ms" : ""));
        if (!counts.isEmpty()) ov.add(counts);
        String sysT = Diag.sysText();
        if (!sysT.isEmpty()) ov.add(sysT);
        for (String h : Diag.hwText().split("\n")) if (!h.trim().isEmpty()) ov.add(h);
        overlayLines = ov.toArray(new String[0]);

        // 30 s summary in starsector.log
        long now = System.nanoTime();
        if (now - lastSummaryNs > 30_000_000_000L) {
            float s30 = (now - lastSummaryNs) / 1e9f; lastSummaryNs = now;
            StarMPPlugin.flog("perf 30s: ctx=" + ctx + " fps " + f1(sumFrames / s30) + " avg frame " + f1(sumFrames == 0 ? 0 : sumMs / sumFrames) + " ms worst " + f1(sumWorst)
                    + " ms hitches " + sumHitches + " | heap " + used + "/" + max + " MB | " + (ms.length() > 0 ? "mod " + ms : "mod idle") + (counts.isEmpty() ? "" : " | " + counts));
            sumFrames = 0; sumMs = 0; sumHitches = 0; sumWorst = 0;
        }
    }
}
