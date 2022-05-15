package com.simplemobiletools.gallery.pro.models

import com.google.gson.annotations.SerializedName

data class UserFiles(
    val id: Int,
    @SerializedName("path_on_device") val pathOnDevice: String,
    val name: String,
    @SerializedName("stored_file") val storedFile: Int,
    val size: Long,
    val type: FileTypes,
    @SerializedName("video_duration") val videoDuration: Int,
    @SerializedName("last_modified") val lastModified: String,
    @SerializedName("date_taken") val dateTaken: String,
    @SerializedName("is_favorite") val isFavorite: Boolean
)

data class UserFileCount(
    val count: Long
)

enum class FileTypes(val value: Int) {
    TYPE_IMAGES(1),
    TYPE_VIDEOS(2),
    TYPE_GIFS(4),
    TYPE_RAWS(8),
    TYPE_SVGS(16),
    TYPE_PORTRAITS(32),
    TYPE_CLOUD(64),
}
