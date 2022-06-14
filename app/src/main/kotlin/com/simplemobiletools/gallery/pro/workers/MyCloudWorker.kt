package com.simplemobiletools.gallery.pro.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build.VERSION_CODES
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.BigTextStyle
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import androidx.work.ExistingPeriodicWorkPolicy.KEEP
import androidx.work.NetworkType.CONNECTED
import com.ayush.retrofitexample.RetrofitHelper
import com.simplemobiletools.commons.extensions.getFilenameFromPath
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.commons.helpers.isOreoPlus
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

    private var isCancelled = false
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private val TAG = "MyCloudSyncerWorker"
        private val NOTIFICATION_CHANNEL_ID = "upload"
        private val NOTIFICATION_CHANNEL_NAME = "Uploading Files"
        private val UPLOAD_NOTIFICATION_ID = 1

        fun createNotificationChannel(context: Context){
            if(isOreoPlus()){
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }

        fun setUpWorker(context: Context){

            createNotificationChannel(context)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequest.Builder(MyCloudWorker::class.java, 24, HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork("MyCloudSyncerWorker", KEEP, workRequest)
        }

        fun doOneTimeWork(context: Context){

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequest.Builder(MyCloudWorker::class.java)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork("MyCloudSyncerWorker", ExistingWorkPolicy.REPLACE, workRequest)
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

        setForeground(createForegroundInfo())

        Log.d(TAG, "Starting Syncing Worker ${Calendar.getInstance().time}")

            try{
                val myCloudPhotoAPI = RetrofitHelper.getInstance(applicationContext, applicationContext.config.useLocalServer)
                if (myCloudPhotoAPI.TOKEN.isBlank()){
                    return@withContext Result.failure()
                }

                val response = myCloudPhotoAPI.api.getAllPhotosCount(myCloudPhotoAPI.TOKEN)
                if(response.isSuccessful){
                    if(shouldPhotoSync(response.body())) {
                        var next: String? = "first"
                        var limit: Int? = null
                        var offset: Long? = applicationContext.config.lastSyncOffset

                        while(next.isNullOrEmpty().not()){
                            val allPhotoResponse = myCloudPhotoAPI.api.getAllPhotos(myCloudPhotoAPI.TOKEN, limit, offset)
                            if (allPhotoResponse.isSuccessful) {
                                val page = allPhotoResponse.body()
                                Log.d(TAG, "Syncing Photo Batch with offset $offset of ${page?.count}")
                                syncAllPhotos(page?.results)
                                Log.d(TAG, "Syncing Complete ${page?.next}")

                                next = page?.next
                                if (next.isNullOrEmpty().not()) {
                                    val uri = Uri.parse(next)
                                    limit = uri.getQueryParameter("limit")?.toInt()
                                    offset = uri.getQueryParameter("offset")?.toLong()
                                    applicationContext.config.lastSyncOffset = offset ?: 0
                                }

                            } else {
                                Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                                return@withContext Result.failure()
                            }
                        }

                    }
                } else{
                    Log.e(TAG, "Error Code: ${response.code()} -- ${response.errorBody().toString()}")
                    return@withContext Result.failure()
                }

                // Upload not backedUp files
                val notBackedUpFiles = applicationContext.mediaDB.getNotBackedUpPath().filter{ it.path.startsWith(RECYCLE_BIN).not() && it.path.isCloudPath().not() }
                val totalFiles = notBackedUpFiles.size
                val notifyManager = NotificationManagerCompat.from(applicationContext)
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

                    val notification = getNotificationBuilder().setContentTitle("Uploading Media.... ${(index + 1) * 100 / totalFiles}%")
                            .setProgress(totalFiles, index + 1, false)
                            .setOngoing(true)
                            .setStyle(BigTextStyle()
                                .bigText("Uploading ${it.path.getFilenameFromPath()} (${index + 1}/$totalFiles)"))
                            .build()
                    notifyManager.notify(UPLOAD_NOTIFICATION_ID, notification)

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
                    val notification = getNotificationBuilder().setContentTitle("Upload Complete")
                            .setContentTitle("Uploaded $sum files")
                            .setOngoing(false)
                            .setProgress(0, 0, false)
                            .build()
                    notifyManager.notify(UPLOAD_NOTIFICATION_ID, notification)
                }
            }
            catch (exception: Exception){
                Log.e(TAG, "Caught $exception, ${exception.stackTrace}")
                if(exception is ConnectException){
                    applicationContext.config.useLocalServer = false
                }

                val notifyManager = NotificationManagerCompat.from(applicationContext)

                val notification = getNotificationBuilder().setContentTitle("Upload Error")
                        .setOngoing(false)
                        .setContentText("$exception, ${exception.stackTrace}")
                        .setProgress(0, 0, false)
                        .build()
                notifyManager.notify(UPLOAD_NOTIFICATION_ID, notification)
                return@withContext Result.failure()
            }

        Result.success()
    }

    private fun getNotificationBuilder(): Builder {
        val cancel = "Cancel Sync"
        // This PendingIntent can be used to cancel the worker
        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(getId())

        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(drawable.ic_baseline_cloud_upload)
            .addAction(android.R.drawable.ic_delete, cancel, intent)
    }

    private fun createForegroundInfo(): ForegroundInfo {

        val notification = getNotificationBuilder()
            .setContentTitle("Syncing Media.....")
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        return ForegroundInfo(UPLOAD_NOTIFICATION_ID, notification)
    }

}
