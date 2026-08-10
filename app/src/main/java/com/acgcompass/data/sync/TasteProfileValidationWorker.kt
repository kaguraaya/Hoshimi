package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.domain.taste.TasteAnalysisController
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Periodically validates the personal-input fingerprint without implicit networking. */
@HiltWorker
class TasteProfileValidationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val controller: TasteAnalysisController,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        controller.ensureCurrentProfile()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_WORK_NAME = "taste_profile_validation"
    }
}
