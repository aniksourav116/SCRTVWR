package srv.scrtvwr.util.logs

import timber.log.Timber

/**
 * Debug logger that prefixes each message with its source file, line, and method.
 */
class CustomLogger : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val source = Throwable().stackTrace.firstOrNull { element ->
            element.className !in LOGGER_CLASSES
        }
        val prefix = source?.let {
            "[(${it.fileName}:${it.lineNumber})->${it.methodName}]"
        } ?: "[Unknown Source]"

        super.log(priority, tag, "$prefix $message", t)
    }

    private companion object {
        val LOGGER_CLASSES = setOf(
            Timber::class.java.name,
            CustomLogger::class.java.name,
            "timber.log.Timber\$Forest",
            "timber.log.Timber\$Tree",
        )
    }
}
