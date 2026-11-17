package org.nasedka.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Read-only farm summary based only on saved incubation data. */
public final class FarmOverview {
    private FarmOverview() {}

    public static int activeCount(List<Batch> batches) {
        int n=0;
        if (batches!=null) for (Batch b:batches) if (b!=null && b.active) n++;
        return n;
    }

    public static int eggsInActiveIncubations(List<Batch> batches) {
        int n=0;
        if (batches!=null) for (Batch b:batches) if (b!=null && b.active) n+=Math.max(0,b.eggs);
        return n;
    }

    public static List<Reminder> nextActions(List<Reminder> actions, Set<String> done, long now, int limit) {
        ArrayList<Reminder> next = new ArrayList<>();
        if (actions==null || limit<1) return next;
        long from=now-24L*3600000L, to=now+48L*3600000L;
        for (Reminder r:actions) if (r!=null && r.at>=from && r.at<=to
                && (done==null || !done.contains(r.key))) next.add(r);
        next.sort(Comparator.comparingLong((Reminder r)->r.at).thenComparing(r->r.key));
        if (next.size()>limit) return new ArrayList<>(next.subList(0,limit));
        return next;
    }
}
