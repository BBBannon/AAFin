package app.aafin.data.jellyfin

import app.aafin.data.Ticks
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType

/*
 * App-level models. UI stages (car and phone) use only these - never Jellyfin SDK DTOs.
 * All ids are Jellyfin GUIDs as strings; all times are milliseconds.
 */

enum class LibraryType { MOVIES, TV_SHOWS, BOX_SETS, MIXED }

/** A top-level library ("view") the user can browse. */
data class Library(
    val id: String,
    val name: String,
    val type: LibraryType,
    val primaryImageTag: String?,
)

enum class ItemKind {
    MOVIE, SERIES, SEASON, EPISODE, BOX_SET, FOLDER, VIDEO, OTHER;

    /** True for things that can be handed to the player directly. */
    val isPlayable: Boolean get() = this == MOVIE || this == EPISODE || this == VIDEO
}

/**
 * A film, series, season, episode, collection or folder.
 * Serialisable so downloads can keep a copy for offline browsing.
 */
@Serializable
data class MediaEntry(
    val id: String,
    val name: String,
    val kind: ItemKind,
    val overview: String? = null,
    val productionYear: Int? = null,
    val runtimeMs: Long? = null,
    /** Where the user got to, 0 if unwatched or finished. */
    val resumePositionMs: Long = 0,
    val played: Boolean = false,
    /** 0-100, for progress bars. Null when not started. */
    val playedPercentage: Double? = null,
    /** For series/seasons: episodes still to watch. */
    val unplayedCount: Int? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    /** Episode number (or season number when [kind] is SEASON). */
    val indexNumber: Int? = null,
    /** Season number of an episode. */
    val parentIndexNumber: Int? = null,
    val primaryImageTag: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val backdropImageTag: String? = null,
    val canDownload: Boolean = false,
) {
    /** "S2:E5" for episodes with numbering, otherwise null. */
    val episodeLabel: String?
        get() = if (kind == ItemKind.EPISODE && indexNumber != null) {
            if (parentIndexNumber != null) "S$parentIndexNumber:E$indexNumber" else "E$indexNumber"
        } else {
            null
        }

    /** One-line title for lists and the player overlay: "Series · S2:E5 · Episode name" or the item name. */
    val displayTitle: String
        get() = if (kind == ItemKind.EPISODE && seriesName != null) {
            listOfNotNull(seriesName, episodeLabel, name).joinToString(" · ")
        } else {
            name
        }
}

/** One page of results from a paged query. */
data class Page<T>(
    val items: List<T>,
    val startIndex: Int,
    val totalCount: Int,
) {
    val nextStartIndex: Int get() = startIndex + items.size
    val hasMore: Boolean get() = nextStartIndex < totalCount
}

enum class ImageKind(internal val apiName: String) {
    PRIMARY("Primary"), BACKDROP("Backdrop"), THUMB("Thumb")
}

internal fun CollectionType?.toLibraryType(): LibraryType? = when (this) {
    CollectionType.MOVIES -> LibraryType.MOVIES
    CollectionType.TVSHOWS -> LibraryType.TV_SHOWS
    CollectionType.BOXSETS -> LibraryType.BOX_SETS
    // "Mixed content" libraries report no collection type at all.
    null, CollectionType.UNKNOWN -> LibraryType.MIXED
    else -> null
}

internal fun BaseItemKind.toItemKind(): ItemKind = when (this) {
    BaseItemKind.MOVIE -> ItemKind.MOVIE
    BaseItemKind.SERIES -> ItemKind.SERIES
    BaseItemKind.SEASON -> ItemKind.SEASON
    BaseItemKind.EPISODE -> ItemKind.EPISODE
    BaseItemKind.BOX_SET -> ItemKind.BOX_SET
    BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> ItemKind.FOLDER
    BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> ItemKind.VIDEO
    else -> ItemKind.OTHER
}

/** Maps a Jellyfin item to the app model. Returns null for libraries AAFin does not show. */
internal fun BaseItemDto.toLibrary(): Library? {
    val type = collectionType.toLibraryType() ?: return null
    return Library(
        id = id.toString(),
        name = name ?: "Library",
        type = type,
        primaryImageTag = imageTags?.get(ImageType.PRIMARY),
    )
}

internal fun BaseItemDto.toMediaEntry(): MediaEntry = MediaEntry(
    id = id.toString(),
    name = name ?: "",
    kind = type.toItemKind(),
    overview = overview,
    productionYear = productionYear,
    runtimeMs = runTimeTicks?.let(Ticks::toMs),
    resumePositionMs = userData?.playbackPositionTicks?.let(Ticks::toMs) ?: 0,
    played = userData?.played ?: false,
    playedPercentage = userData?.playedPercentage,
    unplayedCount = userData?.unplayedItemCount,
    seriesId = seriesId?.toString(),
    seriesName = seriesName,
    seasonId = seasonId?.toString(),
    seasonName = seasonName,
    indexNumber = indexNumber,
    parentIndexNumber = parentIndexNumber,
    primaryImageTag = imageTags?.get(ImageType.PRIMARY),
    seriesPrimaryImageTag = seriesPrimaryImageTag,
    backdropImageTag = backdropImageTags?.firstOrNull(),
    canDownload = canDownload ?: false,
)
