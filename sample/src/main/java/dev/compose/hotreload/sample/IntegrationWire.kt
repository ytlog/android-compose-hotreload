package dev.compose.hotreload.sample

import kotlinx.serialization.Serializable
import retrofit2.Call
import retrofit2.http.GET

interface IntegrationApi {
    @GET("v1") fun read(): Call<String>
}

class IntegrationDto {
    var value: String = "initial"
    // DTO_FIELDS
    fun display(): String = value
}

@Serializable
data class IntegrationGenerated(val value: String = "generated-v1")
