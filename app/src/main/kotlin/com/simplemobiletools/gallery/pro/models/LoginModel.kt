package com.simplemobiletools.gallery.pro.models


data class LoginRequest (
    val username: String,
    val password: String
)

data class LoginResponse (
    val token: String
)
