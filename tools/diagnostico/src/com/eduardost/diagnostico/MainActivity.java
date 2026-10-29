package com.eduardost.diagnostico;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Collects a read-only report of the head unit (see {@link Report}), shows the summary on screen
 * and saves the full report to the internal Download folder and to every pendrive it can write to.
 * It changes nothing on the radio.
 */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, summary;
    private Button again;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(0xFF101418);

        LinearLayout top = new LinearLayout(this);
        TextView title = text("Diagnóstico del radio", 22, 0xFFFFFFFF);
        again = new Button(this);
        again.setText("Repetir y guardar");
        again.setOnClickListener(v -> run());
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(again);

        status = text("", 15, 0xFF8BE28B);
        summary = text("", 13, 0xFFE0E0E0);
        summary.setTypeface(Typeface.MONOSPACE);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(summary);

        root.addView(top);
        root.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        run();
    }

    private void run() {
        again.setEnabled(false);
        status.setText("Revisando el radio… puede tardar un minuto.");
        summary.setText("");
        new Thread(() -> {
            Report report = new Report(this);
            String full = report.collect(msg -> ui.post(() -> status.setText(msg)));
            String where = save(full);
            ui.post(() -> {
                status.setText(where);
                summary.setText(report.summary());
                again.setEnabled(true);
            });
        }, "diagnostico").start();
    }

    /** Writes the report everywhere it can; returns a message listing where it landed. */
    private String save(String text) {
        String name = "diagnostico_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".txt";
        Map<String, Boolean> dirs = new LinkedHashMap<>(); // path -> is removable
        dirs.put(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath(), false);
        for (File d : Report.usbRoots()) dirs.put(d.getPath(), true);
        File[] own = getExternalFilesDirs(null);
        for (int i = 0; i < own.length; i++) if (own[i] != null) dirs.put(own[i].getPath(), i > 0);

        StringBuilder ok = new StringBuilder();
        boolean onUsb = false;
        for (Map.Entry<String, Boolean> e : dirs.entrySet()) {
            File f = new File(e.getKey(), name);
            try {
                f.getParentFile().mkdirs();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
                    w.write(text);
                }
                ok.append("\n   ").append(f.getPath());
                onUsb |= e.getValue();
            } catch (Exception ignored) {
                // Not writable (read-only mount, no permission): try the next one.
            }
        }
        if (ok.length() == 0) return "No pude guardar el reporte en ningún lado.";
        return (onUsb ? "Listo. Reporte guardado, también en el pendrive:"
                : "Listo, pero NO encontré un pendrive donde escribir. Guardado en:") + ok;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
