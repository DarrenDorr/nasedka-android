package org.nasedka.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Only observational checks: no artificial incubator temperature, humidity or turning. */
public final class NestCarePlan {
    private NestCarePlan(){}

    public static List<Reminder> generate(FarmDb.Nest nest,ZoneId zone,
            List<LocalTime> checks,Set<LocalDate> recordedDates) {
        ArrayList<Reminder> output=new ArrayList<>();
        if(nest==null||!nest.active||nest.startedAt<=0||nest.days<3||nest.days>60
                ||checks==null)return output;
        LocalDate begin=Instant.ofEpochMilli(nest.startedAt).atZone(zone).toLocalDate();
        LocalDate due=begin.plusDays(nest.days);
        for(int d=0;d<nest.days;d++) {
            LocalDate date=begin.plusDays(d);
            if(recordedDates!=null&&recordedDates.contains(date))continue;
            for(LocalTime time:checks) {
                long at=date.atTime(time).atZone(zone).toInstant().toEpochMilli();
                if(at<nest.startedAt)continue;
                String key="nest:"+nest.id+":"+date+":"+time;
                output.add(new Reminder(key,nest.id,at,"nest_check",
                        "Осмотреть наседку · "+nest.name,
                        "Проверьте фактическое состояние птицы и гнезда, доступ к корму, "
                                +"воде и отсутствие беспокойства. Запишите наблюдение."));
            }
        }
        long at=due.atTime(checks.isEmpty()?LocalTime.of(9,0):checks.get(0))
                .atZone(zone).toInstant().toEpochMilli();
        output.add(new Reminder("nest:"+nest.id+":hatch:"+due,nest.id,at,
                "nest_hatch","Ожидаемый вывод · "+nest.name,
                "Срок ориентировочный. Проверьте состояние наседки и вылупившихся птенцов. "
                        +"Запишите фактический результат."));
        return output;
    }
    public static Set<LocalDate> observedDays(FarmDb.Nest nest,List<FarmDb.Note> notes,ZoneId zone) {
        HashSet<LocalDate> days=new HashSet<>();
        if(notes!=null)for(FarmDb.Note note:notes) {
            if(note!=null&&note.at>=nest.startedAt)
                days.add(Instant.ofEpochMilli(note.at).atZone(zone).toLocalDate());
        }
        return days;
    }
}
