package com.simplemobiletools.gallery.pro.jobs

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.ayush.retrofitexample.RetrofitHelper
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.gallery.pro.extensions.mediaDB
import com.simplemobiletools.gallery.pro.extensions.updateDirectoryPath
import com.simplemobiletools.gallery.pro.helpers.MediumState
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.UserFiles
import kotlinx.coroutines.*
import java.time.ZonedDateTime
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
class MyCloudSyncerService : JobService(){
    companion object {
        const val MY_CLOUD_SYNCER_JOB = 20
    }

    private val TAG = "MyCloudSyncerService"
    private var isCancelled = false

    fun scheduleJob(context: Context) {
        val componentName = ComponentName(context, MyCloudSyncerService::class.java)

        val jobInfo = JobInfo.Builder(MY_CLOUD_SYNCER_JOB, componentName)
                            .setRequiresBatteryNotLow(true)
                            .setPeriodic(24 * 60 * 60 * 1000)
                            .setPersisted(true)
                            .build()
        context.getSystemService(JobScheduler::class.java).schedule(jobInfo)
    }

    fun isScheduled(context: Context): Boolean {
        val jobScheduler = context.getSystemService(JobScheduler::class.java)
        val jobs = jobScheduler.allPendingJobs
        return jobs.any { it.id == MY_CLOUD_SYNCER_JOB }
    }

    private fun checkAllPhotoSync(response: List<UserFiles>?){
        val currentCloudFilesNumber = mediaDB.getNumberOfCloudFiles()
        if (currentCloudFilesNumber < response?.size ?: 0){
            val affectedFolderPaths = HashSet<String>()
            response?.forEach{ userFile ->

                affectedFolderPaths.add(userFile.pathOnDevice.getParentPath())
                    mediaDB.insert(Medium(
                    null,
                    userFile.name,
                    "${RetrofitHelper.getInstance(this).BASE_URL}file/${userFile.storedFile}/",
                    userFile.pathOnDevice.getParentPath(),
                    ZonedDateTime.parse(userFile.lastModified).toEpochSecond() * 1000,
                    ZonedDateTime.parse(userFile.dateTaken).toEpochSecond() * 1000,
                    userFile.size,
                    userFile.type.value,
                    MediumState.ON_CLOUD,
                    userFile.videoDuration,
                    userFile.isFavorite,
                    0L,
                    0L
                ))
            }

            affectedFolderPaths.forEach{
                updateDirectoryPath(it)
            }

        } else {
            Log.e(TAG, "Some Problem with syncing")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Starting Syncing Job ${Calendar.getInstance().time}")
        ensureBackgroundThread {

            val handler = CoroutineExceptionHandler { _, exception ->
                Log.e(TAG, "Caught $exception")
                jobFinished(params, true)
            }

            val myCloudPhotoAPI = RetrofitHelper.getInstance(this)
            CoroutineScope(Dispatchers.IO).launch(handler) {
                    val response = myCloudPhotoAPI.api.getAllPhotos(myCloudPhotoAPI.TOKEN)
                    if(response.isSuccessful){
                        checkAllPhotoSync(response.body())
                        jobFinished(params, false)
                    } else{
                        Log.e(TAG, response.errorBody().toString())
                        jobFinished(params, true)
                    }
                }
            }
        return true
    }

    override fun onStopJob(p0: JobParameters?): Boolean {
        Log.d(TAG, "Cancelled the job")
        isCancelled = true
        return true
    }


}
