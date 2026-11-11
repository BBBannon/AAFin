package app.aafin.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import app.aafin.R

/** Deliberate Stage 1 placeholder: proves the app installs and opens in Android Auto. */
class PlaceholderScreen(carContext: CarContext) : Screen(carContext) {

    // setTitle/setHeaderAction are deprecated in favour of Header, which needs a newer car API
    // level than the minCarApiLevel of 1 declared in the manifest.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.car_placeholder_message))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setIcon(CarIcon.APP_ICON)
            .build()
}
