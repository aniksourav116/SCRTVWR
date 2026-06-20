package srv.scrtvwr.util.time


import android.content.Context
import android.text.format.DateUtils

object TimeUtil {
    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun formatDisplayDate(
        timestampMillis: Long
    ): String = DateUtils.formatDateTime(
        requireContext(),
        timestampMillis,
        DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_ABBREV_MONTH
    )

    fun formatNumericDate(
        timestampMillis: Long
    ): String = DateUtils.formatDateTime(
        requireContext(),
        timestampMillis,
        DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_NUMERIC_DATE
    )

    private fun requireContext(): Context {
        check(::applicationContext.isInitialized) {
            "TimeUtil must be initialized from Application.onCreate()."
        }
        return applicationContext
    }
}
