package com.example.md3empty;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ViewFlipper;

public class MainActivity extends Activity {
    private static final int TAG_PID = 0x7f0f1234;
    // Кэш скана: переживает recreate при смене темы — повторного скана нет.
    private static java.util.List<String[]> cachedRows = null;
    private static String cachedMemText = null;
    private static int cachedMemPct = -1;
    private static String cachedCount = null;
    private static long cacheTime = 0;
    private static final long CACHE_TTL = 90_000;
    private static int page = 0;

    private static final java.util.concurrent.ConcurrentHashMap<String, String> appNameCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    private volatile boolean scanning = false;
    private volatile boolean liveBusy = false;
    private ViewFlipper flipper;
    private final Handler memHandler = new Handler(Looper.getMainLooper());
    private final Runnable memTick = new Runnable() {
        @Override public void run() {
            refreshMem();
            memHandler.postDelayed(this, 2000);
        }
    };
    private final Handler procHandler = new Handler(Looper.getMainLooper());
    private volatile boolean liveRunning = false;
    private final Runnable procTick = new Runnable() {
        @Override public void run() {
            if (!liveRunning) return;
            refreshProcsLive();
            procHandler.postDelayed(this, 2500);
        }
    };

    private String getAppLabel(android.content.pm.PackageManager pm, String pkg) {
        String base = pkg;
        int ci = base.indexOf(':');
        if (ci > 0) base = base.substring(0, ci);
        String cached = appNameCache.get(base);
        if (cached != null) return cached;
        String title = pkg;
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(base, 0);
            CharSequence lb = pm.getApplicationLabel(ai);
            if (lb != null && lb.length() > 0) title = lb.toString();
        } catch (Exception ignored) {}
        appNameCache.put(base, title);
        return title;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        flipper = findViewById(R.id.flipper);
        flipper.setDisplayedChild(page);

