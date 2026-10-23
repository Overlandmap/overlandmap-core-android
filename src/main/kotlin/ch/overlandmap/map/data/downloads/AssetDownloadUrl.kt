package ch.overlandmap.map.data.downloads

/** Asset records may contain an HTTP URL, a bucket-relative Storage path, or a gs:// URL. */
internal suspend fun resolveAssetDownloadUrl(
    value: String,
    storageDownloadUrl: suspend (String) -> String,
): String {
    val path = value.trim()
    require(path.isNotEmpty()) { "Missing asset download URL" }
    val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(path)?.groupValues?.get(1)?.lowercase()
    return when (scheme) {
        "http", "https" -> path
        null, "gs" -> storageDownloadUrl(path)
        else -> throw IllegalArgumentException("Unsupported asset download URL scheme: $scheme")
    }
}
