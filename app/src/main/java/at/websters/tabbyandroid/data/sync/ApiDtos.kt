package at.websters.tabbyandroid.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiConfig(
    val id: Long,
    val name: String,
    val content: String = "{}",
    @SerialName("last_used_with_version") val lastUsedWithVersion: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val user: Long? = null,
)

@Serializable
data class ApiUser(
    val id: Long,
    val username: String,
    @SerialName("active_config") val activeConfig: Long? = null,
)

/** Body for PATCH /api/1/configs/{id} - same as Tabby desktop's updateConfig. */
@Serializable
data class UpdateConfigBody(
    val content: String,
    @SerialName("last_used_with_version") val lastUsedWithVersion: String,
)
