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
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.extensions.favoritesDB
import com.simplemobiletools.gallery.pro.extensions.mediaDB
import com.simplemobiletools.gallery.pro.extensions.updateDirectoryPath
import com.simplemobiletools.gallery.pro.helpers.MediumState
import com.simplemobiletools.gallery.pro.helpers.ON_CLOUD
import com.simplemobiletools.gallery.pro.models.Favorite
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.UserFileCount
import com.simplemobiletools.gallery.pro.models.UserFiles
import kotlinx.coroutines.*
import java.io.File
import java.net.ConnectException
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
        context.config.useLocalServer = true

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

    private fun shouldPhotoSync(response: UserFileCount?): Boolean{
        val currentCloudFilesNumber = mediaDB.getNumberOfCloudFiles()
        return currentCloudFilesNumber < response?.count ?: 0
    }

    private fun syncAllPhotos(response: List<UserFiles>?){
        val affectedFolderPaths = HashSet<String>()
        response?.forEach{ userFile ->

            affectedFolderPaths.add(userFile.pathOnDevice.getParentPath())
            val medium = Medium(
                null,
                userFile.name,
                "$ON_CLOUD/thumbnail/file/${userFile.id}/",
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
            )

            if(userFile.isFavorite)
                favoritesDB.insert(Favorite(null, medium.path, medium.name, medium.parentPath))
            mediaDB.insert(medium)
        }

        affectedFolderPaths.forEach{
            val folderPath = File(it)
            if(folderPath.exists().not()) folderPath.mkdirs()
            updateDirectoryPath(it)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Starting Syncing Job ${Calendar.getInstance().time}")
        ensureBackgroundThread {

            val handler = CoroutineExceptionHandler { _, exception ->
                Log.e(TAG, "Caught $exception, ${exception.stackTrace}")
                if(exception is ConnectException){
                    applicationContext.config.useLocalServer = false
                }
                jobFinished(params, true)
            }

            CoroutineScope(Dispatchers.IO).launch(handler) {
                val myCloudPhotoAPI = RetrofitHelper.getInstance(applicationContext, applicationContext.config.useLocalServer)
                val response = myCloudPhotoAPI.api.getAllPhotosCount(myCloudPhotoAPI.TOKEN)
                if(response.isSuccessful){
                    if(shouldPhotoSync(response.body())) {
                        val allPhotoResponse = myCloudPhotoAPI.api.getAllPhotos(myCloudPhotoAPI.TOKEN)
                        if (allPhotoResponse.isSuccessful) {
                            syncAllPhotos(allPhotoResponse.body())
                        } else {
                            Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                            jobFinished(params, true)
                        }
                    }
                    jobFinished(params, false)
                } else{
                    Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
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
