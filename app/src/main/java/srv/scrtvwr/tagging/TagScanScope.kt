package srv.scrtvwr.tagging

import java.io.File

/** Whether tagging may go anywhere by default, or only into the folders the user named. */
enum class TagScopeMode {
    AllStorage,
    SelectedFolders,
}

/**
 * One folder the user has an opinion about. [includeSubfolders] decides whether the opinion reaches
 * the folders beneath it, and [included] whether the opinion is "tag here" or "never tag here".
 */
data class TagFolderRule(
    val path: String,
    val includeSubfolders: Boolean = true,
    val included: Boolean = true,
)

/**
 * Which folders tagging is allowed to read, for both the lazy per-folder pass and the big scan.
 *
 * [TagScopeMode.AllStorage] tags anywhere except where a rule says otherwise;
 * [TagScopeMode.SelectedFolders] tags nowhere except where a rule says so. Either way the rule that
 * applies to a folder is the most specific one covering it, so "tag DCIM and everything under it,
 * but never DCIM/Screenshots" is two rules rather than a special case.
 *
 * Paths are compared as text, on the Android separator rather than the platform's: these are device
 * paths whichever machine the comparison happens to run on.
 */
data class TagScanScope(
    val mode: TagScopeMode = TagScopeMode.AllStorage,
    val rules: List<TagFolderRule> = emptyList(),
) {
    val includedFolders: List<TagFolderRule>
        get() = rules.filter(TagFolderRule::included)

    val skippedFolders: List<TagFolderRule>
        get() = rules.filterNot(TagFolderRule::included)

    /** True when there is nowhere left for tagging to look, so there is no point starting a pass. */
    val isEmpty: Boolean
        get() = mode == TagScopeMode.SelectedFolders && includedFolders.isEmpty()

    /** True when tagging may read the images sitting directly in [directory]. */
    fun allowsDirectory(directory: String): Boolean =
        ruleCovering(normalize(directory))?.included ?: (mode == TagScopeMode.AllStorage)

    /** True when tagging may read [path]. An archive entry answers for the archive holding it. */
    fun allowsFile(path: String): Boolean = allowsDirectory(parentOf(path))

    /**
     * True when the user pointed tagging at [directory] itself or at a folder inside it. Hidden
     * folders and app sandboxes are skipped by default, and this is what overrules that default:
     * a chosen folder deep inside one of them has to stay reachable.
     */
    fun reachesInto(directory: String): Boolean {
        val normalized = normalize(directory)
        return rules.any { rule ->
            val path = normalize(rule.path)
            rule.included && (path == normalized || path.startsWith(prefixOf(normalized)))
        }
    }

    /** True when walking into [directory] can still reach something in scope, here or further down. */
    fun allowsDescent(directory: String): Boolean = allowsDirectory(directory) || reachesInto(directory)

    fun ruleAt(directory: String): TagFolderRule? {
        val normalized = normalize(directory)
        return rules.firstOrNull { normalize(it.path) == normalized }
    }

    /** Adds [rule], replacing whatever was said about the same folder before. */
    fun withRule(rule: TagFolderRule): TagScanScope {
        val normalized = normalize(rule.path)
        return copy(
            rules = rules.filterNot { normalize(it.path) == normalized } + rule.copy(path = normalized),
        )
    }

    fun withoutRule(path: String): TagScanScope {
        val normalized = normalize(path)
        return copy(rules = rules.filterNot { normalize(it.path) == normalized })
    }

    /**
     * Where a scan starts. Folders nested inside another chosen folder are left out: the walk
     * descends into them from the folder above, and starting there again would tag them twice.
     */
    fun scanRootPaths(storageRoot: String?): List<String> = when (mode) {
        TagScopeMode.AllStorage -> listOfNotNull(storageRoot?.let { root -> normalize(root) })
        TagScopeMode.SelectedFolders -> includedFolders
            .map { normalize(it.path) }
            .distinct()
            .sorted()
            .fold(mutableListOf<String>()) { roots, path ->
                if (roots.none { root -> path.startsWith(prefixOf(root)) }) roots += path
                roots
            }
    }

    fun scanRoots(storageRoot: File?): List<File> =
        scanRootPaths(storageRoot?.absolutePath).map(::File)

    private fun ruleCovering(directory: String): TagFolderRule? = rules
        .filter { rule ->
            val path = normalize(rule.path)
            path == directory || (rule.includeSubfolders && directory.startsWith(prefixOf(path)))
        }
        // The most specific rule wins, so a skipped subfolder survives a tagged parent.
        .maxByOrNull { normalize(it.path).length }

    private companion object {
        const val SEPARATOR = '/'
        const val ARCHIVE_ENTRY_SEPARATOR = "::"

        /** Paths arrive from both File.absolutePath and the user, so a trailing slash is possible. */
        fun normalize(path: String): String =
            path.trimEnd(SEPARATOR).ifEmpty { SEPARATOR.toString() }

        /** The folder holding [path], with any archive entry inside the file dropped first. */
        fun parentOf(path: String): String =
            normalize(path.substringBefore(ARCHIVE_ENTRY_SEPARATOR)).substringBeforeLast(SEPARATOR)
                .ifEmpty { SEPARATOR.toString() }

        /** [path] in the form that matches everything underneath it and nothing beside it. */
        fun prefixOf(path: String): String = path + SEPARATOR
    }
}
