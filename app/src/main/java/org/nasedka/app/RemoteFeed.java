package org.nasedka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public GitHub metadata only. The incubation database is never transmitted. */
public final class RemoteFeed {
    public interface Callback { void finished(String message); }
    public static final long INTERVAL = 24L*60*60*1000;
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private RemoteFeed() {}

    public static final class Release {
        public final String version, notes, url, apk, sha256;
        Release(String version, String notes, String url, String apk, String sha256) {
            this.version=version;this.notes=notes;this.url=url;this.apk=apk;this.sha256=sha256;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("version",version).put("notes",notes).put("url",url)
                    .put("apk",apk).put("sha256",sha256);
        }
        static Release parse(JSONObject j) {
            return new Release(j.optString("version"), j.optString("notes"),j.optString("url"),
                    j.optString("apk"),j.optString("sha256"));
        }
    }
    public static final class News {
        public final String date, title, text;
        News(String date, String title, String text) {this.date=date;this.title=title;this.text=text;}
    }

    /** Official, pinned release origin; forks must change this at build time. */
    public static final String OFFICIAL_REPO = "DarrenDorr/nasedka-android";
    public static String repository(Context c) { return OFFICIAL_REPO; }
    public static boolean validRepo(String value) {
        return value!=null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*/[a-zA-Z0-9][a-zA-Z0-9._-]*")
                && !value.contains("..");
    }
    public static String channel() {return BuildConfig.FLAVOR;}
    public static boolean isGithub() {return "github".equals(channel());}
    public static Release cachedRelease(Context c) {
        try {String s=Prefs.get(c).getString("github_release","");return s.isEmpty()?null:Release.parse(new JSONObject(s));}
        catch(Exception ignored){return null;}
    }
    public static boolean hasUpdate(Context c) {
        Release release=cachedRelease(c);
        try {return isGithub() && release!=null && VersionCompare.compare(release.version,BuildConfig.VERSION_NAME)>0;}
        catch(Exception ignored){return false;}
    }
    public static long checkedAt(Context c) {return Prefs.get(c).getLong("github_checked",0);}

    public static void refresh(Context context, boolean force, Callback callback) {
        Context c=context.getApplicationContext();
        String slug=repository(c);
        if (!validRepo(slug)) {if(callback!=null)MAIN.post(()->callback.finished("Сначала укажите адрес репозитория owner/repo в настройках."));return;}
        long now=System.currentTimeMillis();
        if (!force && checkedAt(c)>0 && now-checkedAt(c)<INTERVAL && now>=checkedAt(c)) return;
        Prefs.get(c).edit().putLong("github_checked", now).apply();
        NETWORK.execute(()->{
            String result;
            try {
                JSONObject release=readObject("https://api.github.com/repos/"+slug+"/releases/latest", 256*1024);
                String tag=release.getString("tag_name");
                VersionCompare.compare(tag, BuildConfig.VERSION_NAME); // rejects non-numeric/untrusted tags
                String body=limit(release.optString("body"),2200);
                String page=release.optString("html_url");
                if(!page.startsWith("https://github.com/"+slug+"/releases/tag/")) page="";
                String apk="",digest="";
                JSONArray assets=release.optJSONArray("assets");
                if(assets!=null)for(int i=0;i<assets.length();i++){
                    JSONObject asset=assets.optJSONObject(i);
                    if(asset==null || !"nasedka-github.apk".equals(asset.optString("name")))continue;
                    String url=asset.optString("browser_download_url");
                    String hash=asset.optString("digest").replaceFirst("^sha256:","");
                    if(url.startsWith("https://github.com/"+slug+"/releases/download/") && hash.matches("[a-fA-F0-9]{64}")){
                        apk=url;digest=hash.toLowerCase(java.util.Locale.ROOT);
                    }
                }
                Release parsed=new Release(tag,body,page,apk,digest);
                Prefs.get(c).edit().putString("github_release",parsed.toJson().toString())
                        .putLong("github_checked",System.currentTimeMillis()).apply();
                result="Проверка завершена.";
            } catch(Exception failure) {result="Не удалось проверить выпуск. Проверьте интернет и наличие опубликованных Releases.";}
            final String message=result;
            if(callback!=null)MAIN.post(()->callback.finished(message));
        });
    }

    public static void refreshNews(Context context, boolean force, Callback callback){
        Context c=context.getApplicationContext(); String slug=repository(c);
        if(!validRepo(slug))return;
        long last=Prefs.get(c).getLong("news_checked",0),now=System.currentTimeMillis();
        if(!force && last>0 && now-last<INTERVAL && now>=last)return;
        Prefs.get(c).edit().putLong("news_checked", now).apply();
        NETWORK.execute(()->{
            String result;
            try {
                JSONObject data=readObject("https://raw.githubusercontent.com/"+slug+"/main/content/news.json",128*1024);
                List<News> items=parseNews(data);
                if(items.isEmpty())throw new IllegalArgumentException("Empty news");
                Prefs.get(c).edit().putString("news_cache",data.toString())
                        .putLong("news_checked",System.currentTimeMillis()).apply();
                result="Новости обновлены.";
            }catch(Exception e){result="Не удалось загрузить новости. Доступна сохранённая версия.";}
            String message=result;
            if(callback!=null)MAIN.post(()->callback.finished(message));
        });
    }
    public static List<News> news(Context c){
        try {
            String cached=Prefs.get(c).getString("news_cache","");
            if(!cached.isEmpty())return parseNews(new JSONObject(cached));
            try(InputStream in=c.getAssets().open("news.json")){
                byte[] bytes=readLimited(in,128*1024);
                return parseNews(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
            }
        }catch(Exception ignored){return new ArrayList<>();}
    }
    private static List<News> parseNews(JSONObject o){
        JSONArray arr=o.optJSONArray("items");
        if(arr==null)throw new IllegalArgumentException("Missing items");
        List<News> list=new ArrayList<>();
        for(int i=0;i<Math.min(30,arr.length());i++){
            JSONObject item=arr.optJSONObject(i);
            if(item==null)continue;
            String date=limit(item.optString("date"),20),title=limit(item.optString("title"),140),text=limit(item.optString("text"),1000);
            if(!title.trim().isEmpty()&&!text.trim().isEmpty())list.add(new News(date,title,text));
        }
        return list;
    }
    private static String limit(String s,int max){return s==null?"":s.substring(0,Math.min(s.length(),max)).trim();}
    private static JSONObject readObject(String address,int max) throws Exception {
        HttpURLConnection con=(HttpURLConnection)new URL(address).openConnection();
        con.setConnectTimeout(8000);con.setReadTimeout(10000);con.setInstanceFollowRedirects(false);
        con.setRequestProperty("Accept","application/vnd.github+json");
        con.setRequestProperty("User-Agent","Nasedka-Android");
        try {
            if(con.getResponseCode()!=200)throw new IllegalArgumentException("HTTP "+con.getResponseCode());
            try(InputStream input=con.getInputStream()) {
                return new JSONObject(new String(readLimited(input,max),StandardCharsets.UTF_8));
            }
        }finally{con.disconnect();}
    }
    private static byte[] readLimited(InputStream input,int max)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int read;
        while((read=input.read(buf))!=-1){
            if(bytes.size()+read>max)throw new IllegalArgumentException("Payload too large");
            bytes.write(buf,0,read);
        }
        return bytes.toByteArray();
    }
}
