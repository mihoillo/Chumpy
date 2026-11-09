package slop.miho.chumpy.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update checker's pure half: the version triplet in its dress (a tag's "v", a
 * version name's builder suffix) and the release payload's asset choice. The network
 * half is a plain OkHttp call the tests leave alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateCheckTest {
    /** A latest-release payload, cut to the fields the checker reads. */
    private val payload = """
        {
          "tag_name": "v0.6.221",
          "assets": [
            {"name": "Chumpy-0.6.221-miho-debug-arm64-v8a.apk", "size": 10,
             "browser_download_url": "debug"},
            {"name": "Chumpy-0.6.221-miho-release-arm64-v8a.apk", "size": 15597610,
             "browser_download_url": "https://example/arm64.apk"},
            {"name": "Chumpy-0.6.221-miho-release-armeabi-v7a.apk", "size": 16,
             "browser_download_url": "https://example/v7a.apk"},
            {"name": "Chumpy-0.6.221-miho-release-universal.apk", "size": 52,
             "browser_download_url": "https://example/universal.apk"}
          ]
        }
    """

    @Test
    fun `versions arrive in their dress and compare by the triplet`() {
        assertEquals(AppVersion(0, 6, 227), AppVersion.parse("0.6.227-miho"))
        assertEquals(AppVersion(0, 6, 221), AppVersion.parse("v0.6.221"))
        assertEquals(AppVersion(0, 7, 0), AppVersion.parse("V0.7.0-beta+1"))
        assertNull(AppVersion.parse("milestone-3"))
        assertNull(AppVersion.parse("0.6"))
        assertTrue(AppVersion.parse("v0.7.0")!! > AppVersion.parse("0.6.227-miho")!!)
        assertTrue(AppVersion.parse("v0.6.221")!! < AppVersion.parse("0.6.227-miho")!!)
        assertFalse(AppVersion.parse("v0.7.0")!! <= AppVersion.parse("0.6.227")!!)
    }

    @Test
    fun `the release picks the device's own release build`() {
        val release = UpdateCheck.parseLatest(payload, listOf("arm64-v8a"))!!
        assertEquals("v0.6.221", release.tag)
        assertEquals("0.6.221", release.version.toString())
        // The debug carry-along never installs; the matching ABI's release build does.
        assertEquals("https://example/arm64.apk", release.apkUrl)
        assertEquals(15_597_610L, release.apkSize)
    }

    @Test
    fun `the device's ABI order leads the pick`() {
        // The primary ABI has no build of its own — the next one in the system's
        // preference order answers with its own.
        val release = UpdateCheck.parseLatest(payload, listOf("x86", "armeabi-v7a"))!!
        assertEquals("https://example/v7a.apk", release.apkUrl)
    }

    @Test
    fun `an ABI the release ships nothing for answers nothing, universal included`() {
        // The universal carry-along still rides the payload — and is passed by: no
        // device gets handed a file three times its own build's weight.
        assertNull(UpdateCheck.parseLatest(payload, listOf("x86")))
    }

    @Test
    fun `a release without anything this device installs answers nothing`() {
        assertNull(UpdateCheck.parseLatest("""{"tag_name":"v0.7.0","assets":[]}""", listOf("arm64-v8a")))
        assertNull(UpdateCheck.parseLatest("""{"tag_name":"v0.7.0"}""", listOf("arm64-v8a")))
        assertNull(UpdateCheck.parseLatest("""{"assets":[{"name":"Chumpy-x-release-arm64-v8a.apk"}]}""", listOf("arm64-v8a")))
        // And a tag that is not a version cannot outrank anything.
        assertNull(UpdateCheck.parseLatest("""{"tag_name":"milestone-3","assets":[]}""", listOf("arm64-v8a")))
    }
}
