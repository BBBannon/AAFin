package app.aafin.testing

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import app.aafin.data.JsonSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * A file-backed JSON DataStore for JVM tests, using the app's own [JsonSerializer].
 *
 * It differs from production (`jsonDataStore`) only in the storage layer: DataStore's default
 * `java.io.File` storage replaces the file with `File.renameTo`, which cannot overwrite an
 * existing file on Windows, so tests on a Windows PC use the Okio storage instead.
 */
fun <T> testJsonDataStore(
    file: File,
    serializer: KSerializer<T>,
    default: T,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
): DataStore<T> {
    val json = JsonSerializer(serializer, default)
    val okioSerializer = object : OkioSerializer<T> {
        override val defaultValue: T = default
        override suspend fun readFrom(source: BufferedSource): T = json.readFrom(source.inputStream())
        override suspend fun writeTo(t: T, sink: BufferedSink) = json.writeTo(t, sink.outputStream())
    }
    return DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, okioSerializer) { file.toOkioPath() },
        scope = scope,
    )
}
