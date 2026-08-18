package com.example.transitkompakt.data

import android.content.Context
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Enqueues and observes [SubwayDownloadWorker], and answers "how big is
 * this download" up front so the first-run sheet can show a real figure
 * instead of a placeholder.
 */
class GtfsDownloadController(private val context: Context) {

    private val http = OkHttpClient()
    private val workManager get() = WorkManager.getInstance(context)

    /** A HEAD request for Content-Length, formatted as e.g. "42 MB". Null if unavailable. */
    suspend fun probeSizeLabel(url: String): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            http.newCall(Request.Builder().url(url).head().build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                res.header("Content-Length")?.toLongOrNull()
            }
        }.getOrNull() ?: return@withContext null
        val mb = (bytes / 1_000_000.0).let { if (it < 1.0) 1L else Math.round(it) }
        "$mb MB"
    }

    /** Starts (or resumes tracking, if one is already queued/running) the subway download. */
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SubwayDownloadWorker>().build()
        workManager.enqueueUniqueWork(SubwayDownloadWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Empty if nothing has ever been enqueued for this unique work name yet.
     * Bridged off getWorkInfosForUniqueWorkLiveData by hand rather than a
     * work-runtime-ktx Flow extension — plain LiveData query methods are the
     * part of this API that's been stable and unchanged since WorkManager
     * 1.0, which is worth more here than a shorter call I can't verify.
     */
    fun workInfoFlow(): Flow<List<WorkInfo>> = callbackFlow {
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(SubwayDownloadWorker.UNIQUE_NAME)
        val observer = Observer<List<WorkInfo>> { infos -> trySend(infos) }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }
}
