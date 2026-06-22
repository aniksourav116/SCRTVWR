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

    fun getHoldSlideShowEnabled(): Boolean =
        preferences.getBoolean(KEY_HOLD_SLIDE_SHOW_ENABLED, false)

    fun saveHoldSlideShowEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HOLD_SLIDE_SHOW_ENABLED, enabled).apply()
    }

    fun getImagePreloadDistance(): Int =
        preferences.getInt(KEY_IMAGE_PRELOAD_DISTANCE, DEFAULT_IMAGE_PRELOAD_DISTANCE)

    fun saveImagePreloadDistance(distance: Int) {
        preferences.edit()
            .putInt(KEY_IMAGE_PRELOAD_DISTANCE, distance.coerceAtLeast(0))
            .apply()
    }

    fun getShowHiddenFiles(): Boolean =
        preferences.getBoolean(KEY_SHOW_HIDDEN_FILES, false)

    fun saveShowHiddenFiles(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_HIDDEN_FILES, enabled).apply()
    }

    fun getBookmarkedMedia(): Set<String> =
        preferences.getStringSet(KEY_BOOKMARKED_MEDIA, emptySet()).orEmpty().toSet()

    fun setMediaBookmarked(path: String, bookmarked: Boolean) {
        val bookmarks = getBookmarkedMedia().toMutableSet()
        if (bookmarked) bookmarks += path else bookmarks -= path
        preferences.edit().putStringSet(KEY_BOOKMARKED_MEDIA, bookmarks).apply()
    }

    fun isMediaBookmarked(path: String?): Boolean =
        path != null && path in getBookmarkedMedia()

    fun getRecentDirectories(): List<String> =
        getMediaHistory().map(LastMediaSession::directoryPath)

    fun saveLastDirectory(path: String) {
        preferences.edit()
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
        val history = (listOf(LastMediaSession(directoryPath, mediaPath)) +
            getMediaHistory().filterNot { it.directoryPath == directoryPath })
            .take(MAX_RECENT_DIRECTORIES)
        preferences.edit()
            .putString(KEY_LAST_MEDIA_DIRECTORY, directoryPath)
            .putString(KEY_LAST_MEDIA_PATH, mediaPath)
            .putString(KEY_MEDIA_HISTORY, encodeMediaHistory(history))
            .apply()
    }

    fun getMediaHistory(): List<LastMediaSession> =
        preferences.getString(KEY_MEDIA_HISTORY, null)
            ?.let(::decodeMediaHistory)
            .orEmpty()

    private fun encodeMediaHistory(values: List<LastMediaSession>): String {
        val json = JSONArray()
        values.forEach { session ->
            json.put(JSONArray().put(session.directoryPath).put(session.mediaPath))
        }
        return json.toString()
    }

    private fun decodeMediaHistory(value: String): List<LastMediaSession> =
        runCatching {
            val json = JSONArray(value)
            List(json.length()) { index ->
                json.optJSONArray(index)?.let { session ->
                    LastMediaSession(
                        directoryPath = session.optString(0),
                        mediaPath = session.optString(1).takeIf(String::isNotBlank),
                    )
                }
            }.filterNotNull().filter { it.directoryPath.isNotBlank() }
        }.getOrDefault(emptyList())

    private companion object {
        const val PREFERENCES_NAME = "scrtvwr_preferences"
        const val KEY_SLIDE_SHOW_STAY_MILLIS = "slide_show_stay_millis"
        const val KEY_HOLD_SLIDE_SHOW_ENABLED = "hold_slide_show_enabled"
        const val KEY_IMAGE_PRELOAD_DISTANCE = "image_preload_distance"
        const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        const val KEY_BOOKMARKED_MEDIA = "bookmarked_media"
        const val KEY_MEDIA_HISTORY = "media_history"
        const val KEY_LAST_DIRECTORY = "last_directory"
        const val KEY_DIRECTORY_SORT_ORDER = "directory_sort_order"
        const val KEY_LAST_MEDIA_DIRECTORY = "last_media_directory"
        const val KEY_LAST_MEDIA_PATH = "last_media_path"
        const val DEFAULT_SLIDE_SHOW_STAY_MILLIS = 50L
        const val DEFAULT_IMAGE_PRELOAD_DISTANCE = 5
        const val MAX_RECENT_DIRECTORIES = 100
    }
}

data class LastMediaSession(
    val directoryPath: String,
    val mediaPath: String?,
)
