package app.aafin.data.accounts

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Persistent list of Jellyfin accounts, the active one, and the per-install device id.
 *
 * All flows emit the current value on collection and again on every change.
 */
class AccountStore(private val store: DataStore<AccountsState>) {

    val state: Flow<AccountsState> = store.data

    val accounts: Flow<List<Account>> = store.data.map { it.accounts }.distinctUntilChanged()

    /** The account the app is currently browsing/playing as, or null when signed out. */
    val activeAccount: Flow<Account?> = store.data.map { it.activeAccount }.distinctUntilChanged()

    suspend fun active(): Account? = store.data.first().activeAccount

    suspend fun get(id: String): Account? = store.data.first().accounts.firstOrNull { it.id == id }

    suspend fun all(): List<Account> = store.data.first().accounts

    /** Returns the stable device id, generating and saving it on first use. */
    suspend fun deviceId(): String = store.updateData { current ->
        if (current.deviceId.isNotBlank()) current else current.copy(deviceId = UUID.randomUUID().toString())
    }.deviceId

    /** Adds [account], or replaces the existing entry with the same id. */
    suspend fun upsert(account: Account, makeActive: Boolean = true) {
        store.updateData { current ->
            current.copy(
                accounts = current.accounts.filterNot { it.id == account.id } + account,
                activeAccountId = if (makeActive || current.activeAccountId == null) account.id else current.activeAccountId,
            )
        }
    }

    /** Makes [id] the active account. Throws if no such account is stored. */
    suspend fun setActive(id: String) {
        store.updateData { current ->
            require(current.accounts.any { it.id == id }) { "Unknown account: $id" }
            current.copy(activeAccountId = id)
        }
    }

    /** Removes [id]. If it was active, the first remaining account (if any) becomes active. */
    suspend fun remove(id: String) {
        store.updateData { current ->
            val remaining = current.accounts.filterNot { it.id == id }
            current.copy(
                accounts = remaining,
                activeAccountId = if (current.activeAccountId == id) remaining.firstOrNull()?.id else current.activeAccountId,
            )
        }
    }
}
