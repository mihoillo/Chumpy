package slop.miho.aurora.sub

/**
 * The metadata panels attach to a subscription response — Hiddify's dialect, which is the
 * de-facto standard: traffic counters in `subscription-userinfo`, the display name in
 * `profile-title` (plain or base64-prefixed), a filename hint in `content-disposition`,
 * and the refresh hint in `profile-update-interval`.
 */
data class SubscriptionMetadata(
    val title: String? = null,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireEpochSeconds: Long = 0,
    val updateIntervalHours: Int? = null,
)

object SubscriptionHeaders {
    /** Parses the interesting headers out of a response's header map. */
    fun parse(headers: Map<String, List<String>>): SubscriptionMetadata {
        fun first(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()?.takeIf { it.isNotBlank() }

        var meta = SubscriptionMetadata()
        first("subscription-userinfo")?.let {
            val counters = parseUserinfo(it)
            meta = meta.copy(
                uploadBytes = counters.uploadBytes,
                downloadBytes = counters.downloadBytes,
                totalBytes = counters.totalBytes,
                expireEpochSeconds = counters.expireEpochSeconds,
            )
        }
        first("profile-title")?.let { parseTitle(it)?.let { title -> meta = meta.copy(title = title) } }
        first("content-disposition")?.let { meta = meta.copy(title = meta.title ?: parseFilename(it)) }
        first("profile-update-interval")?.let { parseUpdateInterval(it)?.let { h -> meta = meta.copy(updateIntervalHours = h) } }
        return meta
    }

    /** "upload=123; download=456; total=789; expire=1700000000" → its four numbers. */
    fun parseUserinfo(value: String): SubscriptionMetadata {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L
        value.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@forEach
            val key = part.substring(0, idx).trim().lowercase()
            val number = part.substring(idx + 1).trim().toLongOrNull() ?: return@forEach
            when (key) {
                "upload" -> upload = number
                "download" -> download = number
                "total" -> total = number
                "expire" -> expire = number
            }
        }
        return SubscriptionMetadata(uploadBytes = upload, downloadBytes = download, totalBytes = total, expireEpochSeconds = expire)
    }

    /** Plain text, or "base64:…" encoded; both are in the wild. */
    fun parseTitle(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("base64:", ignoreCase = true)) {
            LinkUtils.decodeBase64(trimmed.substringAfter(':'))?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            return null
        }
        // URLEncoded titles are common too.
        return LinkUtils.decodeURIComponent(trimmed).takeIf { it.isNotBlank() }
    }

    /** "attachment; filename=Name.txt" → "Name". */
    fun parseFilename(value: String): String? {
        val match = "filename\\*?=\"?([^\";]+)\"?".toRegex().find(value) ?: return null
        val name = match.groupValues[1].trim()
        return name.removeSuffix(".txt").removeSuffix(".list").takeIf { it.isNotEmpty() }
    }

    /** Hours, as the header carries it. */
    fun parseUpdateInterval(value: String): Int? =
        value.trim().substringBefore(';').toDoubleOrNull()?.let { hours ->
            if (hours > 0) hours.toInt().coerceAtLeast(1) else null
        }
}
