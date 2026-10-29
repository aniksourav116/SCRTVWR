package srv.scrtvwr.util.time


import android.content.Context
import android.text.format.DateUtils

object TimeUtil {
    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    /** [time] in epoch seconds as a date, or "" when it is unknown, such as a server file not asked yet. */
    fun getDateAndTimeWithWeekDay(time : Long? = 0L) : String {
        val updatedTime = (time?: 0L)*1000L
        if (updatedTime <= 0L) return ""
        val date = DateUtils.formatDateTime(
            requireContext(),
            updatedTime,
            DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_ALL or
                    DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_12HOUR or
                    DateUtils.FORMAT_SHOW_WEEKDAY
        )
        return date
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
