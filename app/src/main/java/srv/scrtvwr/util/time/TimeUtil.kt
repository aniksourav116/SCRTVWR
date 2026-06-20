package srv.scrtvwr.util.time


import android.content.Context
import android.text.format.DateUtils
import timber.log.Timber

object TimeUtil {
    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getDateAndTimeWithWeekDay(time : Long? = 0L) : String {
        val updatedTime = (time?: 0L)*1000L
        val date = DateUtils.formatDateTime(
            requireContext(),
            if (updatedTime > 0L) updatedTime else System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_ALL or
                    DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_12HOUR or
                    DateUtils.FORMAT_SHOW_WEEKDAY
        )
        Timber.d("$time,000L and $date")
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
