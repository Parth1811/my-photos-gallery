
// Retrofit interface
package com.ayush.retrofitexample

import android.content.Context
import android.util.Log
import com.simplemobiletools.gallery.pro.extensions.config
import com.simplemobiletools.gallery.pro.models.LoginRequest
import com.simplemobiletools.gallery.pro.models.LoginResponse
import com.simplemobiletools.gallery.pro.models.UserFiles
import kotlinx.coroutines.GlobalScope
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

interface MyCloudPhotoAPI {

    @GET("/files")
    suspend fun getAllPhotos(@Header("Authorization") token: String): Response<List<UserFiles>>

    @POST("/auth/")
//    @FormUrlEncoded
    fun getAuthToken(@Body request: LoginRequest): Call<LoginResponse>
}

class RetrofitHelper(context: Context) {

    val BASE_URL = "http://localhost:8000/"
    var TOKEN = ""
    val TAG = "RetrofitHelper"
    private val username = "parth"
    private val password = "s+r0ngPa554@photos"

    private lateinit var retrofit: Retrofit
    lateinit var api: MyCloudPhotoAPI

    companion object {
        @Volatile private var INSTANCE: RetrofitHelper? = null

        fun getInstance(context: Context): RetrofitHelper =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RetrofitHelper(context).also {
                    it.retrofit =  Retrofit.Builder().baseUrl(it.BASE_URL)
                                        .addConverterFactory(GsonConverterFactory.create())
                                        .build()

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

