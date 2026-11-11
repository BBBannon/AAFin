package app.aafin.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Shared JSON configuration for everything AAFin persists itself. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** DataStore serializer that stores a single `@Serializable` value as JSON. */
class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T = try {
        AppJson.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Stored JSON could not be read", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(AppJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/**
 * Creates a JSON-backed DataStore in [file].
 *
 * A corrupt file is replaced with [default] and [onCorruption] is told, so the caller can log it
 * (the alternative is an app that can never start again).
 */
fun <T> jsonDataStore(
    file: File,
    serializer: KSerializer<T>,
    default: T,
    onCorruption: (CorruptionException) -> Unit = {},
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer, default),
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
        onCorruption(error)
        default
    },
    produceFile = { file },
)
