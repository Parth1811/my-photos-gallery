
// Retrofit interface
package com.ayush.retrofitexample

import android.content.Context
import android.util.Log
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.helpers.CLOUD_BASE_URL
import com.simplemobiletools.gallery.pro.helpers.LOCAL_BASE_URL
import com.simplemobiletools.gallery.pro.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface MyCloudPhotoAPI {

    @GET("/files/")
    suspend fun getAllPhotos(@Header("Authorization") token: String): Response<List<UserFiles>>

    @GET("/file-count/")
    suspend fun getAllPhotosCount(@Header("Authorization") token: String): Response<UserFileCount>

    @POST("/auth/")
//    @FormUrlEncoded
    fun getAuthToken(@Body request: LoginRequest): Call<LoginResponse>

    @Multipart
    @POST("/files/")
    suspend fun uploadPhoto(
        @Header("Authorization") token: String,
        @Part("size") size: Long,
        @Part("type") type: RequestBody,
        @Part("path_on_device") path: RequestBody,
        @Part("video_duration") videoDuration: Int,
        @Part("last_modified") lastModified: RequestBody,
        @Part("date_taken") dateTaken: RequestBody,
        @Part("is_favorite") isFavorite: Boolean,
        @Part file: MultipartBody.Part
    ): Response<UploadFileStatus>

}

class RetrofitHelper(context: Context) {

    var TOKEN = ""
    val TAG = "RetrofitHelper"
    private val username = "parth"
    private val password = "s+r0ngPa554@photos"

    private lateinit var retrofit: Retrofit
    lateinit var api: MyCloudPhotoAPI

    companion object {
        @Volatile private var INSTANCE: RetrofitHelper? = null

        fun getInstance(context: Context, useLocalServer: Boolean = true): RetrofitHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RetrofitHelper(context).also {
                    if(!useLocalServer){
                        it.retrofit = Retrofit.Builder().baseUrl(CLOUD_BASE_URL)
                                                        .addConverterFactory(GsonConverterFactory.create())
                                                        .build()
                    } else {
                        it.retrofit = Retrofit.Builder().baseUrl(LOCAL_BASE_URL)
                                                        .addConverterFactory(GsonConverterFactory.create())
                                                        .build()
                    }
                    it.api = it.retrofit.create(MyCloudPhotoAPI::class.java)
                    it.TOKEN = "Token ${context.config.myCloudToken}"
                    it.ensureAuthToken(context)
                }
            }

    }

    fun ensureAuthToken(context: Context){
        if(context.config.myCloudToken.isBlank()){
            val response = api.getAuthToken(
                LoginRequest(
                    username = username,
                    password = password
            )).execute()

            if(response.isSuccessful){
                val token = response.body()?.token
                if(token?.isNotBlank() == true){
                    TOKEN = "Token $token"
                    context.config.myCloudToken = token
                }
            } else{
                Log.e(TAG, response.errorBody().toString())
            }
        }
    }

}

