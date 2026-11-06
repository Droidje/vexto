package com.vexto.reflect;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** OS-managed local work; no network constraint, foreground service, or backend dependency. */
public final class ReflectEmotionJobService extends JobService {
    private JobParameters current;

    @Override public boolean onStartJob(JobParameters parameters) {
        current = parameters;
        ReflectEmotionCoordinator.get(this).start(() -> {
            if (current == parameters) {
                current = null;
                jobFinished(parameters, false);
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (current == parameters) {
            current = null;
            ReflectEmotionCoordinator.get(this).stop();
        }
        return true;
    }
}
