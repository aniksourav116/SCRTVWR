package srv.scrtvwr.util.logs

import timber.log.Timber

/** Public release logs are discarded rather than uploaded. */
class CrashlyticsTree : Timber.Tree() {
    override fun isLoggable(tag: String?, priority: Int): Boolean = false
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
}
