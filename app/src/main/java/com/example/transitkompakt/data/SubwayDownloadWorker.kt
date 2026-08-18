package com.example.transitkompakt.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters

/**
 * The one background download this app does: the subway feed, on first run,
 * so it survives the user navigating away mid-download (a plain
 * ViewModel-scoped coroutine would die with the Activity). Runs entirely
 * through [TransitRepository] — a fresh instance here, since a Worker can't
 * share the one the ViewModel holds — so the persisted result is identical
 * to what the normal in-app fetch path would have written; the ViewModel's
 * own repository instance just needs telling to re-read it (see
 * [TransitRepository.refresh]) once this succeeds.
 */
class SubwayDownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = TransitRepository(applicationContext)
        return try {
            val stubs = repo.loadRouteList(
                GtfsSources.SUBWAY, Mode.TRAIN, null,
                onBytes = { read, total ->
                    val fraction = if (total != null && total > 0) {
                        (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    setProgressAsync(Data.Builder().putFloat(KEY_FRACTION, fraction).build())
                }
            ).getOrThrow()
            repo.loadAllRouteDetail(GtfsSources.SUBWAY, stubs, Mode.TRAIN, null).getOrThrow()
            Result.success()
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString(KEY_ERROR, e.message ?: "Download failed").build())
        }
    }

    companion object {
        const val UNIQUE_NAME = "subway_gtfs_download"
        const val KEY_FRACTION = "fraction"
        const val KEY_ERROR = "error"
    }
}
