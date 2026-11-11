package app.aafin.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** One connection to a car screen. Stage 2 adds the surface renderer and the real screens. */
class AAFinSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen = PlaceholderScreen(carContext)
}
