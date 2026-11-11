package app.aafin.data

import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.data.jellyfin.AuthRepository
import app.aafin.data.jellyfin.ClientIdentity
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinClients
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.JellyfinRepository
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.QuickConnectState
import app.aafin.data.jellyfin.SdkAuthGateway
import app.aafin.data.jellyfin.ServerCompatibility
import app.aafin.data.playback.AuthHeaderInterceptor
import app.aafin.data.playback.CodecCapabilities
import app.aafin.data.playback.PlayMethod
import app.aafin.data.playback.PlaybackRequest
import app.aafin.data.playback.PlaybackResolver
import app.aafin.data.playback.PlaybackSnapshot
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.QueuedPosition
import app.aafin.data.playback.SdkPlaystateGateway
import app.aafin.testing.InMemoryDataStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the real Jellyfin SDK against a local fake server, to check the things unit tests with
 * fakes cannot: request paths, the authorisation header, request bodies, and that the SDK
 * decodes Jellyfin-shaped JSON into the app's models.
 */
class JellyfinSdkIntegrationTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val userId = "5f4dcc3b5aa765d61d8327deb882cf99"
    private val userIdDashed = "5f4dcc3b-5aa7-65d6-1d83-27deb882cf99"
    private val movieId = "0f8fad5bd9cb469fa16570867728950e"
    private val movieIdDashed = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val seriesId = "11111111222233334444555555555555"
    private var quickConnectPolls = 0

    private val accounts = AccountStore(InMemoryDataStore(AccountsState()))
    private val httpClient = OkHttpClient()
    private val clients = JellyfinClients(ClientIdentity("AAFin", "1.0-test", "Pixel Test"), accounts, httpClient)
    private val auth = AuthRepository(SdkAuthGateway(clients), accounts)
    private val repository = JellyfinRepository(clients, accounts)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val userJson =
        """{"Name":"ben","ServerId":"srv1","Id":"$userId","HasPassword":true,"HasConfiguredPassword":true,"HasConfiguredEasyPassword":false}"""
    private val authJson = """{"User":$userJson,"AccessToken":"tok-123","ServerId":"srv1"}"""

    private fun userData(positionTicks: Long, played: Boolean) =
        """{"PlaybackPositionTicks":$positionTicks,"PlayCount":1,"IsFavorite":false,"Played":$played,"Key":"k","ItemId":"$movieId","PlayedPercentage":25.0}"""

    private val movieJson = """{"Name":"Big Buck Bunny","Id":"$movieId","Type":"Movie","MediaType":"Video","RunTimeTicks":72000000000,
        "ProductionYear":2008,"Overview":"A rabbit.","CanDownload":true,"ImageTags":{"Primary":"ptag"},"BackdropImageTags":["btag"],
        "UserData":${userData(18_000_000_000, false)},"SomeFieldFromTheFuture":{"x":1}}"""

    private val episodeJson = """{"Name":"Pilot","Id":"22222222333344445555666666666666","Type":"Episode","MediaType":"Video",
        "SeriesName":"Testing Times","SeriesId":"$seriesId","SeasonId":"33333333444455556666777777777777","IndexNumber":1,"ParentIndexNumber":2,
        "SeriesPrimaryImageTag":"stag","RunTimeTicks":13200000000}"""

    private val playbackInfoJson = """{"MediaSources":[{"Protocol":"File","Id":"$movieId","Type":"Default","Container":"mkv","IsRemote":false,
        "ETag":"etag9","RunTimeTicks":72000000000,"ReadAtNativeFramerate":false,"IgnoreDts":false,"IgnoreIndex":false,"GenPtsInput":false,
        "SupportsTranscoding":true,"SupportsDirectStream":false,"SupportsDirectPlay":false,"IsInfiniteStream":false,"RequiresOpening":false,
        "RequiresClosing":false,"RequiresLooping":false,"SupportsProbing":true,"HasSegments":false,"DefaultAudioStreamIndex":1,
        "TranscodingUrl":"/videos/$movieIdDashed/master.m3u8?MediaSourceId=$movieId&VideoCodec=h264&AudioCodec=aac&ApiKey=tok-123&PlaySessionId=ps1",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"ts",
        "MediaStreams":[
          {"Codec":"hevc","Type":"Video","Index":0,"IsInterlaced":false,"IsDefault":true,"IsForced":false,"IsHearingImpaired":false,
           "IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false,"Width":3840,"Height":2160,"VideoRange":"HDR","VideoRangeType":"SomethingNew"},
          {"Codec":"aac","Type":"Audio","Index":1,"Language":"eng","DisplayTitle":"English - AAC - Stereo","IsInterlaced":false,"IsDefault":true,
           "IsForced":false,"IsHearingImpaired":false,"IsExternal":false,"IsTextSubtitleStream":false,"SupportsExternalStream":false},
          {"Codec":"subrip","Type":"Subtitle","Index":2,"Language":"eng","DisplayTitle":"English - SRT","IsInterlaced":false,"IsDefault":false,
           "IsForced":false,"IsHearingImpaired":false,"IsExternal":false,"IsTextSubtitleStream":true,"SupportsExternalStream":true,
           "DeliveryMethod":"External","DeliveryUrl":"/Videos/$movieIdDashed/$movieId/Subtitles/2/0/Stream.srt?ApiKey=tok-123","IsExternalUrl":false}
        ]}],"PlaySessionId":"ps1"}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/jf/System/Info/Public" ->
                        json("""{"LocalAddress":"http://10.0.0.2:8096","ServerName":"Home","Version":"10.11.5","ProductName":"Jellyfin Server","Id":"srv1","StartupWizardCompleted":true}""")
                    path == "/jf/QuickConnect/Enabled" -> json("true")
                    path == "/jf/QuickConnect/Initiate" -> json(quickConnect(authenticated = false))
                    path == "/jf/QuickConnect/Connect" -> json(quickConnect(authenticated = ++quickConnectPolls >= 2))
                    path == "/jf/Users/AuthenticateWithQuickConnect" -> json(authJson)
                    path == "/jf/Users/AuthenticateByName" ->
                        if (request.body.readUtf8().contains("\"Pw\":\"correct\"")) json(authJson) else MockResponse().setResponseCode(401)
                    path == "/jf/UserViews" -> json(
                        """{"Items":[
                            {"Name":"Films","Id":"aaaaaaaa000000000000000000000001","Type":"CollectionFolder","CollectionType":"movies","ImageTags":{"Primary":"t"}},
                            {"Name":"Shows","Id":"aaaaaaaa000000000000000000000002","Type":"CollectionFolder","CollectionType":"tvshows"},
                            {"Name":"Music","Id":"aaaaaaaa000000000000000000000003","Type":"CollectionFolder","CollectionType":"music"},
                            {"Name":"Mixed","Id":"aaaaaaaa000000000000000000000004","Type":"CollectionFolder"},
                            {"Name":"Live TV","Id":"aaaaaaaa000000000000000000000005","Type":"UserView","CollectionType":"livetv"}
                        ],"TotalRecordCount":5,"StartIndex":0}""",
                    )
                    path == "/jf/Items" -> json("""{"Items":[$movieJson],"TotalRecordCount":120,"StartIndex":50}""")
                    path == "/jf/UserItems/Resume" || path == "/jf/Users/$userIdDashed/Items/Resume" ->
                        json("""{"Items":[$movieJson,$episodeJson],"TotalRecordCount":2,"StartIndex":0}""")
                    path == "/jf/Shows/NextUp" -> json("""{"Items":[$episodeJson],"TotalRecordCount":1,"StartIndex":0}""")
                    path.startsWith("/jf/Shows/") && path.endsWith("/Episodes") ->
                        json("""{"Items":[$episodeJson],"TotalRecordCount":1,"StartIndex":0}""")
                    path == "/jf/Items/$movieIdDashed/PlaybackInfo" -> json(playbackInfoJson)
                    path.startsWith("/jf/Sessions/Playing") -> MockResponse().setResponseCode(204)
                    path == "/jf/Videos/$movieIdDashed/stream" -> MockResponse().setBody("video-bytes")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun quickConnect(authenticated: Boolean) =
        """{"Authenticated":$authenticated,"Secret":"sec-1","Code":"482913","DeviceId":"d","DeviceName":"n","AppName":"AAFin","AppVersion":"1.0","DateAdded":"2026-11-11T08:00:00.0000000Z"}"""

    private fun baseUrl() = server.url("/jf").toString()

    private fun lastRequest(pathSuffix: String) = requests.last { it.requestUrl!!.encodedPath.endsWith(pathSuffix) }

    private fun signIn() = runBlocking { auth.signInWithPassword(auth.findServer(baseUrl()), "ben", "correct") }

    // ---------------------------------------------------------------------------------------

    @Test
    fun `findServer reads public info through the SDK`() = runBlocking {
        val info = auth.findServer(baseUrl() + "/web/index.html")

        assertEquals(baseUrl(), info.url)
        assertEquals("Home", info.name)
        assertEquals("srv1", info.id)
        assertEquals(ServerCompatibility.SUPPORTED, info.compatibility)
        assertFalse(info.isSecure)
    }

    @Test
    fun `a host that is not jellyfin is reported as such`() = runBlocking {
        val error = runCatching { auth.findServer(server.url("/elsewhere").toString()) }.exceptionOrNull() as JellyfinException
        assertEquals(JellyfinException.Kind.NOT_JELLYFIN, error.kind)
    }

    @Test
    fun `password sign-in sends the credentials and identifies the app`() = runBlocking {
        val account = signIn()

        assertEquals("srv1:$userIdDashed", account.id)
        assertEquals("ben", account.userName)
        assertEquals("tok-123", account.accessToken)
        assertEquals(baseUrl(), account.serverUrl)

        val request = lastRequest("/Users/AuthenticateByName")
        assertEquals("POST", request.method)
        val header = request.getHeader("Authorization")!!
        assertTrue(header, header.startsWith("MediaBrowser "))
        assertTrue(header, header.contains("Client=\"AAFin\""))
        assertTrue(header, header.contains("Version=\"1.0-test\""))
        // The SDK URL-encodes header values; the server decodes them again.
        assertTrue(header, header.contains("Device=\"Pixel+Test\""))
        assertTrue(header, header.contains("DeviceId=\"${accounts.deviceId()}\""))
        assertFalse("no token before sign-in", header.contains("Token="))
    }

    @Test
    fun `wrong password maps to UNAUTHORISED`() = runBlocking {
        val info = auth.findServer(baseUrl())
        val error = runCatching { auth.signInWithPassword(info, "ben", "wrong") }.exceptionOrNull() as JellyfinException
        assertEquals(JellyfinException.Kind.UNAUTHORISED, error.kind)
        assertNull(accounts.active())
    }

    @Test
    fun `quick connect runs end to end against the SDK`() = runBlocking {
        val states = auth.quickConnect(auth.findServer(baseUrl()), pollIntervalMs = 10).toList()

        assertEquals(QuickConnectState.AwaitingApproval("482913"), states[1])
        val success = states.last() as QuickConnectState.Success
        assertEquals("tok-123", success.account.accessToken)
        assertEquals("sec-1", lastRequest("/QuickConnect/Connect").requestUrl!!.queryParameter("secret"))
        assertTrue(lastRequest("/Users/AuthenticateWithQuickConnect").body.readUtf8().contains("\"Secret\":\"sec-1\""))
    }

    @Test
    fun `libraries are filtered to film and tv`() = runBlocking {
        signIn()
        val libraries = repository.libraries()

        assertEquals(listOf("Films", "Shows", "Mixed"), libraries.map { it.name })
        assertEquals(listOf(LibraryType.MOVIES, LibraryType.TV_SHOWS, LibraryType.MIXED), libraries.map { it.type })
        assertEquals("aaaaaaaa-0000-0000-0000-000000000001", libraries.first().id)

        val request = lastRequest("/UserViews")
        assertEquals(userIdDashed, request.requestUrl!!.queryParameter("userId"))
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-123\""))
    }

    @Test
    fun `library items are paged, sorted by name and mapped`() = runBlocking {
        signIn()
        val library = repository.libraries().first()
        val page = repository.libraryItems(library, startIndex = 50, limit = 50)

        assertEquals(120, page.totalCount)
        assertEquals(51, page.nextStartIndex)
        assertTrue(page.hasMore)
        val movie = page.items.single()
        assertEquals(movieIdDashed, movie.id)
        assertEquals("Big Buck Bunny", movie.name)
        assertEquals(ItemKind.MOVIE, movie.kind)
        assertEquals(7_200_000L, movie.runtimeMs)
        assertEquals(1_800_000L, movie.resumePositionMs)
        assertEquals("ptag", movie.primaryImageTag)
        assertEquals("btag", movie.backdropImageTag)
        assertTrue(movie.canDownload)

        val url = lastRequest("/Items").requestUrl!!
        assertEquals(library.id, url.queryParameter("parentId"))
        assertEquals("50", url.queryParameter("startIndex"))
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("true", url.queryParameter("recursive"))
        assertEquals("SortName", url.queryParameter("sortBy"))
        assertEquals("Ascending", url.queryParameter("sortOrder"))
        assertEquals("Movie", url.queryParameter("includeItemTypes"))

        assertEquals(
            "${baseUrl()}/Items/$movieIdDashed/Images/Primary?maxWidth=256&quality=90&tag=ptag",
            repository.posterUrl(movie, maxWidth = 256),
        )
    }

    @Test
    fun `resume, next up, episodes and search decode`() = runBlocking {
        signIn()

        assertEquals(listOf(ItemKind.MOVIE, ItemKind.EPISODE), repository.resumeItems().map { it.kind })

        val episode = repository.nextUp().single()
        assertEquals("Testing Times · S2:E1 · Pilot", episode.displayTitle)
        assertEquals("11111111-2222-3333-4444-555555555555", episode.seriesId)
        assertEquals(
            "${baseUrl()}/Items/11111111-2222-3333-4444-555555555555/Images/Primary?maxWidth=200&quality=90&tag=stag",
            repository.posterUrl(episode, maxWidth = 200),
        )

        assertEquals(1, repository.episodes(episode.seriesId!!, episode.seasonId).size)
        assertEquals("33333333-4444-5555-6666-777777777777", lastRequest("/Episodes").requestUrl!!.queryParameter("seasonId"))

        assertEquals(1, repository.search("  bunny ").size)
        assertEquals("bunny", lastRequest("/Items").requestUrl!!.queryParameter("searchTerm"))
        assertTrue(repository.search("   ").isEmpty())
    }

    @Test
    fun `browsing while signed out fails with NOT_SIGNED_IN`() = runBlocking {
        val error = runCatching { repository.libraries() }.exceptionOrNull() as JellyfinException
        assertEquals(JellyfinException.Kind.NOT_SIGNED_IN, error.kind)
    }

    @Test
    fun `playback info is requested with the device profile and resolved to a plan`() = runBlocking {
        signIn()
        val resolver = PlaybackResolver(clients, accounts, CodecCapabilities { it == "video/avc" || it == "audio/mp4a-latm" })

        val plan = resolver.planOnline(PlaybackRequest(movieIdDashed, QualityTier.P720_4M))

        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertTrue(plan.isHls)
        assertTrue(plan.url.startsWith("${baseUrl()}/videos/$movieIdDashed/master.m3u8?"))
        assertEquals("ps1", plan.playSessionId)
        assertEquals(movieId, plan.mediaSourceId)
        assertEquals(7_200_000L, plan.runtimeMs)
        assertEquals(listOf(1), plan.audioTracks.map { it.index })
        assertEquals("${baseUrl()}/Videos/$movieIdDashed/$movieId/Subtitles/2/0/Stream.srt?ApiKey=tok-123", plan.externalSubtitles.single().url)

        val body = Json.parseToJsonElement(lastRequest("/PlaybackInfo").body.readUtf8()).jsonObject
        assertEquals(userId, body.getValue("UserId").jsonPrimitive.content.replace("-", ""))
        assertEquals(4_000_000, body.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        assertEquals(-1, body.getValue("SubtitleStreamIndex").jsonPrimitive.int)
        assertTrue(body.getValue("EnableDirectPlay").jsonPrimitive.boolean)
        assertFalse("start position is applied by the player, not the server", body.containsKey("StartTimeTicks"))

        val profile = body.getValue("DeviceProfile").jsonObject
        assertEquals(4_000_000, profile.getValue("MaxStreamingBitrate").jsonPrimitive.int)
        val direct = profile.getValue("DirectPlayProfiles").jsonArray.single().jsonObject
        assertEquals("h264", direct.getValue("VideoCodec").jsonPrimitive.content)
        assertEquals("aac", direct.getValue("AudioCodec").jsonPrimitive.content)
        assertEquals("Video", direct.getValue("Type").jsonPrimitive.content)
        val transcoding = profile.getValue("TranscodingProfiles").jsonArray.single().jsonObject
        assertEquals("hls", transcoding.getValue("Protocol").jsonPrimitive.content)
        assertEquals("ts", transcoding.getValue("Container").jsonPrimitive.content)
        val widthCondition = profile.getValue("CodecProfiles").jsonArray
            .flatMap { it.jsonObject.getValue("Conditions").jsonArray }
            .map { it.jsonObject }
            .single { it.getValue("Property").jsonPrimitive.content == "Width" }
        assertEquals("LessThanEqual", widthCondition.getValue("Condition").jsonPrimitive.content)
        assertEquals("1280", widthCondition.getValue("Value").jsonPrimitive.content)
    }

    @Test
    fun `forced transcode disables direct play in the request`() = runBlocking {
        signIn()
        val resolver = PlaybackResolver(clients, accounts, CodecCapabilities { true })

        resolver.planOnline(PlaybackRequest(movieIdDashed, QualityTier.P720_4M, forceTranscode = true))

        val body = Json.parseToJsonElement(lastRequest("/PlaybackInfo").body.readUtf8()).jsonObject
        assertFalse(body.getValue("EnableDirectPlay").jsonPrimitive.boolean)
        assertFalse(body.getValue("EnableDirectStream").jsonPrimitive.boolean)
        assertTrue(body.getValue("EnableTranscoding").jsonPrimitive.boolean)
    }

    @Test
    fun `progress reports carry position in ticks, and queued replays omit the play session`() = runBlocking {
        val account = signIn()
        val gateway = SdkPlaystateGateway(clients, accounts)
        val snapshot = PlaybackSnapshot(account.id, movieIdDashed, movieId, "ps1", PlayMethod.TRANSCODE, 90_000, isPaused = true, audioStreamIndex = 1)

        gateway.reportStart(snapshot)
        gateway.reportProgress(snapshot)
        gateway.reportStopped(snapshot)

        fun body(suffix: String): JsonObject = Json.parseToJsonElement(lastRequest(suffix).body.readUtf8()).jsonObject
        val start = body("/Sessions/Playing")
        assertEquals(900_000_000L, start.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals("Transcode", start.getValue("PlayMethod").jsonPrimitive.content)
        assertEquals("ps1", start.getValue("PlaySessionId").jsonPrimitive.content)
        assertEquals(movieId, start.getValue("ItemId").jsonPrimitive.content.replace("-", ""))
        assertTrue(body("/Sessions/Playing/Progress").getValue("IsPaused").jsonPrimitive.boolean)
        assertEquals("ps1", body("/Sessions/Playing/Stopped").getValue("PlaySessionId").jsonPrimitive.content)

        gateway.reportPosition(QueuedPosition(account.id, movieIdDashed, movieId, 120_000))
        val replay = body("/Sessions/Playing/Stopped")
        assertEquals(1_200_000_000L, replay.getValue("PositionTicks").jsonPrimitive.long)
        assertFalse("a replay must not be able to end a newer transcode", replay.containsKey("PlaySessionId"))

        val gone = runCatching { gateway.reportPosition(QueuedPosition("removed-account", movieIdDashed, movieId, 1)) }.exceptionOrNull()
        assertEquals(JellyfinException.Kind.NOT_SIGNED_IN, (gone as JellyfinException).kind)
    }

    @Test
    fun `media requests get the auth header only on the account's server`() {
        signIn()
        val mediaClient = httpClient.newBuilder().addInterceptor(AuthHeaderInterceptor(accounts, clients)).build()

        mediaClient.newCall(Request.Builder().url("${baseUrl()}/Videos/$movieIdDashed/stream?static=true").build()).execute().close()
        val onServer = lastRequest("/stream").getHeader("Authorization")!!
        assertTrue(onServer.startsWith("MediaBrowser ") && onServer.contains("Token=\"tok-123\""))

        // Same host and port but outside the server's base path: must not receive the token.
        mediaClient.newCall(Request.Builder().url(server.url("/other/file.srt")).build()).execute().close()
        assertNull(requests.last { it.requestUrl!!.encodedPath == "/other/file.srt" }.getHeader("Authorization"))
    }
}
