package org.nasedka.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Sideload update flow, compiled ONLY into the GitHub-distributed APK. */
public final class ChannelUpdater {
    private ChannelUpdater() {}
    private static final String ID="apk_download_id", HASH="apk_download_sha", VERSION="apk_download_version";
    /** True only when Android DownloadManager still knows a nonfailed APK download. */
    public static boolean hasPending(Context c) {
        long id = Prefs.get(c).getLong(ID, 0);
        if (id <= 0) return false;
        DownloadManager manager = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return false;
        try (android.database.Cursor q = manager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) return false;
            int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            return status == DownloadManager.STATUS_SUCCESSFUL
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static String label(Context c){
        long id=Prefs.get(c).getLong(ID,0);
        if(id==0)return "Обновление из GitHub Releases";
        DownloadManager manager=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
        try(android.database.Cursor q=manager.query(new DownloadManager.Query().setFilterById(id))){
            if(q!=null&&q.moveToFirst()){
                int status=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if(status==DownloadManager.STATUS_SUCCESSFUL)return "APK загружен — можно установить";
                if(status==DownloadManager.STATUS_RUNNING || status==DownloadManager.STATUS_PENDING || status==DownloadManager.STATUS_PAUSED)return "APK загружается";
            }
        }catch(Exception ignored){}
        return "Загрузка не завершена";
    }
    public static void offer(Activity a,RemoteFeed.Release r){
        if(r==null){new AlertDialog.Builder(a).setMessage("Пока нет данных о выпуске.").setPositiveButton("ОК",null).show();return;}
        if(r.apk.isEmpty()||!r.sha256.matches("[a-f0-9]{64}")){
            new AlertDialog.Builder(a).setMessage("В GitHub Release отсутствует файл nasedka-github.apk или проверяемая SHA-256-сумма. Загрузка заблокирована ради безопасности.")
                    .setPositiveButton("Понятно",null).show();return;
        }
        if(!r.apk.startsWith("https://github.com/"+RemoteFeed.repository(a)+"/releases/download/"))return;
        String notes=r.notes.trim().isEmpty()?"В выпуске нет описания изменений.":r.notes;
        new AlertDialog.Builder(a).setTitle("Наседка "+r.version)
                .setMessage("Скачивать только из GitHub Releases. Перед установкой приложение сверит SHA-256.\n\n"+notes)
                .setNegativeButton("Позже",null).setPositiveButton("Скачать APK",(d,w)->download(a,r)).show();
    }
    private static File destination(Activity a,String version){
        File dir=a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if(dir==null)throw new IllegalStateException("Недоступна папка загрузки");
        return new File(dir,"nasedka-"+version.replaceAll("[^0-9.vV]","")+".apk");
    }
    private static void download(Activity a,RemoteFeed.Release r){
        try {
            File file=destination(a,r.version);
            DownloadManager oldManager=(DownloadManager)a.getSystemService(Context.DOWNLOAD_SERVICE);
            long oldId=Prefs.get(a).getLong(ID,0);
            if(oldId>0)oldManager.remove(oldId);
            if(file.exists()&&!file.delete())throw new IllegalStateException("Не удалось заменить старый файл");
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(r.apk));
            req.setTitle("Наседка "+r.version);
            req.setDescription("Обновление приложения, скачивается с GitHub");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(a,Environment.DIRECTORY_DOWNLOADS,file.getName());
            long id=oldManager.enqueue(req);
            Prefs.get(a).edit().putLong(ID,id).putString(HASH,r.sha256).putString(VERSION,r.version).apply();
            new AlertDialog.Builder(a).setTitle("Загрузка запущена")
                    .setMessage("APK загружается через Android. Когда загрузка завершится, откройте «Настройки → Обновления → Продолжить установку». Установка выполняется только после вашего подтверждения.")
                    .setPositiveButton("Понятно",null).show();
        }catch(Exception e){new AlertDialog.Builder(a).setMessage("Не удалось начать загрузку: "+e.getMessage()).setPositiveButton("ОК",null).show();}
    }
    public static void pending(Activity a){
        long id=Prefs.get(a).getLong(ID,0);
        if(id<=0){new AlertDialog.Builder(a).setMessage("Скачанного обновления пока нет.").setPositiveButton("ОК",null).show();return;}
        DownloadManager mgr=(DownloadManager)a.getSystemService(Context.DOWNLOAD_SERVICE);
        try(android.database.Cursor q=mgr.query(new DownloadManager.Query().setFilterById(id))){
            if(q==null || !q.moveToFirst()){error(a,"Файл загрузки не найден.");return;}
            int status=q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(status==DownloadManager.STATUS_FAILED){error(a,"Не удалось скачать APK. Попробуйте ещё раз.");return;}
            if(status!=DownloadManager.STATUS_SUCCESSFUL){
                long done=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total=q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String pct=total>0?(100*done/total)+"%":"ещё нет данных";
                new AlertDialog.Builder(a).setTitle("Загрузка продолжается").setMessage("Загружено: "+pct)
                        .setPositiveButton("Понятно",null).show();return;
            }
        }catch(Exception e){error(a,"Не удалось определить статус загрузки.");return;}
        File apk=destination(a,Prefs.get(a).getString(VERSION,""));
        String expected=Prefs.get(a).getString(HASH,"");
        new Thread(()->{
            boolean verified=false;
            try {verified=apk.isFile() && apk.length()>0 && expected.matches("[a-f0-9]{64}")&&expected.equals(sha256(apk));}
            catch(Exception ignored){}
            boolean valid=verified;
            a.runOnUiThread(()->{
                if(!valid){error(a,"Контрольная сумма APK не совпадает. Установка запрещена.");return;}
                new AlertDialog.Builder(a).setTitle("APK проверен")
                        .setMessage("SHA-256 совпадает с GitHub Release. Android дополнительно проверит подпись приложения и спросит разрешение на установку.")
                        .setNegativeButton("Позже",null).setPositiveButton("Установить",(d,w)->install(a,apk)).show();
            });
        }).start();
    }
    private static String sha256(File file)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[16384];int size;
        try(FileInputStream in=new FileInputStream(file)){while((size=in.read(buf))!=-1)md.update(buf,0,size);}
        StringBuilder hex=new StringBuilder();for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&0xff));return hex.toString();
    }
    private static void install(Activity a,File file){
        if(!a.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(a).setTitle("Разрешение Android")
                    .setMessage("Для установки APK из GitHub разрешите установку из этого приложения в настройках Android. После возврата нажмите «Продолжить установку» ещё раз.")
                    .setNegativeButton("Позже",null).setPositiveButton("Открыть настройки",(d,w)->{
                        try {a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())));}
                        catch(Exception ignored){error(a,"Не удалось открыть настройки разрешений.");}
                    }).show();return;
        }
        try {
            Uri uri=FileProvider.getUriForFile(a,a.getPackageName()+".apkprovider",file);
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(intent);
        }catch(Exception e){error(a,"Не удалось открыть установщик Android: "+e.getMessage());}
    }
    private static void error(Activity a,String message){new AlertDialog.Builder(a).setMessage(message).setPositiveButton("Понятно",null).show();}
}
