package app.aafin.car

import android.content.Intent
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.aafin.AAFinApp
import app.aafin.AppGraph
import app.aafin.car.render.VideoSurfaceRenderer
import app.aafin.car.screens.HomeScreen

/**
 * One connection to a car screen (wired or wireless - Android Auto hides the difference).
 *
 * On connect: starts the video surface renderer, works out which car this is (for its quality
 * profile) and shows Home. On disconnect: stops playback, which also tells Jellyfin where the
 * viewer got to, and takes the video window down.
 *
 * AAFin sits in the NAVIGATION category only to be given a drawing surface. It never calls
 * `NavigationManager.navigationStarted()`, so it does not take over from the real sat-nav.
 */
class AAFinSession : Session() {

    private lateinit var graph: AppGraph
    private lateinit var env: CarEnv
    private var renderer: VideoSurfaceRenderer? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = connect()
            override fun onDestroy(owner: LifecycleOwner) = disconnect()
        })
    }

    private fun connect() {
        Log.i(CarLog.CAR, "Session created (car API level ${carContext.carAppApiLevel})")
        graph = AAFinApp.graph(carContext)
        val backend = GraphCarBackend(graph)
        val profile = CarProfileSession(lifecycleScope, graph.carProfiles, fetchModel = { fetchCarModel(carContext) })
        val renderer = VideoSurfaceRenderer(
            context = carContext,
            scope = lifecycleScope,
            playback = backend.playback,
            player = { graph.playback.player },
            togglePlayPause = backend::togglePlayPause,
            seekBy = backend::seekBy,
            onSurface = profile::onSurface,
        )
        this.renderer = renderer
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        } catch (e: RuntimeException) {
            // SecurityException (surface permission refused) or HostException. Browsing still
            // works; without a surface there is sound but no picture, and the log says why.
            Log.e(CarLog.SURFACE, "The car would not give AAFin a surface: ${e.javaClass.simpleName}: ${e.message}")
        }
        env = CarEnv(backend, HostContentLimits(carContext), CoilPosterSource(carContext, backend), profile, renderer)
    }

    private fun disconnect() {
        Log.i(CarLog.CAR, "Session destroyed; stopping playback")
        graph.playback.stop()
        renderer?.release()
        renderer = null
    }

    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext, env)
}
