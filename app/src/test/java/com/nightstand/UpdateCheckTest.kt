package com.nightstand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // org.json is Android's
@Config(sdk = [34])
class UpdateCheckTest {
    @Test fun versionOrder() {
        assertTrue(UpdateCheck.isNewer("1.2", "1.1"))
        assertTrue(UpdateCheck.isNewer("1.10", "1.9.3"))
        assertTrue(UpdateCheck.isNewer("1.1", "1.1-beta7"))
        assertFalse(UpdateCheck.isNewer("1.1", "1.1"))
        assertFalse(UpdateCheck.isNewer("1.0.6", "1.1"))
    }

    private val json = """[
      {"tag_name":"model-mobileclip-s0-v1","draft":false,"prerelease":false,"html_url":"m",
       "assets":[{"name":"mobileclip_s0_image.tflite","browser_download_url":"x"}]},
      {"tag_name":"v1.2","draft":false,"prerelease":false,"html_url":"p12",
       "assets":[{"name":"nightstand-1.2.apk","browser_download_url":"apk12"}]},
      {"tag_name":"v1.3","draft":true,"prerelease":false,"html_url":"p13","assets":[{"name":"a.apk","browser_download_url":"apk13"}]},
      {"tag_name":"v1.1","draft":false,"prerelease":false,"html_url":"p11","assets":[]}
    ]"""

    @Test fun picksNewestAppReleaseWithApk() {
        val u = UpdateCheck.pick(json, "1.1")!!
        assertEquals("1.2", u.version); assertEquals("apk12", u.apkUrl)
        assertNull(UpdateCheck.pick(json, "1.2")) // up to date; drafts and the model release ignored
    }
}
