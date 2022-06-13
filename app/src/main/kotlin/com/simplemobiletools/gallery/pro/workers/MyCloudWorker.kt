package com.simplemobiletools.gallery.pro.workers

import android.content.Context
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat.BigTextStyle
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.NetworkType.CONNECTED
import com.ayush.retrofitexample.RetrofitHelper
import com.simplemobiletools.commons.extensions.getFilenameFromPath
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.gallery.pro.R.drawable
import com.simplemobiletools.gallery.pro.extensions.*
import com.simplemobiletools.gallery.pro.helpers.*
import com.simplemobiletools.gallery.pro.helpers.MediumState.BACKED_UP
import com.simplemobiletools.gallery.pro.helpers.MediumState.ON_CLOUD
import com.simplemobiletools.gallery.pro.models.Favorite
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.UserFileCount
import com.simplemobiletools.gallery.pro.models.UserFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody.Part
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ConnectException
import java.time.Instant
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit.HOURS

@RequiresApi(VERSION_CODES.O)
open class MyCloudWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {

    private val TAG = "MyCloudSyncerWorker"
    private val NOTIFICATION_CHANNEL_ID = "upload"
    private val NOTIFICATION_CHANNEL_NAME = "Uploading Files"
    private val UPLOAD_NOTIFICATION_ID = 1
    private var isCancelled = false

