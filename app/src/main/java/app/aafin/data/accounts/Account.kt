package app.aafin.data.accounts

import kotlinx.serialization.Serializable

/**
 * One signed-in Jellyfin user on one server.
 *
 * @property id stable key: `serverId:userId`. Signing in again as the same user replaces the entry.
 * @property serverUrl normalised base URL without a trailing slash (may include a base path).
 */
@Serializable
data class Account(
    val id: String,
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
) {
    /** The access token is a credential: keep it out of logs and crash reports. */
    override fun toString(): String = "Account(id=$id, server=$serverName, user=$userName)"

    companion object {
        fun idFor(serverId: String, userId: String): String = "$serverId:$userId"
    }
}

/** Everything [AccountStore] persists. */
@Serializable
data class AccountsState(
    /** Random per-install identifier sent to Jellyfin as the DeviceId. Blank until first use. */
    val deviceId: String = "",
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String? = null,
) {
    val activeAccount: Account?
        get() = accounts.firstOrNull { it.id == activeAccountId }
}
