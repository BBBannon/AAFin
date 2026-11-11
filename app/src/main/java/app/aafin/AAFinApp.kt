package app.aafin

import android.app.Application
import android.content.Context

class AAFinApp : Application() {

    /** The app's singletons. Everything in it is created lazily, so start-up stays instant. */
    val graph: AppGraph by lazy { AppGraph(this) }

    companion object {
        /** The [AppGraph] from any [Context] (activity, service, car context, worker). */
        fun graph(context: Context): AppGraph = (context.applicationContext as AAFinApp).graph
    }
}
