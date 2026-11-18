package org.nasedka.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.Drawable;

/** Small native Android design system. Built without network-loaded UI frameworks. */
public final class Ui {
    private Ui() {}
    public static final int BACKGROUND = 0xFFF7F8F4;
    public static final int WHITE = Color.WHITE;
    public static final int GREEN = 0xFF247653;
    public static final int GREEN_DARK = 0xFF16583E;
    public static final int PALE = 0xFFE8F3EC;
    public static final int CREAM = 0xFFF6ECCD;
    public static final int DARK = 0xFF203B2F;
    public static final int MUTED = 0xFF66756B;
    public static final int STROKE = 0xFFDFE7DF;
    public static final int AMBER = 0xFF996120;

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
        l.setBackground(bg(WHITE, 20, 1, STROKE, c));
        l.setPadding(dp(c, 17), dp(c, 16), dp(c, 17), dp(c, 16));
        return l;
    }
    public static GradientDrawable heroBg(Context c) {
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFE2F1E7,0xFFF2F7E8});
        g.setCornerRadius(dp(c,24));
        return g;
    }
    public static Drawable ripple(Context c,int base,float radius) {
        return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22247653),
                bg(base,radius,0,0,c),null);
    }
    public static LinearLayout iconCircle(Context c,int glyph,int tint,int base) {
        LinearLayout circle=horizontal(c);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(bg(base,15,0,0,c));
        FarmGlyph icon=new FarmGlyph(c,glyph,tint);
        circle.addView(icon,new LinearLayout.LayoutParams(dp(c,27),dp(c,27)));
        return circle;
    }
    public static LinearLayout sectionTitle(Context c,String label,String count) {
        LinearLayout line=horizontal(c);
        TextView title=text(c,label,19,DARK,true);
        line.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(count!=null && !count.isEmpty())line.addView(text(c,count,12,MUTED,false));
        return line;
    }
    public static TextView button(Context c, String label, boolean filled) {
        TextView b = text(c, label, 15, filled ? WHITE : GREEN, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 15), dp(c, 10), dp(c, 15), dp(c, 10));
        b.setBackground(ripple(c,filled ? GREEN : PALE,14));
        b.setClickable(true); b.setFocusable(true);
        b.setContentDescription(label);
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
