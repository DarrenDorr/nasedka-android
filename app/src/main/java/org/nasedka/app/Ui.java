package org.nasedka.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native Android design system. Built without network-loaded UI frameworks. */
public final class Ui {
    private Ui() {}
    public static final int BACKGROUND = Color.rgb(244, 247, 242);
    public static final int WHITE = Color.WHITE;
    public static final int GREEN = Color.rgb(35, 112, 78);
    public static final int DARK = Color.rgb(32, 58, 45);
    public static final int MUTED = Color.rgb(97, 117, 103);
    public static final int STROKE = Color.rgb(224, 233, 225);
    public static final int AMBER = Color.rgb(148, 91, 23);

    public static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    public static GradientDrawable bg(int color, float corner, int stroke, int strokeColor, Context c) {
        GradientDrawable b = new GradientDrawable();
        b.setColor(color); b.setCornerRadius(dp(c, corner));
        if (stroke > 0) b.setStroke(dp(c, stroke), strokeColor);
        return b;
    }
    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(dp(c, 2), 1f);
        return t;
    }
    public static LinearLayout card(Context c) {
        LinearLayout l = vertical(c);
        l.setBackground(bg(WHITE, 18, 1, STROKE, c));
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        return l;
    }
    public static TextView button(Context c, String label, boolean filled) {
        TextView b = text(c, label, 15, filled ? WHITE : GREEN, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 15), dp(c, 10), dp(c, 15), dp(c, 10));
        b.setBackground(bg(filled ? GREEN : Color.rgb(232, 244, 235), 13, 0, 0, c));
        b.setClickable(true); b.setFocusable(true);
        return b;
    }
    public static EditText field(Context c, String hint, String value, boolean number) {
        EditText e = new EditText(c);
        e.setSingleLine(true); e.setHint(hint);
        if (value != null) e.setText(value);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextSize(16);
        e.setTextColor(DARK);
        e.setHintTextColor(MUTED);
        return e;
    }
    public static void space(LinearLayout parent, Context c, int dp) {
        View v = new View(c);
        parent.addView(v, new LinearLayout.LayoutParams(1, Ui.dp(c, dp)));
    }
    public static void addMargin(LinearLayout parent, View child, Context c, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, bottom);
        parent.addView(child, lp);
    }
    public static void label(LinearLayout parent, Context c, String label) {
        addMargin(parent, text(c, label, 13, MUTED, true), c, 3);
    }
}
