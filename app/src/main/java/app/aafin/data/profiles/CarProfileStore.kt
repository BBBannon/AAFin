package app.aafin.data.profiles

import androidx.datastore.core.DataStore
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.ScreenSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Settings for one car.
 *
 * @property key how the car was recognised: its make/model/year when the car reports them,
 *   otherwise its screen size (see [CarProfileStore.keyForModel] / [CarProfileStore.keyForScreen]).
 * @property name shown to the user; they can rename it on the phone.
 */
@Serializable
data class CarProfile(
    val key: String,
    val name: String,
    val quality: QualityTier = QualityTier.DEFAULT,
    /** When this car last connected (epoch milliseconds); null if not recorded yet. */
    val lastUsedAtMs: Long? = null,
    /** The size of the drawing surface this car last gave AAFin, in pixels; null until one has been seen. */
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
) {
    /** The car's screen, if it has been seen: transcodes are kept no larger than it can show. */
    val screen: ScreenSize?
        get() = if (screenWidthPx != null && screenHeightPx != null) ScreenSize(screenWidthPx, screenHeightPx) else null
}

/** Everything [CarProfileStore] persists. */
@Serializable
data class CarProfilesState(
    val profiles: List<CarProfile> = emptyList(),
    /** Quality given to newly detected cars (the "default quality" setting). */
    val defaultQuality: QualityTier = QualityTier.DEFAULT,
    /** False in files written before Auto existed; see [upToDate]. */
    val autoDefaultApplied: Boolean = false,
) {
    /**
     * One-off step for files from before Auto existed: the default for new cars moves from the old
     * built-in default (720p) to Auto. A default the user set to anything else, and every existing
     * car's own quality, are left exactly as they were.
     */
    fun upToDate(): CarProfilesState = if (autoDefaultApplied) {
        this
    } else {
        copy(autoDefaultApplied = true, defaultQuality = if (defaultQuality == QualityTier.P720_4M) QualityTier.AUTO else defaultQuality)
    }
}

/** Per-car profiles, created automatically the first time each car is seen. */
class CarProfileStore(private val store: DataStore<CarProfilesState>) {

    private val state: Flow<CarProfilesState> = store.data.map { it.upToDate() }

    val profiles: Flow<List<CarProfile>> = state.map { it.profiles }.distinctUntilChanged()

    val defaultQuality: Flow<QualityTier> = state.map { it.defaultQuality }.distinctUntilChanged()

    /** Emits the profile for [key] whenever it changes; null while no such profile exists. */
    fun profile(key: String): Flow<CarProfile?> =
        state.map { state -> state.profiles.firstOrNull { it.key == key } }.distinctUntilChanged()

    /**
     * Returns the profile for [key], creating it with the default quality and [suggestedName]
     * if this car has not been seen before. Safe to call concurrently.
     */
    suspend fun getOrCreate(key: String, suggestedName: String): CarProfile =
        edit { state ->
            if (state.profiles.any { it.key == key }) {
                state
            } else {
                state.copy(profiles = state.profiles + CarProfile(key, suggestedName.ifBlank { "My car" }, state.defaultQuality))
            }
        }.profiles.first { it.key == key }

    suspend fun get(key: String): CarProfile? = state.first().profiles.firstOrNull { it.key == key }

    suspend fun rename(key: String, name: String) = update(key) { it.copy(name = name.trim().ifBlank { it.name }) }

    suspend fun setQuality(key: String, quality: QualityTier) = update(key) { it.copy(quality = quality) }

    /** Records that the car with [key] has just connected ("last used" on the phone). */
    suspend fun markUsed(key: String, nowMs: Long = System.currentTimeMillis()) = update(key) { it.copy(lastUsedAtMs = nowMs) }

    /** Remembers the size of the surface the car with [key] gave us, for the picture-size ceiling. */
    suspend fun setScreen(key: String, widthPx: Int, heightPx: Int) =
        update(key) { it.copy(screenWidthPx = widthPx, screenHeightPx = heightPx) }

    suspend fun remove(key: String) {
        edit { state -> state.copy(profiles = state.profiles.filterNot { it.key == key }) }
    }

    suspend fun setDefaultQuality(quality: QualityTier) {
        edit { it.copy(defaultQuality = quality) }
    }

    private suspend fun update(key: String, change: (CarProfile) -> CarProfile) {
        edit { state ->
            state.copy(profiles = state.profiles.map { if (it.key == key) change(it) else it })
        }
    }

    private suspend fun edit(change: (CarProfilesState) -> CarProfilesState): CarProfilesState =
        store.updateData { change(it.upToDate()) }

    companion object {
        /** Key for a car that reports its identity through `CarInfo.fetchModel`. */
        fun keyForModel(manufacturer: String?, model: String?, year: Int?): String? {
            val parts = listOfNotNull(manufacturer?.trim(), model?.trim(), year?.toString()).filter { it.isNotEmpty() }
            return if (manufacturer.isNullOrBlank() && model.isNullOrBlank()) null else "model:" + parts.joinToString("|").lowercase()
        }

        /** Fallback key for cars that do not: the screen's size and density identify the head unit. */
        fun keyForScreen(widthPx: Int, heightPx: Int, dpi: Int): String = "screen:${widthPx}x$heightPx@$dpi"
    }
}