    companion object {
        fun setUpWorker(context: Context){
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequest.Builder(MyCloudWorker::class.java, 3, HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork("MyCloudSyncerWorker", KEEP, workRequest)
        }
    }



    private fun shouldPhotoSync(response: UserFileCount?): Boolean{
        val currentCloudFilesNumber = applicationContext.mediaDB.getNumberOfCloudFiles()
        return currentCloudFilesNumber < response?.count ?: 0
    }

    private fun syncAllPhotos(response: List<UserFiles>?){
        val affectedFolderPaths = HashSet<String>()
        response?.forEach{ userFile ->

            affectedFolderPaths.add(userFile.pathOnDevice.getParentPath())
            val alreadyPresentMedium = applicationContext.mediaDB.getMediumFromPath(userFile.pathOnDevice)
            alreadyPresentMedium?.state = BACKED_UP
            val medium = alreadyPresentMedium ?: Medium(
                null,
                userFile.name,
                "${com.simplemobiletools.gallery.pro.helpers.ON_CLOUD}/thumbnail/file/${userFile.id}/",
                userFile.pathOnDevice.getParentPath(),
                ZonedDateTime.parse(userFile.lastModified).toEpochSecond() * 1000,
                ZonedDateTime.parse(userFile.dateTaken).toEpochSecond() * 1000,
                userFile.size,
                userFile.type.value,
                ON_CLOUD,
                userFile.videoDuration,
                userFile.isFavorite,
                0L,
                0L
            )

            if(userFile.isFavorite)
                applicationContext.favoritesDB.insert(Favorite(null, medium.path, medium.name, medium.parentPath))
            applicationContext.mediaDB.insert(medium)
        }

        affectedFolderPaths.forEach{
            val folderPath = File(it)
            if(folderPath.exists().not()) folderPath.mkdirs()
            applicationContext.updateDirectoryPath(it)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting Syncing Worker ${Calendar.getInstance().time}")

            try{
                val myCloudPhotoAPI = RetrofitHelper.getInstance(applicationContext, applicationContext.config.useLocalServer)
                if (myCloudPhotoAPI.TOKEN.isBlank()){
                    Result.failure()
                }

                val response = myCloudPhotoAPI.api.getAllPhotosCount(myCloudPhotoAPI.TOKEN)
                if(response.isSuccessful){
                    if(shouldPhotoSync(response.body())) {
                        val allPhotoResponse = myCloudPhotoAPI.api.getAllPhotos(myCloudPhotoAPI.TOKEN)
                        if (allPhotoResponse.isSuccessful) {
                            syncAllPhotos(allPhotoResponse.body())
                        } else {
                            Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                            Result.failure()

                        }
                    }
                    Result.success()
                } else{
                    Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                    Result.failure()
                }

                // Upload not backedUp files
                val notBackedUpFiles = applicationContext.mediaDB.getNotBackedUpPath().filter{ it.path.startsWith(RECYCLE_BIN).not() && it.path.isCloudPath().not() }
                val totalFiles = notBackedUpFiles.size
                val notifyManager = NotificationManagerCompat.from(applicationContext)
                val notificationBuilder = Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(drawable.ic_baseline_cloud_upload)
                var sum = 0

                notBackedUpFiles.forEachIndexed { index, it ->
                    Log.d(TAG, "Uploading FIle $it")
                    val type = when(it.type){
                        TYPE_IMAGES -> "TYPE_IMAGES"
                        TYPE_VIDEOS -> "TYPE_VIDEOS"
                        TYPE_GIFS -> "TYPE_GIFS"
                        TYPE_RAWS -> "TYPE_RAWS"
                        TYPE_SVGS -> "TYPE_SVGS"
                        TYPE_PORTRAITS -> "TYPE_PORTRAITS"
                        TYPE_CLOUD -> "TYPE_CLOUD"
                        else -> "TYPE_IMAGES"
                    }

                    notificationBuilder.setContentTitle("Uploading Media.... ${(index + 1) * 100 / totalFiles}%")
                            .setProgress(totalFiles, index + 1, false)
                            .setOngoing(true)
                            .setStyle(BigTextStyle()
                                .bigText("Uploading ${it.path.getFilenameFromPath()} (${index + 1}/$totalFiles)"))
                    notifyManager.notify(UPLOAD_NOTIFICATION_ID, notificationBuilder.build())

                    val file = File(it.path)
                    if (file.exists()) {
                        val uploadResponse = myCloudPhotoAPI.api.uploadPhoto(
                                myCloudPhotoAPI.TOKEN,
                                it.size,
                                type.toRequestBody("text/plain".toMediaTypeOrNull()),
                                it.path.toRequestBody("text/plain".toMediaTypeOrNull()),
                                it.videoDuration,
                                Instant.ofEpochMilli(it.modified).toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                                Instant.ofEpochMilli(it.taken).toString().toRequestBody("text/plain".toMediaTypeOrNull()),
                                it.isFavorite,
                                Part.createFormData(
                                    "file",
                                    file.name,
                                    file.asRequestBody("multipart/form-data".toMediaTypeOrNull())
                                )
                        )

                        if (uploadResponse.isSuccessful) {
                            it.state = BACKED_UP
                            applicationContext.mediaDB.insert(it)
                            sum++
                        } else {
                            Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                        }
                    }
                }

                Log.d(TAG, "Uploading Complete")
                if(sum == 0){
                    notifyManager.cancel(UPLOAD_NOTIFICATION_ID)
                } else {
                    notificationBuilder.setContentTitle("Upload Complete")
                            .setContentTitle("Uploaded $sum files")
                            .setOngoing(false)
                            .setProgress(0, 0, false)
                    notifyManager.notify(UPLOAD_NOTIFICATION_ID, notificationBuilder.build())
                }
            }
            catch (exception: Exception){
                Log.e(TAG, "Caught $exception, ${exception.stackTrace}")
                if(exception is ConnectException){
                    applicationContext.config.useLocalServer = false
                }

                val notifyManager = NotificationManagerCompat.from(applicationContext)
                val notificationBuilder = Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(drawable.ic_baseline_cloud_upload)
                notificationBuilder.setContentTitle("Upload Error")
                        .setOngoing(false)
                        .setContentText("$exception, ${exception.stackTrace}")
                        .setProgress(0, 0, false)
                notifyManager.notify(UPLOAD_NOTIFICATION_ID, notificationBuilder.build())
                Result.failure()
            }

        Result.success()
    }


}
