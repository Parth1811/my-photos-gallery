package com.simplemobiletools.gallery.pro.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.arch.core.util.Function
import com.android.volley.AuthFailureError
import com.android.volley.RequestQueue
import com.android.volley.VolleyError
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.gson.JsonArray
import com.simplemobiletools.gallery.pro.extensions.config
import org.json.JSONArray
import org.json.JSONObject

interface VolleyResponseListener<T> {
    fun onError(message: String?)
    fun onResponse(response: T)
}

class MyCloudPhotoService constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MyCloudPhotoService? = null
        fun getInstance(context: Context) =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MyCloudPhotoService(context).also {
                    INSTANCE = it
                }.apply {
                    TOKEN = context.config.myCloudToken
                }
            }

    }
    private val URL = "http://127.0.0.1:8000"
    private var TOKEN = ""
    private val username = "parth"
    private val password = "s+r0ngPa554@photos"
    private val requestQueue: RequestQueue by lazy {
        Volley.newRequestQueue(context.applicationContext)
    }

    fun handleRequestError(listener: VolleyResponseListener<JSONArray>, error: VolleyError){
        Log.d("MyCloudService", error.toString())
        listener.onError(error.message)
    }

    fun getAllPhotos(listener: VolleyResponseListener<JSONArray>){
        val jsonObjectRequest = object:  JsonArrayRequest(
            Method.GET, "$URL/files", null,
            {listener.onResponse(it)},
            {handleRequestError(listener, it)}
        ) {
            override fun getHeaders(): Map<String, String> {
                val headers = HashMap<String, String>()
                headers["Content-Type"] = "application/json"
                headers["Authorization"] = "Token $TOKEN"
                return headers
            }
        }
        requestQueue.add(jsonObjectRequest)
    }


     fun getAuthToken(context: Context){
        if (TOKEN.isBlank()) {
            val reqObj = JSONObject()
            reqObj.put("username", username)
            reqObj.put("password", password)
            val jsonObjectRequest = object : JsonObjectRequest(
                Method.POST, "$URL/auth/", reqObj,
                {
                    TOKEN = it.get("token").toString()
                    context.config.myCloudToken = TOKEN
                },
                {
                    Log.e("MyCloudService", it.message ?: it.toString())
                }
            ) {
                override fun getHeaders(): Map<String, String> {
                    val headers = HashMap<String, String>()
                    headers["Content-Type"] = "application/json"
                    headers["Authorization"] = "Token $TOKEN"
                    return headers
                }
            }
            requestQueue.add(jsonObjectRequest)
        }
    }

}
