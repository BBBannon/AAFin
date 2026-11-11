package app.aafin.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point Android Auto binds to. Declared in the manifest under the NAVIGATION category,
 * which is what gives the app a drawing surface for video.
 */
class AAFinCarAppService : CarAppService() {

    /** A personal, sideloaded app: any host (Android Auto, the desktop head unit) may connect. */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = AAFinSession()
}
