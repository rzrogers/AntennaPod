package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.storage.database.DelayedDeletion;

public class DelayedDeletionWorker extends Worker {
    private static final String TAG = "DelayedDeletionWorker";
    private static final String WORK_ID_DELAYED_DELETION = "PlusDelayedDeletionWorker";

    public DelayedDeletionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context) {
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                DelayedDeletionWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_ID_DELAYED_DELETION,
                ExistingPeriodicWorkPolicy.KEEP, workRequest);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            DelayedDeletion.runDue(getApplicationContext());
        } catch (RuntimeException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        return Result.success();
    }
}
