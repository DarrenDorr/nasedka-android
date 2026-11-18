package org.nasedka.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** RuStore appupdate SDK integration will replace the store-page fallback after moderation. */
public final class ChannelUpdater {
    private ChannelUpdater() {}
    public static boolean hasPending(Context c) { return false; }
    public static String label(Context c){return "Обновления через RuStore (SDK — после регистрации приложения)";}
    public static void offer(Activity a,RemoteFeed.Release ignored){open(a);}
    public static void pending(Activity a){open(a);}
    private static void open(Activity a){
        a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.rustore.ru/catalog/app/"+a.getPackageName())));
    }
}
