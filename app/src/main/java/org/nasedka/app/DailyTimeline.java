package org.nasedka.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only daily projection of the existing schedule and locally saved user observations. */
public final class DailyTimeline {
    private DailyTimeline() {}

    public static class Observation {
        public final long at;
        public final Double temperature, humidity;
        public final String note;
        public Observation(long at, Double temperature, Double humidity, String note) {
            this.at=at; this.temperature=temperature; this.humidity=humidity;
            this.note=note==null ? "" : note;
        }
    }

    public static class Day {
        public final int dayNumber;
        public final LocalDate date;
        public final boolean expectedHatch;
        public final List<Reminder> plan=new ArrayList<>();
        public final List<Observation> facts=new ArrayList<>();
        private final Set<String> done;
        Day(int number, LocalDate date, boolean hatch, Set<String> done) {
            this.dayNumber=number; this.date=date; this.expectedHatch=hatch; this.done=done;
        }
        public boolean completed(Reminder reminder) {return done.contains(reminder.key);}
        public void markCompleted(Reminder reminder) {done.add(reminder.key);}
        public int completedCount() {
            int count=0; for(Reminder r:plan) if(completed(r)) count++; return count;
        }
        public int measuredCount() {
            int count=0;
            for(Observation o:facts) if(o.temperature!=null || o.humidity!=null) count++;
            return count;
        }
        public int notesCount() {
            int count=0; for(Observation o:facts) if(!o.note.trim().isEmpty()) count++;
            return count;
        }
        public Double latestTemperature() {
            Double result=null; long stamp=Long.MIN_VALUE;
            for(Observation o:facts) if(o.temperature!=null && o.at>=stamp) {
                stamp=o.at; result=o.temperature;
            }
            return result;
        }
        public Double latestHumidity() {
            Double result=null; long stamp=Long.MIN_VALUE;
            for(Observation o:facts) if(o.humidity!=null && o.at>=stamp) {
                stamp=o.at; result=o.humidity;
            }
            return result;
        }
    }

    public static List<Day> build(Batch batch, ZoneId zone,
            List<Reminder> events, Set<String> finished, List<Observation> recorded) {
        if(batch==null || zone==null || batch.startedAt<=0 || batch.duration<1 || batch.duration>60)
            throw new IllegalArgumentException("Invalid incubation dates");
        Set<String> done=new HashSet<>();
        if(finished!=null) done.addAll(finished);
        LocalDate start=batch.startDate(zone);
        List<Day> days=new ArrayList<>();
        Map<LocalDate,Day> lookup=new HashMap<>();
        for(int i=0;i<=batch.duration;i++) {
            Day d=new Day(i+1,start.plusDays(i),i==batch.duration,done);
            days.add(d); lookup.put(d.date,d);
        }
        if(events!=null) for(Reminder event:events) {
            if(event==null || event.batchId!=batch.id) continue;
            Day d=lookup.get(Instant.ofEpochMilli(event.at).atZone(zone).toLocalDate());
            if(d!=null)d.plan.add(event);
        }
        if(recorded!=null) for(Observation observation:recorded) {
            if(observation==null) continue;
            Day d=lookup.get(Instant.ofEpochMilli(observation.at).atZone(zone).toLocalDate());
            if(d!=null)d.facts.add(observation);
        }
        for(Day d:days) {
            d.plan.sort((a,b)->Long.compare(a.at,b.at));
            d.facts.sort((a,b)->Long.compare(a.at,b.at));
        }
        return days;
    }

    public static String eggs(int number) {
        int n=Math.abs(number),a=n%100,b=n%10;
        String noun=(a>=11 && a<=14)?"яиц":b==1?"яйцо":
                b>=2 && b<=4?"яйца":"яиц";
        return number+" "+noun;
    }
}
