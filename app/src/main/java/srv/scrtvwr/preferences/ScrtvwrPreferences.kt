package srv.scrtvwr.preferences

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import srv.scrtvwr.media.MediaPaths
import srv.scrtvwr.media.remote.HttpCredentials
import srv.scrtvwr.media.remote.HttpServer
import srv.scrtvwr.media.remote.HttpServerAuth
import srv.scrtvwr.media.remote.HttpServerConnections
import srv.scrtvwr.ui.media.MediaEdgeDialogStyle
import srv.scrtvwr.ui.media.SlideShowDirection
import srv.scrtvwr.ui.media.VideoCompletionAction
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
        preferences.getBoolean(KEY_HOLD_SLIDE_SHOW_ENABLED, true)

    fun saveHoldSlideShowEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HOLD_SLIDE_SHOW_ENABLED, enabled).apply()
    }

    fun getSlideShowDirection(): SlideShowDirection =
        preferences.getString(KEY_SLIDE_SHOW_DIRECTION, null)
            ?.let { value -> SlideShowDirection.entries.firstOrNull { it.name == value } }
            ?: SlideShowDirection.Next

    fun saveSlideShowDirection(direction: SlideShowDirection) {
        preferences.edit()
            .putString(KEY_SLIDE_SHOW_DIRECTION, direction.name)
            .apply()
    }

    fun getImagePreloadDistance(): Int =
        preferences.getInt(KEY_IMAGE_PRELOAD_DISTANCE, DEFAULT_IMAGE_PRELOAD_DISTANCE)

    fun saveImagePreloadDistance(distance: Int) {
        preferences.edit()
            .putInt(KEY_IMAGE_PRELOAD_DISTANCE, distance.coerceAtLeast(0))
            .apply()
    }

    fun getVideoPreloadDistance(): Int =
        preferences.getInt(KEY_VIDEO_PRELOAD_DISTANCE, DEFAULT_VIDEO_PRELOAD_DISTANCE)

    fun saveVideoPreloadDistance(distance: Int) {
        preferences.edit()
            .putInt(KEY_VIDEO_PRELOAD_DISTANCE, distance.coerceAtLeast(0))
            .apply()
    }

    fun getVideoCompletionAction(): VideoCompletionAction =
        preferences.getString(KEY_VIDEO_COMPLETION_ACTION, null)
            ?.let { value -> VideoCompletionAction.entries.firstOrNull { it.name == value } }
            ?: VideoCompletionAction.Stop

    fun saveVideoCompletionAction(action: VideoCompletionAction) {
        preferences.edit()
            .putString(KEY_VIDEO_COMPLETION_ACTION, action.name)
            .apply()
    }

    fun getMediaEdgeDialogStyle(): MediaEdgeDialogStyle =
        preferences.getString(KEY_MEDIA_EDGE_DIALOG_STYLE, null)
            ?.let { value -> MediaEdgeDialogStyle.entries.firstOrNull { it.name == value } }
            ?: MediaEdgeDialogStyle.Bottom

    fun saveMediaEdgeDialogStyle(style: MediaEdgeDialogStyle) {
        preferences.edit()
            .putString(KEY_MEDIA_EDGE_DIALOG_STYLE, style.name)
            .apply()
    }

    fun getShowHiddenFiles(): Boolean =
        preferences.getBoolean(KEY_SHOW_HIDDEN_FILES, false)

    fun saveShowHiddenFiles(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_HIDDEN_FILES, enabled).apply()
    }

    fun getShowDirectoryFileActions(): Boolean =
        preferences.getBoolean(KEY_SHOW_DIRECTORY_FILE_ACTIONS, false)

    fun saveShowDirectoryFileActions(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_DIRECTORY_FILE_ACTIONS, enabled).apply()
    }

    fun getLoadHttpVideoThumbnails(): Boolean =
        preferences.getBoolean(KEY_LOAD_HTTP_VIDEO_THUMBNAILS, true)

    fun saveLoadHttpVideoThumbnails(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOAD_HTTP_VIDEO_THUMBNAILS, enabled).apply()
    }

    fun getBookmarkedMedia(): Set<String> =
        preferences.getStringSet(KEY_BOOKMARKED_MEDIA, emptySet()).orEmpty().toSet()

    fun setMediaBookmarked(path: String, bookmarked: Boolean) {
        val bookmarks = getBookmarkedMedia().toMutableSet()
        if (bookmarked) bookmarks += path else bookmarks -= path
        preferences.edit().putStringSet(KEY_BOOKMARKED_MEDIA, bookmarks).apply()
    }

    fun clearBookmarkedMedia() {
        preferences.edit().remove(KEY_BOOKMARKED_MEDIA).apply()
    }

    fun removeBookmarkedMediaDirectory(directoryPath: String) {
        val bookmarks = getBookmarkedMedia()
            .filterNot { path -> MediaPaths.parent(path.substringBefore("::")) == directoryPath }
            .toSet()
        preferences.edit().putStringSet(KEY_BOOKMARKED_MEDIA, bookmarks).apply()
    }

    fun isMediaBookmarked(path: String?): Boolean =
        path != null && path in getBookmarkedMedia()

    fun getHttpServers(): List<HttpServer> =
        preferences.getString(KEY_HTTP_SERVERS, null)
            ?.let(::decodeHttpServers)
            .orEmpty()

    fun saveHttpServers(servers: List<HttpServer>) {
        preferences.edit()
            .putString(KEY_HTTP_SERVERS, encodeHttpServers(servers))
            .apply()
        HttpServerAuth.update(servers)
    }

    /** Saved servers connected in this process; see [HttpServerConnections]. */
    fun getConnectedHttpServers(): List<HttpServer> = HttpServerConnections.connected(getHttpServers())

    fun setHttpServerConnected(serverId: String, connected: Boolean) {
        HttpServerConnections.setConnected(serverId, connected, getHttpServers())
    }

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

    fun removeMediaHistoryDirectory(directoryPath: String) {
        val history = getMediaHistory().filterNot { it.directoryPath == directoryPath }
        val editor = preferences.edit()
            .putString(KEY_MEDIA_HISTORY, encodeMediaHistory(history))
        if (getLastMediaSession()?.directoryPath == directoryPath) {
            editor
                .remove(KEY_LAST_MEDIA_DIRECTORY)
                .remove(KEY_LAST_MEDIA_PATH)
        }
        editor.apply()
    }

    fun clearMediaHistory() {
        preferences.edit()
            .remove(KEY_MEDIA_HISTORY)
            .remove(KEY_LAST_MEDIA_DIRECTORY)
            .remove(KEY_LAST_MEDIA_PATH)
            .apply()
    }

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

    private fun encodeHttpServers(servers: List<HttpServer>): String {
        val json = JSONArray()
        servers.forEach { server ->
            json.put(
                JSONObject()
                    .put("id", server.id)
                    .put("name", server.name)
                    .put("url", server.baseUrl)
                    .put("autoReconnect", server.autoReconnect)
                    .apply {
                        server.credentials?.let { credentials ->
                            put("username", credentials.username)
                            // Only the Keystore-encrypted form of the password is written.
                            KeystoreSecrets.encrypt(credentials.password)?.let { put("password", it) }
                        }
                    },
            )
        }
        return json.toString()
    }

    private fun decodeHttpServers(value: String): List<HttpServer> =
        runCatching {
            val json = JSONArray(value)
            List(json.length()) { index ->
                json.optJSONObject(index)?.let { server ->
                    HttpServer(
                        id = server.optString("id"),
                        name = server.optString("name"),
                        baseUrl = server.optString("url"),
                        autoReconnect = server.optBoolean("autoReconnect", false),
                        // A password this device cannot decrypt reads as empty, so the server
                        // answers 401 and its card asks to sign in again.
                        credentials = if (server.has("username")) {
                            HttpCredentials(
                                username = server.optString("username"),
                                password = server.optString("password").takeIf(String::isNotEmpty)
                                    ?.let(KeystoreSecrets::decrypt)
                                    .orEmpty(),
                            )
                        } else {
                            null
                        },
                    )
                }
            }.filterNotNull().filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
        }.getOrDefault(emptyList())

    private companion object {
        const val PREFERENCES_NAME = "scrtvwr_preferences"
        const val KEY_SLIDE_SHOW_STAY_MILLIS = "slide_show_stay_millis"
        const val KEY_HOLD_SLIDE_SHOW_ENABLED = "hold_slide_show_enabled"
        const val KEY_SLIDE_SHOW_DIRECTION = "slide_show_direction"
        const val KEY_IMAGE_PRELOAD_DISTANCE = "image_preload_distance"
        const val KEY_VIDEO_PRELOAD_DISTANCE = "video_preload_distance"
        const val KEY_VIDEO_COMPLETION_ACTION = "video_completion_action"
        const val KEY_MEDIA_EDGE_DIALOG_STYLE = "media_edge_dialog_style"
        const val KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
        const val KEY_SHOW_DIRECTORY_FILE_ACTIONS = "show_directory_file_actions"
        const val KEY_LOAD_HTTP_VIDEO_THUMBNAILS = "load_http_video_thumbnails"
        const val KEY_BOOKMARKED_MEDIA = "bookmarked_media"
        const val KEY_MEDIA_HISTORY = "media_history"
        const val KEY_LAST_DIRECTORY = "last_directory"
        const val KEY_DIRECTORY_SORT_ORDER = "directory_sort_order"
        const val KEY_LAST_MEDIA_DIRECTORY = "last_media_directory"
        const val KEY_LAST_MEDIA_PATH = "last_media_path"
        const val KEY_HTTP_SERVERS = "http_servers"
        const val DEFAULT_SLIDE_SHOW_STAY_MILLIS = 100L
        const val DEFAULT_IMAGE_PRELOAD_DISTANCE = 5
        const val DEFAULT_VIDEO_PRELOAD_DISTANCE = 0
        const val MAX_RECENT_DIRECTORIES = 100
    }
}

data class LastMediaSession(
    val directoryPath: String,
    val mediaPath: String?,
)
