package dev.fanchao.mymeet.call

import kotlinx.serialization.Serializable

@Serializable
data class CallSettings(
    val serverUrl: String,
    val room: String,
    val userId: String,
    val userName: String,
)