package org.nasedka.app;

public final class Reminder {
    public final String key;
    public final long batchId;
    public final long at;
    public final String type;
    public final String title;
    public final String message;

    public Reminder(String key, long batchId, long at, String type, String title, String message) {
        this.key = key;
        this.batchId = batchId;
        this.at = at;
        this.type = type;
        this.title = title;
        this.message = message;
    }

    public boolean important() {
        return "lockdown".equals(type) || "hatch".equals(type);
    }
}
