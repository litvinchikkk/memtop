package com.example.md3empty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Рут-скан через один вызов su: быстро (~100-300мс на 782 процесса).
// Строка формата: pid|pkg|rssKb|pssKb  (pss=-1 если не сняли)
// 1) fast: ps -A -o PID,NAME,RSS,CMDLINE — мгновенный список + RSS
// 2) full: bulk smaps_rollup loop — точный PSS поверх (один su-вызов)
public class RootProc {

    public static class Row {
        public int pid;
        public String pkg;   // пакет / команда
        public long rssKb;
        public long pssKb;   // -1 = нет данных
        public Row(int pid, String pkg, long rssKb, long pssKb) {
            this.pid = pid; this.pkg = pkg; this.rssKb = rssKb; this.pssKb = pssKb;
        }
        public long bestKb() { return pssKb >= 0 ? pssKb : rssKb; }
        public String flat() { return pid + "|" + pkg + "|" + rssKb + "|" + pssKb; }
    }

    // The caller owns the deadline; the worker may block in process startup, read or waitFor.
    private static String exec(String cmd, long timeoutMs) {
        final class Run implements Runnable {
            private Process process;
            private volatile boolean cancelled;
            private String output;

            synchronized void cancel() {
                cancelled = true;
                if (process != null) process.destroy();
            }

            @Override public void run() {
                Process p = null;
                try {
                    p = new ProcessBuilder("su", "-c", cmd).start();
                    synchronized (this) {
                        process = p;
                        if (cancelled) {
                            p.destroy();
                            return;
                        }
                    }
                    // stderr must be drained independently so it cannot fill its pipe.
                    final java.io.InputStream err = p.getErrorStream();
                    Thread errors = new Thread(() -> {
                        try {
                            byte[] bytes = new byte[4096];
                            while (err.read(bytes) != -1) {}
                        } catch (java.io.IOException ignored) {
                        } finally {
                            try { err.close(); } catch (java.io.IOException ignored) {}
                        }
                    }, "RootProc-stderr");
                    errors.setDaemon(true);
                    errors.start();

                    StringBuilder sb = new StringBuilder(1 << 16);
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream()))) {
                        char[] buf = new char[8192];
                        int n;
                        while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
                    }
                    p.waitFor();
                    output = sb.toString();
                } catch (Exception ignored) {
                } finally {
                    if (p != null) {
                        if (cancelled) p.destroy();
                        try { p.getOutputStream().close(); } catch (java.io.IOException ignored) {}
                    }
                }
            }
        }

        Run run = new Run();
        Thread worker = new Thread(run, "RootProc-exec");
        worker.setDaemon(true);
        worker.start();
        try {
            if (timeoutMs > 0) worker.join(timeoutMs);
            if (worker.isAlive()) {
                run.cancel();
                return null;
            }
            return run.output;
        } catch (InterruptedException e) {
            run.cancel();
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static boolean hasRoot() {
        String out = exec("id", 3000);
        return out != null && out.contains("uid=0");
    }

    // Память без натива: прямо /proc/meminfo (читается без рута), запас — через su.
    // Возвращает [totalKb, availKb, usedKb] или null.
    public static long[] memKb() {
        long[] m = parseMeminfo(readFile("/proc/meminfo"));
        if (m != null) return m;
        String out = exec("cat /proc/meminfo", 4000);
        return parseMeminfo(out);
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int n = fis.read(buf);
            fis.close();
            if (n > 0) return new String(buf, 0, n);
        } catch (Exception ignored) {}
        return null;
    }

    private static long parseFirstLong(String s, int fromIndex) {
        int len = s.length();
        int i = fromIndex;
        while (i < len && (s.charAt(i) < '0' || s.charAt(i) > '9')) i++;
        if (i >= len) return -1;
        long res = 0;
        while (i < len && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            res = res * 10 + (s.charAt(i) - '0');
            i++;
        }
        return res;
    }

    private static long[] parseMeminfo(String txt) {
        if (txt == null) return null;
        long total = -1, avail = -1;
        int len = txt.length();
        int start = 0;
        while (start < len) {
            int end = txt.indexOf('\n', start);
            if (end < 0) end = len;
            if (txt.startsWith("MemTotal:", start)) {
                total = parseFirstLong(txt, start + 9);
            } else if (txt.startsWith("MemAvailable:", start)) {
                avail = parseFirstLong(txt, start + 13);
            }
            if (total > 0 && avail >= 0) break;
            start = end + 1;
        }
        if (total <= 0) return null;
        if (avail < 0) avail = 0;
        return new long[]{total, avail, total - avail};
    }

    // Быстрый скан: один su ps. Возвращает отсортированные по RSS desc.
    public static List<Row> fastScan() {
        String out = exec("ps -A -o PID,NAME,RSS,CMDLINE", 8000);
        if (out == null || out.length() < 10) return null;
        List<Row> list = new ArrayList<>(700);
        int len = out.length();
        int start = 0;
        boolean firstLine = true;
        while (start < len) {
            int end = out.indexOf('\n', start);
            if (end < 0) end = len;
            if (firstLine) {
                firstLine = false;
                start = end + 1;
                continue;
            }
            // Parse line without heavy split
            int i = start;
            while (i < end && Character.isWhitespace(out.charAt(i))) i++;
            if (i < end) {
                // PID
                int pStart = i;
                while (i < end && !Character.isWhitespace(out.charAt(i))) i++;
                int pid = -1;
                try { pid = Integer.parseInt(out.substring(pStart, i)); } catch (Exception ignored) {}

                while (i < end && Character.isWhitespace(out.charAt(i))) i++;
                // NAME
                int nStart = i;
                while (i < end && !Character.isWhitespace(out.charAt(i))) i++;
                String name = (nStart < i) ? out.substring(nStart, i) : "?";

                while (i < end && Character.isWhitespace(out.charAt(i))) i++;
                // RSS
                int rStart = i;
                while (i < end && !Character.isWhitespace(out.charAt(i))) i++;
                long rss = 0;
                try { rss = Long.parseLong(out.substring(rStart, i)); } catch (Exception ignored) {}

                while (i < end && Character.isWhitespace(out.charAt(i))) i++;
                // CMDLINE
                String pkg = name;
                if (i < end) {
                    int cStart = i;
                    while (i < end && out.charAt(i) != ' ' && out.charAt(i) != '\t' && out.charAt(i) != '\r') i++;
                    String cmd = out.substring(cStart, i);
                    if (!cmd.isEmpty()) pkg = cmd;
                }
                if (pkg.startsWith("/")) {
                    int sl = pkg.lastIndexOf('/');
                    if (sl >= 0) pkg = pkg.substring(sl + 1);
                }
                if (pid > 0) {
                    list.add(new Row(pid, pkg, rss, -1));
                }
            }
            start = end + 1;
        }
        Collections.sort(list, (a, b) -> Long.compare(b.rssKb, a.rssKb));
        return list;
    }

    // Точный PSS одним su-вызовом: цикл по /proc, grep Pss. ~0.3-0.6с.
    // Возвращает pid->pss map через список "pid pss".
    public static void fillPss(List<Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        StringBuilder pids = new StringBuilder(rows.size() * 7);
        for (Row r : rows) pids.append(r.pid).append(' ');
        String out = exec(
            "for p in " + pids + "; do v=$(grep -m1 '^Pss:' /proc/$p/smaps_rollup 2>/dev/null); echo \"$p ${v#Pss:}\"; done",
            12000);
        if (out == null) return;
        String[] lines = out.split("\n");
        for (String ln : lines) {
            try {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                int sp = ln.indexOf(' ');
                int pid = Integer.parseInt(sp > 0 ? ln.substring(0, sp) : ln);
                String rest = sp > 0 ? ln.substring(sp + 1).trim().split("\\s+")[0] : "";
                long pss = rest.isEmpty() ? -1 : Long.parseLong(rest);
                if (pss >= 0) {
                    for (Row r : rows) {
                        if (r.pid == pid) { r.pssKb = pss; break; }
                    }
                }
            } catch (Exception ignored) {}
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.bestKb(), a.bestKb()));
    }
}
