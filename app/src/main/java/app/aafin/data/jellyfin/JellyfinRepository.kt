package app.aafin.data.jellyfin

import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID

/**
 * Browsing the active account's Jellyfin library.
 *
 * Every function suspends, runs its network call off the main thread (`jellyfinCall`), returns
 * app-level models and throws [JellyfinException] on failure - including
 * `NOT_SIGNED_IN` when there is no active account.
 */
class JellyfinRepository(
    private val clients: JellyfinClients,
    private val accountStore: AccountStore,
) {

    /** Film, TV, collection and mixed libraries, in the user's order. Music, live TV etc. are left out. */
    suspend fun libraries(): List<Library> = call { api, account ->
        api.userViewsApi.getUserViews(userId = account.userId.toUUID()).content.items.mapNotNull { it.toLibrary() }
    }

    /**
     * Items in [library], sorted by name, [limit] at a time from [startIndex].
     * Film libraries list films, TV libraries list series, collection libraries list collections;
     * mixed libraries list films and series together.
     */
    suspend fun libraryItems(library: Library, startIndex: Int = 0, limit: Int = DEFAULT_PAGE_SIZE): Page<MediaEntry> =
        call { api, account ->
            api.itemsApi.getItems(
                userId = account.userId.toUUID(),
                parentId = library.id.toUUID(),
                recursive = true,
                includeItemTypes = when (library.type) {
                    LibraryType.MOVIES -> listOf(BaseItemKind.MOVIE)
                    LibraryType.TV_SHOWS -> listOf(BaseItemKind.SERIES)
                    LibraryType.BOX_SETS -> listOf(BaseItemKind.BOX_SET)
                    LibraryType.MIXED -> listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                },
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = LIST_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
            ).content.toPage()
        }

    /** Direct children of a collection (box set) or folder, sorted by name. */
    suspend fun children(parentId: String, startIndex: Int = 0, limit: Int = DEFAULT_PAGE_SIZE): Page<MediaEntry> =
        call { api, account ->
            api.itemsApi.getItems(
                userId = account.userId.toUUID(),
                parentId = parentId.toUUID(),
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                startIndex = startIndex,
                limit = limit,
                fields = LIST_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
            ).content.toPage()
        }

    /** "Continue watching": partly-watched films and episodes, most recent first. */
    suspend fun resumeItems(limit: Int = 12): List<MediaEntry> = call { api, account ->
        api.itemsApi.getResumeItems(
            userId = account.userId.toUUID(),
            limit = limit,
            mediaTypes = listOf(MediaType.VIDEO),
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
            fields = LIST_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = IMAGE_TYPES,
        ).content.items.map { it.toMediaEntry() }
    }

    /** "Next up": the next unwatched episode of each series in progress. */
    suspend fun nextUp(limit: Int = 12): List<MediaEntry> = call { api, account ->
        api.tvShowsApi.getNextUp(
            userId = account.userId.toUUID(),
            limit = limit,
            fields = LIST_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = IMAGE_TYPES,
        ).content.items.map { it.toMediaEntry() }
    }

    /** Seasons of a series, in order. */
    suspend fun seasons(seriesId: String): List<MediaEntry> = call { api, account ->
        api.tvShowsApi.getSeasons(
            seriesId = seriesId.toUUID(),
            userId = account.userId.toUUID(),
            fields = LIST_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = IMAGE_TYPES,
        ).content.items.map { it.toMediaEntry() }
    }

    /** Episodes of a series in order; pass [seasonId] to limit to one season. */
    suspend fun episodes(seriesId: String, seasonId: String? = null): List<MediaEntry> = call { api, account ->
        api.tvShowsApi.getEpisodes(
            seriesId = seriesId.toUUID(),
            userId = account.userId.toUUID(),
            seasonId = seasonId?.toUUID(),
            fields = LIST_FIELDS,
            enableUserData = true,
            imageTypeLimit = 1,
            enableImageTypes = IMAGE_TYPES,
        ).content.items.map { it.toMediaEntry() }
    }

    /** Films, series and episodes whose name matches [query]. */
    suspend fun search(query: String, limit: Int = 30): List<MediaEntry> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return call { api, account ->
            api.itemsApi.getItems(
                userId = account.userId.toUUID(),
                searchTerm = term,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                limit = limit,
                fields = LIST_FIELDS,
                enableUserData = true,
                imageTypeLimit = 1,
                enableImageTypes = IMAGE_TYPES,
            ).content.items.map { it.toMediaEntry() }
        }
    }

    /** Full detail for one item, including the latest resume position. */
    suspend fun item(itemId: String): MediaEntry = call { api, account ->
        api.userLibraryApi.getItem(itemId = itemId.toUUID(), userId = account.userId.toUUID()).content.toMediaEntry()
    }

    /** Size and bitrate of the file behind a film or episode, for download size estimates. */
    suspend fun sourceInfo(itemId: String): SourceInfo = call { api, account ->
        val source = api.userLibraryApi.getItem(itemId = itemId.toUUID(), userId = account.userId.toUUID())
            .content.mediaSources?.firstOrNull()
        SourceInfo(sizeBytes = source?.size?.takeIf { it > 0 }, bitrate = source?.bitrate?.takeIf { it > 0 })
    }

    /**
     * URL of an item's artwork for the active account, or null when signed out.
     * Image URLs need no authentication, so they can be given straight to Coil.
     */
    suspend fun imageUrl(itemId: String, kind: ImageKind = ImageKind.PRIMARY, maxWidth: Int, tag: String? = null): String? =
        accountStore.active()?.let { JellyfinUrls.image(it.serverUrl, itemId, kind, maxWidth, tag) }

    /**
     * The best poster for [entry]: its own primary image, or the series poster for an episode
     * that has none. Null if there is no artwork or nobody is signed in.
     */
    suspend fun posterUrl(entry: MediaEntry, maxWidth: Int): String? {
        val account = accountStore.active() ?: return null
        return when {
            entry.primaryImageTag != null ->
                JellyfinUrls.image(account.serverUrl, entry.id, ImageKind.PRIMARY, maxWidth, entry.primaryImageTag)
            entry.seriesId != null && entry.seriesPrimaryImageTag != null ->
                JellyfinUrls.image(account.serverUrl, entry.seriesId, ImageKind.PRIMARY, maxWidth, entry.seriesPrimaryImageTag)
            else -> null
        }
    }

    private suspend fun <T> call(block: suspend (ApiClient, Account) -> T): T {
        val account = accountStore.active() ?: throw JellyfinException(JellyfinException.Kind.NOT_SIGNED_IN)
        val api = clients.forAccount(account)
        return jellyfinCall { block(api, account) }
    }

    private fun BaseItemDtoQueryResult.toPage(): Page<MediaEntry> =
        Page(items = items.map { it.toMediaEntry() }, startIndex = startIndex, totalCount = totalRecordCount)

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        private val LIST_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.CAN_DOWNLOAD)
        private val IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP)
    }
}
