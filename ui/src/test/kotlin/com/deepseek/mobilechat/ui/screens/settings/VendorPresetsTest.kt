package com.deepseek.mobilechat.ui.screens.settings

import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM coverage for the vendor table and the "vendor → draft" mapping.
 * No Compose/Robolectric here: the editor's rendering is verified by the build
 * only (there is no emulator on this machine), but everything that decides a
 * value is pure and covered below.
 */
class VendorPresetsTest {

    @Test
    fun `table is complete`() {
        assertEquals(9, VendorPresets.all.size)
        assertFalse(VendorPresets.all.any { it.id == VendorPresets.CUSTOM_ID })
        assertEquals(VendorPresets.CUSTOM_ID, VendorPresets.custom.id)
    }

    @Test
    fun `every preset has a usable, unique identity`() {
        val ids = VendorPresets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        VendorPresets.all.forEach { preset ->
            assertTrue("${preset.id} name", preset.displayName.isNotBlank())
            assertTrue("${preset.id} baseUrl", preset.baseUrl.isNotBlank())
            assertTrue("${preset.id} model", preset.defaultModelId.isNotBlank())
            assertTrue("${preset.id} keyHint", preset.keyHint.isNotBlank())
            assertTrue("${preset.id} console", preset.consoleUrl == null || isHttpUrl(preset.consoleUrl))
            assertTrue("${preset.id} baseUrl is http(s)", isHttpUrl(preset.baseUrl))
        }
    }

    @Test
    fun `every preset protocol matches its endpoint`() {
        VendorPresets.all.forEach { preset ->
            assertTrue(
                "${preset.id}: ${preset.protocol} vs ${preset.baseUrl}",
                isProtocolConsistentWithUrl(preset.protocol, preset.baseUrl),
            )
        }
    }

    @Test
    fun `protocols are the adapters the endpoints actually speak`() {
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.deepSeek.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.openAi.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.openRouter.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.moonshot.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.siliconFlow.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.zhipu.protocol)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, VendorPresets.dashScope.protocol)
        assertEquals(ProviderProtocol.ANTHROPIC, VendorPresets.anthropic.protocol)
        assertEquals(ProviderProtocol.GEMINI, VendorPresets.gemini.protocol)
    }

    @Test
    fun `deepseek keeps the shipped endpoint and model`() {
        assertEquals("https://api.deepseek.com", VendorPresets.deepSeek.baseUrl)
        assertEquals("deepseek-flash", VendorPresets.deepSeek.defaultModelId)
    }

    @Test
    fun `vendor to draft fills every editable field`() {
        VendorPresets.all.forEach { preset ->
            val draft = preset.toDraft(apiKey = "sk-test")
            assertEquals(preset.displayName, draft.name)
            assertEquals(preset.protocol, draft.protocol)
            assertEquals(preset.baseUrl, draft.baseUrl)
            assertEquals(preset.defaultModelId, draft.defaultModelId)
            assertEquals("sk-test", draft.apiKey)
            assertTrue("${preset.id} draft validates", validateProviderDraft(draft).isValid)
            assertNull(
                "${preset.id} protocol warning",
                validateProviderDraft(draft).protocolWarning,
            )
        }
    }

    @Test
    fun `custom has no invented defaults`() {
        assertEquals("", VendorPresets.custom.baseUrl)
        assertEquals("", VendorPresets.custom.defaultModelId)
        assertNull(VendorPresets.custom.consoleUrl)
    }

    @Test
    fun `match finds the preset behind a saved profile`() {
        val profile = profile(baseUrl = "https://api.deepseek.com/", protocol = ProviderProtocol.OPENAI_COMPATIBLE)
        assertEquals(VendorPresets.deepSeek.id, VendorPresets.match(profile)?.id)
    }

    @Test
    fun `match returns null for an edited endpoint or the wrong protocol`() {
        assertNull(
            VendorPresets.match(
                profile(baseUrl = "https://my-gateway.example.com/v1", protocol = ProviderProtocol.OPENAI_COMPATIBLE),
            ),
        )
        assertNull(
            VendorPresets.match(
                profile(baseUrl = "https://api.deepseek.com", protocol = ProviderProtocol.ANTHROPIC),
            ),
        )
        assertNull(VendorPresets.match(null))
    }

    @Test
    fun `valid draft has no errors`() {
        val validation = validateProviderDraft(
            ProviderDraft(
                name = "DeepSeek",
                protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com",
                apiKey = "sk-test",
                defaultModelId = "deepseek-flash",
            ),
        )
        assertTrue(validation.isValid)
        assertNull(validation.nameError)
        assertNull(validation.baseUrlError)
        assertNull(validation.modelError)
        assertNull(validation.protocolWarning)
    }

    @Test
    fun `blank fields block saving`() {
        val blank = validateProviderDraft(
            ProviderDraft(
                name = " ",
                protocol = ProviderProtocol.OPENAI_COMPATIBLE,
                baseUrl = "",
                apiKey = "",
                defaultModelId = "",
            ),
        )
        assertFalse(blank.isValid)
        assertNotNull(blank.nameError)
        assertNotNull(blank.baseUrlError)
        assertNotNull(blank.modelError)
    }

    @Test
    fun `a scheme-less or malformed url is rejected`() {
        assertFalse(isHttpUrl("api.deepseek.com"))
        assertFalse(isHttpUrl("ftp://api.deepseek.com"))
        assertFalse(isHttpUrl("https://"))
        assertFalse(isHttpUrl("https://api deepseek.com"))
        assertTrue(isHttpUrl("http://localhost:8080/v1"))
        assertTrue(isHttpUrl("https://api.deepseek.com"))
    }

    @Test
    fun `mismatched protocol is a warning, not a block`() {
        val validation = validateProviderDraft(
            ProviderDraft(
                name = "Proxy",
                protocol = ProviderProtocol.ANTHROPIC,
                baseUrl = "https://api.deepseek.com",
                apiKey = "",
                defaultModelId = "deepseek-flash",
            ),
        )
        assertTrue(validation.isValid)
        assertNotNull(validation.protocolWarning)
        assertFalse(isProtocolConsistentWithUrl(ProviderProtocol.ANTHROPIC, "https://api.deepseek.com"))
        assertTrue(isProtocolConsistentWithUrl(ProviderProtocol.ANTHROPIC, "https://api.anthropic.com"))
        assertTrue(
            isProtocolConsistentWithUrl(
                ProviderProtocol.GEMINI,
                "https://generativelanguage.googleapis.com",
            ),
        )
    }

    @Test
    fun `host extraction ignores path query and port`() {
        assertEquals("api.example.com", hostOfUrl("https://api.example.com:8443/v1?x=1#y"))
        assertEquals("", hostOfUrl("not-a-url"))
    }

    private fun profile(baseUrl: String, protocol: ProviderProtocol): ProviderProfile = ProviderProfile(
        id = ProviderId("p1"),
        name = "Saved",
        protocol = protocol,
        baseUrl = baseUrl,
        secretRef = "provider.p1",
        defaultModelId = null,
        createdAt = 0L,
    )
}
