package slop.miho.chumpy.windows

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

class WindowsProfilePortTest {
    @Test
    fun parsesVlessAndBuildsBothWindowsModes() {
        val link = "vless://12345678-1234-1234-1234-123456789abc@example.com:443?security=tls&sni=example.com#Windows%20test"
        val profile = LinkParsers.parse(link)
        assertNotNull(profile)
        assertEquals(ProfileType.VLESS, profile!!.type)
        assertEquals("example.com", profile.server)
        assertEquals(443, profile.serverPort)

        val dir = Files.createTempDirectory("chumpy-test").toFile()
        val xray = kotlinx.serialization.json.Json.parseToJsonElement(
            WindowsXrayConfigBuilder.write(profile, dir).readText()
        ).jsonObject
        assertEquals("vless", xray["outbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)

        val singBox = kotlinx.serialization.json.Json.parseToJsonElement(
            WindowsSingBoxConfigBuilder.write(profile, dir).readText()
        ).jsonObject
        assertEquals("tun", singBox["inbounds"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("vless", singBox["outbounds"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        dir.deleteRecursively()
    }
}
