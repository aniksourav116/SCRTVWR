package srv.scrtvwr.preferences

import android.content.Context
import org.json.JSONArray
import srv.scrtvwr.ui.sort.DirectorySortOrder

class ScrtvwrPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getSlideShowStayMillis(): Long =
        preferences.getLong(KEY_SLIDE_SHOW_STAY_MILLIS, DEFAULT_SLIDE_SHOW_STAY_MILLIS)

    fun saveSlideShowStayMillis(stayMillis: Long) {
        preferences.edit()
            .putLong(KEY_SLIDE_SHOW_STAY_MILLIS, stayMillis.coerceAtLeast(1L))
            .apply()
    }

    fun getRecentDirectories(): List<String> =
        preferences.getString(KEY_RECENT_DIRECTORIES, null)
            ?.let(::decodeStringList)
            .orEmpty()

    fun rememberDirectory(path: String) {
        val recentDirectories = (listOf(path) + getRecentDirectories().filterNot { it == path })
            .take(MAX_RECENT_DIRECTORIES)
        preferences.edit()
            .putString(KEY_RECENT_DIRECTORIES, encodeStringList(recentDirectories))
            .putString(KEY_LAST_DIRECTORY, path)
            .apply()
    }

    fun getLastDirectory(): String? =
        preferences.getString(KEY_LAST_DIRECTORY, null)

    fun getDirectorySortOrder(): DirectorySortOrder =
        preferences.getString(KEY_DIRECTORY_SORT_ORDER, null)
            ?.let { value -> DirectorySortOrder.entries.firstOrNull { it.name == value } }
            ?: DirectorySortOrder.NameAscending

    fun saveDirectorySortOrder(sortOrder: DirectorySortOrder) {
        preferences.edit()
            .putString(KEY_DIRECTORY_SORT_ORDER, sortOrder.name)
            .apply()
    }

    fun getLastMediaSession(): LastMediaSession? {
        val directoryPath = preferences.getString(KEY_LAST_MEDIA_DIRECTORY, null) ?: return null
        val mediaPath = preferences.getString(KEY_LAST_MEDIA_PATH, null)
        return LastMediaSession(directoryPath = directoryPath, mediaPath = mediaPath)
    }

    fun saveLastMediaSession(directoryPath: String, mediaPath: String?) {
        preferences.edit()
            .putString(KEY_LAST_MEDIA_DIRECTORY, directoryPath)
            .putString(KEY_LAST_MEDIA_PATH, mediaPath)
            .apply()
    }

    private fun encodeStringList(values: List<String>): String {
        val json = JSONArray()
        values.forEach(json::put)
        return json.toString()
    }

    private fun decodeStringList(value: String): List<String> =
        runCatching {
            val json = JSONArray(value)
            List(json.length()) { index -> json.optString(index) }
                .filter(String::isNotBlank)
        }.getOrDefault(emptyList())

    private companion object {
        const val PREFERENCES_NAME = "scrtvwr_preferences"
        const val KEY_SLIDE_SHOW_STAY_MILLIS = "slide_show_stay_millis"
        const val KEY_RECENT_DIRECTORIES = "recent_directories"
        const val KEY_LAST_DIRECTORY = "last_directory"
        const val KEY_DIRECTORY_SORT_ORDER = "directory_sort_order"
        const val KEY_LAST_MEDIA_DIRECTORY = "last_media_directory"
        const val KEY_LAST_MEDIA_PATH = "last_media_path"
        const val DEFAULT_SLIDE_SHOW_STAY_MILLIS = 50L
        const val MAX_RECENT_DIRECTORIES = 100
    }
}

data class LastMediaSession(
    val directoryPath: String,
    val mediaPath: String?,
)
