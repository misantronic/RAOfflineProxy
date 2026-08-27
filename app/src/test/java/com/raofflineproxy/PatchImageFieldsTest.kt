package com.raofflineproxy

import com.raofflineproxy.proxy.patchImagePath
import com.raofflineproxy.proxy.patchImagePathFromUrl
import com.raofflineproxy.proxy.patchImageUrl
import com.raofflineproxy.proxy.putPatchImageFields
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchImageFieldsTest {

    private fun patchData(imageIcon: String? = null, imageIconUrl: String? = null) = JSONObject().apply {
        imageIcon?.let { put("ImageIcon", it) }
        imageIconUrl?.let { put("ImageIconUrl", it) }
    }

    @Test
    fun `media host url becomes a path`() {
        assertEquals(
            "/Images/052963.png",
            patchImagePathFromUrl("https://media.retroachievements.org/Images/052963.png")
        )
    }

    @Test
    fun `bare host url becomes a path`() {
        assertEquals(
            "/Images/052963.png",
            patchImagePathFromUrl("https://retroachievements.org/Images/052963.png")
        )
    }

    @Test
    fun `badge host url becomes a path`() {
        assertEquals(
            "/Badge/12345.png",
            patchImagePathFromUrl("https://i.retroachievements.org/Badge/12345.png")
        )
    }

    @Test
    fun `query string is stripped`() {
        assertEquals(
            "/Images/052963.png",
            patchImagePathFromUrl("https://media.retroachievements.org/Images/052963.png?v=2")
        )
    }

    @Test
    fun `an existing path is left alone`() {
        assertEquals("/Images/052963.png", patchImagePathFromUrl("/Images/052963.png"))
    }

    @Test
    fun `a foreign url is left alone`() {
        assertEquals("https://example.com/foo.png", patchImagePathFromUrl("https://example.com/foo.png"))
    }

    @Test
    fun `image path is normalized when ImageIcon holds a url`() {
        val data = patchData(
            imageIcon = "https://media.retroachievements.org/Images/052963.png",
            imageIconUrl = "https://media.retroachievements.org/Images/052963.png"
        )
        assertEquals("/Images/052963.png", patchImagePath(data))
    }

    @Test
    fun `image path is normalized when only ImageIconUrl is present`() {
        val data = patchData(imageIconUrl = "https://media.retroachievements.org/Images/052963.png")
        assertEquals("/Images/052963.png", patchImagePath(data))
    }

    @Test
    fun `image path is null without image fields`() {
        assertNull(patchImagePath(patchData()))
    }

    @Test
    fun `image url prefers ImageIconUrl verbatim`() {
        val data = patchData(
            imageIcon = "/Images/052963.png",
            imageIconUrl = "https://media.retroachievements.org/Images/052963.png"
        )
        assertEquals("https://media.retroachievements.org/Images/052963.png", patchImageUrl(data))
    }

    @Test
    fun `image url keeps an absolute ImageIcon when ImageIconUrl is missing`() {
        val data = patchData(imageIcon = "https://media.retroachievements.org/Images/052963.png")
        assertEquals("https://media.retroachievements.org/Images/052963.png", patchImageUrl(data))
    }

    @Test
    fun `image url absolutizes a relative ImageIcon`() {
        val data = patchData(imageIcon = "/Images/052963.png")
        assertEquals("$RA_HOST/Images/052963.png", patchImageUrl(data))
    }

    @Test
    fun `put fields writes a path and the original url`() {
        val target = JSONObject()
        putPatchImageFields(target, "https://media.retroachievements.org/Images/052963.png")
        assertEquals("/Images/052963.png", target.getString("ImageIcon"))
        assertEquals("https://media.retroachievements.org/Images/052963.png", target.getString("ImageIconUrl"))
    }
}
