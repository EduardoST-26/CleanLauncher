package com.simple.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/** Shared look (neon blue/purple) and small helpers for all screens. */
final class Ui {

    // MCU source ids for ACTION_REQUEST_CHANGE_SOURCE: the "Source" column of
    // /nwdappconfig/app/ApplicationList.xml (not the AppID).
    static final int SOURCE_MUSIC = -128;
    static final int SOURCE_RADIO = 4;
    static final int SOURCE_BT_MUSIC = -125;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2C8C, 0xFF20206E, 0xFF371764});
        GradientDrawable glowLeft = new GradientDrawable();
        glowLeft.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowLeft.setGradientRadius(dp(c, 520));
        glowLeft.setGradientCenter(0.15f, 0.1f);
        glowLeft.setColors(new int[]{0x553FA9FF, 0x003FA9FF});
        GradientDrawable glowRight = new GradientDrawable();
        glowRight.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowRight.setGradientRadius(dp(c, 560));
        glowRight.setGradientCenter(0.95f, 0.95f);
        glowRight.setColors(new int[]{0x66B04CFF, 0x00B04CFF});
        return new LayerDrawable(new Drawable[]{base, glowLeft, glowRight});
    }

    static GradientDrawable card(Context c) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D5B6CFF, 0x333B3FB8});
        bg.setCornerRadius(dp(c, 18));
        bg.setStroke(dp(c, 1), 0x337F9CFF);
        return bg;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    /** Round icon button. filled = white disc with dark icon (primary action). */
    static ImageView iconButton(Context c, int icon, int sizeDp, boolean filled, Runnable action) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        int pad = Math.round(sizeDp * 0.24f);
        b.setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad));
        b.setBackground(circle(filled ? Color.WHITE : 0x26FFFFFF));
        b.setColorFilter(filled ? 0xFF2A1F6E : Color.WHITE);
        pressable(b, 0.88f);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    static void pressable(View v, float scale) {
        v.setClickable(true);
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(scale).scaleY(scale).alpha(0.8f).setDuration(90).start();
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
            }
            return false;
        });
    }

    static void launch(Context c, String component) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(ComponentName.unflattenFromString(component));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "No disponible", Toast.LENGTH_SHORT).show();
        }
    }

    /** Asks the NWD kernel to route this audio source to the speakers. */
    static void requestSource(Context c, int sourceId) {
        Intent i = new Intent("com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE");
        i.putExtra("extra_source_id", (byte) sourceId);
        c.sendBroadcast(i);
    }
}
