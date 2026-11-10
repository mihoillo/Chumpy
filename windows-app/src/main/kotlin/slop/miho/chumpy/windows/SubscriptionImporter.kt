package slop.miho.chumpy.windows

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class SubscriptionImporter {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetch(url: String): List<String> {
        val uri = URI(url.trim())
        require(uri.scheme == "https" || uri.scheme == "http") { "Подписка должна быть HTTP(S)-ссылкой." }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", "Chumpy-Windows/0.1")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Сервер подписки ответил HTTP ${response.statusCode()}." }
        val body = response.body().trim().removePrefix("\uFEFF")
        val decoded = LinkUtils.decodeBase64(body)
        val content = if (decoded != null && decoded.contains("://")) decoded else body
        return content.lineSequence()
            .map(String::trim)
            .filter { LinkParsers.parse(it) != null }
            .distinct()
            .toList()
    }
}
