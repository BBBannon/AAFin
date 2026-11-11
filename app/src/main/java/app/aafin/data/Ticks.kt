package app.aafin.data

/** Jellyfin measures time in ticks: 10,000,000 per second, i.e. 10,000 per millisecond. */
object Ticks {
    const val PER_MILLISECOND = 10_000L

    fun toMs(ticks: Long): Long = ticks / PER_MILLISECOND

    fun fromMs(ms: Long): Long = ms * PER_MILLISECOND
}