        AnimHelper.popIn(findViewById(R.id.card_procs), 50);
        AnimHelper.popIn(findViewById(R.id.card_theme), 150);
        findViewById(R.id.navbar).startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.nav_up));

        setupProcList();
        setupPaletteRow();
        setupNav();
        updatePill();
    }

    @Override
    protected void onResume() {
        super.onResume();
        memHandler.post(memTick);
        liveRunning = true;
        procHandler.postDelayed(procTick, 2500);
    }

    @Override
    protected void onPause() {
        liveRunning = false;
        memHandler.removeCallbacks(memTick);
        procHandler.removeCallbacks(procTick);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (page == 1) {
            showPage(0);
            return;
        }
        super.onBackPressed();
    }

    private void showPage(int p) {
        if (p == page) return;
        if (p > page) {
            flipper.setInAnimation(this, R.anim.slide_in_right);
            flipper.setOutAnimation(this, R.anim.fade_out);
        } else {
            flipper.setInAnimation(this, R.anim.fade_in);
            flipper.setOutAnimation(this, R.anim.slide_out_right);
        }
        page = p;
        flipper.setDisplayedChild(p);
        updatePill(true);
    }

    private void updatePill(boolean animate) {
        View ind = findViewById(R.id.nav_indicator);
        View pill = findViewById(R.id.nav_pill_outer);
        if (ind == null || pill == null) return;
        pill.post(() -> {
            int w = pill.getWidth();
            if (w == 0) return;
            int count = 2;
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            int iw = (w - pad * 2) / count;
            android.view.ViewGroup.LayoutParams lp = ind.getLayoutParams();
            lp.width = iw;
            ind.setLayoutParams(lp);
            float target = pad + page * iw;
            if (animate) ind.animate().translationX(target - pad).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            else ind.setTranslationX(target - pad);
            float a0 = page == 0 ? 1f : 0.55f;
            float a1 = page == 1 ? 1f : 0.55f;
            View b0 = findViewById(R.id.nav_btns);
            View b1 = findViewById(R.id.nav_settings);
            b0.animate().alpha(a0).setDuration(200).start();
            b1.animate().alpha(a1).setDuration(200).start();
        });
    }

    private void updatePill() { updatePill(false); }

    @Override
    protected void onDestroy() {
        scanning = false;
        liveRunning = false;
        memHandler.removeCallbacks(memTick);
        procHandler.removeCallbacks(procTick);
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private void refreshMem() {
        TextView memView = findViewById(R.id.mem_view);
        ProgressBar memBar = findViewById(R.id.mem_bar);
        if (memView == null || memBar == null) return;
        long[] memArr = RootProc.memKb();
        if (memArr == null) try { memArr = ProcNative.safeMem(); } catch (Exception ignored) {}
        if (memArr != null && memArr.length >= 3 && memArr[0] > 0) {
            long totalMb = memArr[0] / 1024;
            long usedMb = memArr[2] / 1024;
            cachedMemText = usedMb + " / " + totalMb + " МБ";
            cachedMemPct = (int) (memArr[2] * 100 / memArr[0]);
            memView.setText(cachedMemText);
            memBar.setProgress(cachedMemPct);
        }
    }

    private void refreshProcsLive() {
        if (scanning || liveBusy || backgroundExecutor.isShutdown()) return;
        liveBusy = true;
        backgroundExecutor.execute(() -> {
            try {
                java.util.List<RootProc.Row> rows;
                try { rows = RootProc.fastScan(); } catch (Exception e) { return; }
                if (rows == null || rows.isEmpty() || !liveRunning) return;
                int lim = Math.min(30, rows.size());
                java.util.List<RootProc.Row> top = new java.util.ArrayList<>(rows.subList(0, lim));
                try { RootProc.fillPss(top); } catch (Exception ignored) {}
                if (!liveRunning) return;
                runOnUiThread(() -> applyLiveRows(top, rows.size()));
            } finally {
                liveBusy = false;
            }
        });
    }

    private void applyLiveRows(java.util.List<RootProc.Row> top, int total) {
        LinearLayout list = findViewById(R.id.proc_list);
        TextView cnt = findViewById(R.id.proc_count);
        if (list == null) return;
        android.content.pm.PackageManager pm = getPackageManager();
        // Чистим мусор без pid-тега (скелетон, "Нужен root" и старые строки).
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            View v = list.getChildAt(i);
            Object tp = null;
            try { tp = v.getTag(TAG_PID); } catch (Exception ignored) {}
            if (!(tp instanceof Integer)) list.removeViewAt(i);
        }
        java.util.Map<Integer, View> pidToView = new java.util.HashMap<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            Object tagPid = null;
            try { tagPid = v.getTag(TAG_PID); } catch (Exception ignored) {}
            if (tagPid instanceof Integer) pidToView.put((Integer) tagPid, v);
        }
        java.util.Set<Integer> newPids = new java.util.HashSet<>();
        for (RootProc.Row r : top) newPids.add(r.pid);
        for (java.util.Iterator<java.util.Map.Entry<Integer, View>> it = pidToView.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Integer, View> e = it.next();
            if (!newPids.contains(e.getKey())) {
                View v = e.getValue();
                v.animate().alpha(0f).setDuration(180).withEndAction(() -> list.removeView(v)).start();
            }
        }
        java.util.List<String[]> fresh = new java.util.ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            RootProc.Row r = top.get(i);
            View existing = pidToView.get(r.pid);
            String mem = (r.bestKb() / 1024) + " МБ";
            String title = getAppLabel(pm, r.pkg);
            String sub = "PID " + r.pid + " • " + r.pkg;
            fresh.add(new String[]{String.valueOf(r.pid), title, sub, mem});
            if (existing != null) {
                TextView memV = (TextView) existing.getTag();
                if (memV != null && !mem.equals(memV.getText())) memV.setText(mem);
                if (list.indexOfChild(existing) != i) {
                    list.removeView(existing);
                    list.addView(existing, Math.min(i, list.getChildCount()));
                }
            } else {
                View row = createProcRow(r.pid, title, sub, mem);
                int pos = Math.min(i, list.getChildCount());
                list.addView(row, pos);
                AnimHelper.popIn(row, 0);
            }
        }
        if (cnt != null) cnt.setText(top.size() + " / " + total + " • live");
        cachedRows = fresh;
        cachedCount = top.size() + " / " + total + " • live";
        cacheTime = System.currentTimeMillis();
    }

    private View createProcRow(int pid, String title, String subText, String memStr) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.btn_tonal);
        row.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        row.setLayoutParams(lp);
        row.setClickable(true);
        TextView dot = new TextView(this); dot.setText("● "); dot.setTextSize(14); dot.setTextColor(getResources().getColor(android.R.color.holo_green_light)); row.addView(dot);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this); name.setText(title); name.setTextSize(15); name.setTypeface(null, android.graphics.Typeface.BOLD); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(name);
        TextView subV = new TextView(this); subV.setText(subText); subV.setTextSize(12); subV.setSingleLine(true); subV.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(subV); row.addView(col);
        TextView mem = new TextView(this); mem.setText(memStr); mem.setTextSize(13); mem.setTypeface(null, android.graphics.Typeface.BOLD); row.addView(mem);
        row.setTag(mem);
        try { row.setTag(TAG_PID, pid); } catch (Exception ignored) { row.setTag(pid); }
        AnimHelper.juicy(row);
        return row;
    }

    private void setupProcList() {
        TextView memView = findViewById(R.id.mem_view);
        ProgressBar memBar = findViewById(R.id.mem_bar);
        TextView cnt = findViewById(R.id.proc_count);
        LinearLayout list = findViewById(R.id.proc_list);

        // Свежий кэш (после смены темы) — рисуем мгновенно, без скана.
        if (cachedRows != null && !cachedRows.isEmpty()
                && System.currentTimeMillis() - cacheTime < CACHE_TTL) {
            if (cachedMemText != null) memView.setText(cachedMemText);
            if (cachedMemPct >= 0) memBar.setProgress(cachedMemPct);
            list.removeAllViews();
            for (String[] r : cachedRows) {
                addProcRow(list, Integer.parseInt(r[0]), r[1], r[2], r[3], false);
            }
            if (cachedCount != null) cnt.setText(cachedCount);
            return;
        }

        // Память: сначала Java напрямую (всегда читается), натив — запас.
        long[] memArr = RootProc.memKb();
        if (memArr == null) try { memArr = ProcNative.safeMem(); } catch (Exception ignored) {}
        if (memArr != null && memArr.length >= 3 && memArr[0] > 0) {
            long totalMb = memArr[0] / 1024;
            long usedMb = memArr[2] / 1024;
            cachedMemText = usedMb + " / " + totalMb + " МБ";
            cachedMemPct = (int) (memArr[2] * 100 / memArr[0]);
            memView.setText(cachedMemText);
            memBar.setProgress(cachedMemPct);
        } else {
            memView.setText("нет данных");
        }

        // Скелетон: сразу видно что скан идёт.
        list.removeAllViews();
        cnt.setText("скан…");
        for (int i = 0; i < 3; i++) {
            TextView sk = new TextView(this);
            sk.setText("• скан…");
            sk.setAlpha(0.4f);
            sk.setPadding(24, 16, 24, 16);
            list.addView(sk);
        }

        // Фон: рут-скан → стрим по одному с анимацией.
        scanning = true;
        backgroundExecutor.execute(() -> {
            java.util.List<RootProc.Row> rows = null;
            boolean isRoot = false;
            try { rows = RootProc.fastScan(); } catch (Exception ignored) {}
            if (rows != null && !rows.isEmpty()) isRoot = true;
            if (!isRoot) {
                scanning = false;
                runOnUiThread(() -> {
                    list.removeAllViews();
                    TextView t = new TextView(MainActivity.this);
                    t.setText("Нужен root");
                    t.setPadding(24, 16, 24, 16);
                    list.addView(t);
                    cnt.setText("нет доступа");
                });
                return;
            }

            final java.util.List<RootProc.Row> fRows = rows;
            final int total = rows.size();
            final int lim = Math.min(30, rows.size());
            final java.util.List<String[]> fresh = new java.util.ArrayList<>();
            android.content.pm.PackageManager pm = getPackageManager();
            for (int i = 0; i < lim && scanning; i++) {
                RootProc.Row r = fRows.get(i);
                final String fTitle = getAppLabel(pm, r.pkg);
                final String fPkg = r.pkg;
                final int fPid = r.pid;
                final String fMem = (r.rssKb / 1024) + " МБ";
                final String fSub = "PID " + fPid + " • " + fPkg;
                final int idx = i;
                fresh.add(new String[]{String.valueOf(fPid), fTitle, fSub, fMem});
                runOnUiThread(() -> {
                    if (idx == 0) list.removeAllViews();
                    addProcRow(list, fPid, fTitle, fSub, fMem, true);
                    cnt.setText((idx + 1) + " / " + total + " • root+RSS");
                });
                try { Thread.sleep(35); } catch (InterruptedException e) { return; }
            }
            // Уточнение PSS поверх (без перетасовки — плавно обновляем цифры).
            final java.util.List<RootProc.Row> topRows = fRows.subList(0, lim);
            try { RootProc.fillPss(topRows); } catch (Exception ignored) {}
            if (!scanning) return;
            runOnUiThread(() -> {
                for (int i = 0; i < topRows.size() && i < list.getChildCount(); i++) {
                    RootProc.Row r = topRows.get(i);
                    android.view.View row = list.getChildAt(i);
                    Object tag = row.getTag();
                    String mb = (r.bestKb() / 1024) + " МБ";
                    if (tag instanceof TextView) {
                        ((TextView) tag).setText(mb);
                    }
                    if (i < fresh.size()) fresh.get(i)[3] = mb;
                }
                String done = topRows.size() + " / " + total + " • root+PSS";
                cnt.setText(done);
                cachedRows = fresh;
                cachedCount = done;
                cacheTime = System.currentTimeMillis();
                scanning = false;
            });
        });
        scanThread.start();
    }

    private void addProcRow(LinearLayout list, int pid, String title,
                            String subText, String memStr, boolean animate) {
            View row = createProcRow(pid, title, subText, memStr);
            list.addView(row);
            if (animate) AnimHelper.popIn(row, 0);
    }

    private void setupPaletteRow() {
        int[] dots = {R.id.dot_espresso, R.id.dot_indigo, R.id.dot_forest,
                R.id.dot_sakura, R.id.dot_ocean, R.id.dot_sunset,
                R.id.dot_grape, R.id.dot_mono, R.id.dot_lime};
        String[] pals = {"espresso", "indigo", "forest",
                "sakura", "ocean", "sunset",
                "grape", "mono", "lime"};
        for (int i = 0; i < dots.length; i++) {
            final String pal = pals[i];
            View d = findViewById(dots[i]);
            AnimHelper.juicy(d);
            d.setOnClickListener(v -> {
                ThemeManager.setPalette(this, pal);
                recreate();
            });
        }
        Button[] chips = {(Button) findViewById(R.id.chip_system),
                (Button) findViewById(R.id.chip_light),
                (Button) findViewById(R.id.chip_dark)};
        String[] modes = {ThemeManager.MODE_SYSTEM, ThemeManager.MODE_LIGHT, ThemeManager.MODE_DARK};
        String cur = ThemeManager.getMode(this);
        for (int i = 0; i < chips.length; i++) {
            final String m = modes[i];
            chips[i].setBackgroundResource(m.equals(cur) ? R.drawable.chip_on : R.drawable.chip_off);
            AnimHelper.juicy(chips[i]);
            chips[i].setOnClickListener(v -> {
                ThemeManager.setMode(this, m);
                recreate();
            });
        }
    }

    private void setupNav() {
        int[] navs = {R.id.nav_btns, R.id.nav_settings};
        for (int id : navs) AnimHelper.juicy(findViewById(id));
        findViewById(R.id.nav_btns).setOnClickListener(v -> {
            showPage(0);
            ((android.widget.ScrollView) findViewById(R.id.scroll)).smoothScrollTo(0, 0);
        });
        findViewById(R.id.nav_settings).setOnClickListener(v -> showPage(1));
    }
}
