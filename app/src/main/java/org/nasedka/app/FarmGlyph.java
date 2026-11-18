package org.nasedka.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Local vector pictograms. No network, emoji, font files or external icon SDKs. */
public final class FarmGlyph extends View {
    public static final int HOME=0, INCUBATION=1, JOURNAL=2, NEWS=3, SETTINGS=4;
    public static final int BIRD=5, FEED=6, FINANCE=7, EGGS=8, TASKS=9,
            REPORTS=10, NEST=11;
    private final int type;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF oval=new RectF();
    private int tint;

    public FarmGlyph(Context context,int type,int color) {
        super(context);
        this.type=type;this.tint=color;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public void setTint(int tint) {this.tint=tint;invalidate();}
    private void line(Canvas c,float a,float b,float d,float e) {c.drawLine(a,b,d,e,paint);}
    private void curve(Canvas c,float... points) {
        path.reset();
        path.moveTo(points[0],points[1]);
        for(int i=2;i+1<points.length;i+=2)path.lineTo(points[i],points[i+1]);
        c.drawPath(path,paint);
    }
    private void rounded(Canvas c,float l,float t,float r,float b,float rad) {
        c.drawRoundRect(l,t,r,b,rad,rad,paint);
    }
    private void circle(Canvas c,float x,float y,float radius) {c.drawCircle(x,y,radius,paint);}
    private void ellipse(Canvas c,float l,float t,float r,float b) {
        oval.set(l,t,r,b);c.drawOval(oval,paint);
    }
    private void egg(Canvas c,float x,float y,float width,float height) {
        path.reset();
        path.moveTo(x,y+height);
        path.cubicTo(x-width*.72f,y+height*.85f,
                x-width*.58f,y+height*.12f,x,y);
        path.cubicTo(x+width*.58f,y+height*.12f,
                x+width*.72f,y+height*.85f,x,y+height);
        path.close();c.drawPath(path,paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(getWidth()==0||getHeight()==0)return;
        canvas.save();
        float side=Math.min(getWidth(),getHeight());
        canvas.translate((getWidth()-side)/2f,(getHeight()-side)/2f);
        canvas.scale(side/48f,side/48f);
        paint.setColor(tint);paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.65f);paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch(type) {
            case HOME:
                curve(canvas,7,22,24,9,41,22);
                rounded(canvas,12,21,36,40,3);
                rounded(canvas,21,29,28,40,1);
                break;
            case INCUBATION:
                egg(canvas,24,10,22,29);
                curve(canvas,19,30,22,33,27,33,29,30);
                break;
            case JOURNAL:
                rounded(canvas,10,8,38,40,4);
                line(canvas,17,17,31,17);line(canvas,17,24,31,24);
                line(canvas,17,31,27,31);
                break;
            case NEWS:
                rounded(canvas,9,9,39,39,4);
                rounded(canvas,15,15,25,26,2);
                line(canvas,29,16,34,16);line(canvas,29,22,34,22);
                line(canvas,15,31,33,31);
                break;
            case SETTINGS:
                circle(canvas,24,24,7);
                circle(canvas,24,24,16);
                for(int i=0;i<8;i++){
                    double a=Math.PI*i/4.0;
                    line(canvas,(float)(24+16*Math.cos(a)),(float)(24+16*Math.sin(a)),
                            (float)(24+20*Math.cos(a)),(float)(24+20*Math.sin(a)));
                }
                break;
            case BIRD:
                ellipse(canvas,10,20,36,39);
                circle(canvas,31,16,7);
                curve(canvas,38,16,44,19,38,22);
                curve(canvas,12,27,5,22,6,17,13,23);
                curve(canvas,17,39,17,43,12,43);
                curve(canvas,29,39,29,43,34,43);
                circle(canvas,33,15,1);
                break;
            case FEED:
                line(canvas,24,8,24,41);
                for(int i=0;i<4;i++){
                    float y=14+i*7;
                    curve(canvas,24,y+5,14,y+2,11,y-1,20,y-3);
                    curve(canvas,24,y+5,34,y+2,37,y-1,28,y-3);
                }
                break;
            case FINANCE:
                ellipse(canvas,11,10,36,18);
                curve(canvas,11,14,11,34,36,34,36,14);
                ellipse(canvas,11,27,36,35);
                line(canvas,20,22,28,22);line(canvas,24,18,24,28);
                break;
            case EGGS:
                egg(canvas,16,12,13,20);
                egg(canvas,31,11,13,21);
                curve(canvas,9,32,12,39,36,39,40,32);
                break;
            case TASKS:
                rounded(canvas,10,9,38,40,4);
                curve(canvas,16,24,21,29,32,17);
                line(canvas,16,34,32,34);
                break;
            case REPORTS:
                line(canvas,9,39,40,39);line(canvas,9,10,9,39);
                rounded(canvas,15,25,20,38,1);
                rounded(canvas,25,17,30,38,1);
                rounded(canvas,35,11,40,38,1);
                break;
            case NEST:
                egg(canvas,24,8,17,22);
                curve(canvas,10,30,15,39,33,39,38,30);
                curve(canvas,9,34,18,42,30,42,39,34);
                break;
            default:
                circle(canvas,24,24,10);
        }
        canvas.restore();
    }
}
