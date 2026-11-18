package org.nasedka.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Store builds do not download or install executable code from GitHub. */
public final class ChannelUpdater {
    private ChannelUpdater() {}
    public static boolean hasPending(Context c) { return false; }
    public static String label(Context c){return "Обновления через Google Play (Play Core SDK — следующий этап)";}
    public static void offer(Activity a,RemoteFeed.Release ignored){open(a);}
    public static void pending(Activity a){open(a);}
    private static void open(Activity a){
        Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+a.getPackageName()));
        try{a.startActivity(i);}catch(Exception e){a.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id="+a.getPackageName())));}
    }
}
