package slop.miho.chumpy.core

import slop.miho.chumpy.xray.XrayConfigBuilder

internal object DelayProbe {
    private const val BACKUP_URL = "https://cp.cloudflare.com/generate_204"

    fun measure(url: String, probe: (String) -> Long): Long {
        try { return probe(url) }
        catch (first: Exception) {
            val networkFailure = generateSequence(first as Throwable?) { it.cause }.take(8).any {
                val message = it.message.orEmpty().lowercase()
                message.contains("tls handshake timeout") || message.contains("i/o timeout") ||
                    message.contains("connection reset") || message.contains("no such host") ||
                    message.contains("network is unreachable") || message.contains("context deadline exceeded")
            }
            if (url != XrayConfigBuilder.DEFAULT_TEST_URL || !networkFailure) throw first
            return probe(BACKUP_URL)
        }
    }
}
