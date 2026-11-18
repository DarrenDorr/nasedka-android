package org.nasedka.app;

import android.app.Application;

/** Verify interrupted backup restore before Activity/receiver can open SQLite. */
public final class NasedkaApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        BackupStore.recover(this);
    }
}
