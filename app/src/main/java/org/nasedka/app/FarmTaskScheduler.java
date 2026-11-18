package org.nasedka.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalTime;
import java.time.Instant;

/**
 * A separate rolling alarm for farm care tasks. Does not change the existing
 * incubation alarm identity, channels or scheduler state.
 */
public final class FarmTaskScheduler {
    private static final String PREF_NEXT="farm_task_next";
    private static final String PREF_LAST="farm_task_delivered_at";
    private static final String ACTION="org.nasedka.app.FARM_TASK_TICK";
    private FarmTaskScheduler() {}

    private static PendingIntent intent(Context c,long time) {
        Intent i=new Intent(c,Receiver.class);
        i.setAction(ACTION);i.setData(Uri.parse("nasedka://farm/tasks/tick"));
        i.putExtra("at",time);
        return PendingIntent.getBroadcast(c,92,i,PendingIntent.FLAG_UPDATE_CURRENT|
                PendingIntent.FLAG_IMMUTABLE);
    }
    private static List<Reminder> nestReminders(Context c,FarmDb db) {
        java.util.ArrayList<Reminder> output=new java.util.ArrayList<>();
        ZoneId zone=ZoneId.systemDefault();
        List<LocalTime> times=Prefs.times(Prefs.nestChecks(c));
        for(FarmDb.Nest nest:db.nests()) {
            if(!nest.active)continue;
            Set<LocalDate> recorded=NestCarePlan.observedDays(nest,db.nestNotes(nest.id),zone);
            output.addAll(NestCarePlan.generate(nest,zone,times,recorded));
        }
        return output;
    }

    public static synchronized void reschedule(Context c) {
        Context app=c.getApplicationContext();
        AlarmManager am=(AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
        if(am==null)return;
        long now=System.currentTimeMillis();
        long previous=Prefs.get(app).getLong(PREF_NEXT,0);
        if(previous>0&&previous<=now&&previous>Prefs.get(app).getLong(PREF_LAST,0))
            deliver(app,previous);
        am.cancel(intent(app,0));
        if(!Prefs.notifications(app)) {
            Prefs.get(app).edit().remove(PREF_NEXT).apply();return;
        }
        long nearest=Long.MAX_VALUE;
        try(FarmDb db=new FarmDb(app)) {
            for(FarmDb.Task task:db.tasks(true)) {
                if(task.dueAt>now+1000&&task.dueAt<nearest)nearest=task.dueAt;
            }
            for(Reminder nest:nestReminders(app,db)) {
                if(nest.at>now+1000&&nest.at<nearest)nearest=nest.at;
            }
        }
        if(nearest!=Long.MAX_VALUE){
            PendingIntent pi=intent(app,nearest);
            try{
                if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&!am.canScheduleExactAlarms())
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,nearest,pi);
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,nearest,pi);
            }catch(SecurityException denied){
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,nearest,pi);
            }
            Prefs.get(app).edit().putLong(PREF_NEXT,nearest).apply();
        }else Prefs.get(app).edit().remove(PREF_NEXT).apply();
    }
    public static synchronized void deliver(Context c,long expected) {
        long now=System.currentTimeMillis();
        if(expected<=0||expected>now+60000)return;
        long last=Prefs.get(c).getLong(PREF_LAST,0);
        if(expected<=last)return;
        if(Prefs.notifications(c)){
            int shown=0;
            try(FarmDb db=new FarmDb(c)){
                List<FarmDb.Task> tasks=db.tasks(true);
                // Don't flood after power saving; overdue tasks remain in the list.
                for(FarmDb.Task task:tasks) {
                    if(task.dueAt>now)continue;
                    if(task.dueAt<=Math.max(last,now-12L*60L*60L*1000L))continue;
                    send(c,task);
                    if(++shown>=5)break;
                }
                if(shown<5)for(Reminder nest:nestReminders(c,db)) {
                    if(nest.at>now || nest.at<=Math.max(last,now-12L*60L*60L*1000L))
                        continue;
                    sendNest(c,nest);
                    if(++shown>=5)break;
                }
            }
        }
        Prefs.get(c).edit().putLong(PREF_LAST,now).apply();
    }
    private static void send(Context c,FarmDb.Task task) {
        if(Build.VERSION.SDK_INT>=33&&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        !=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null||!nm.areNotificationsEnabled())return;
        Notifications.channels(c);
        Intent open=new Intent(c,MainActivity.class);
        open.putExtra("farm_page",FarmScreens.TASKS);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,(int)(task.id&0x7fffffff),
                open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(c,Notifications.CH_DAILY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Наседка: "+task.title)
                .setContentText(task.notes.isEmpty()?"Запланированная задача по хозяйству":task.notes)
                .setStyle(new Notification.BigTextStyle().bigText(task.notes))
                .setContentIntent(pi).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        nm.notify(taskId(task.id),n);
    }
    private static void sendNest(Context c,Reminder reminder) {
        if(Build.VERSION.SDK_INT>=33&&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        !=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm==null||!nm.areNotificationsEnabled())return;
        Notifications.channels(c);
        Intent open=new Intent(c,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("farm_page",FarmScreens.NESTS);
        PendingIntent pending=PendingIntent.getActivity(c,
                reminder.key.hashCode()&0x7fffffff,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(c,Notifications.CH_DAILY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(reminder.title)
                .setContentText(reminder.message)
                .setStyle(new Notification.BigTextStyle().bigText(reminder.message))
                .setContentIntent(pending).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
        nm.notify(reminder.key.hashCode()&0x7fffffff,notice);
    }
    private static int taskId(long id) {return ("farm-task-"+id).hashCode()&0x7fffffff;}
    public static void complete(Context c,long id) {
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null)nm.cancel(taskId(id));
        reschedule(c);
    }
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c,Intent i) {
            if(i==null||!ACTION.equals(i.getAction()))return;
            deliver(c,i.getLongExtra("at",0));
            reschedule(c);
        }
    }
}
