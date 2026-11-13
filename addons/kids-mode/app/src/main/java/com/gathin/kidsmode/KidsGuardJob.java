package com.gathin.kidsmode;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * A persisted 15-minute job, the third layer after the boot receiver and the launcher: if MIUI
 * killed the add-on while the TV stayed on, the job starts the keeper again and asks it to put
 * the guard back.
 */
public final class KidsGuardJob extends JobService {
    static final int JOB_ID = 4402;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, KidsGuardJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (KidsStore.isEnabled(this)) {
            KidsGuardKeeper.start(this);
            KidsStore.ensureGuard(this);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
