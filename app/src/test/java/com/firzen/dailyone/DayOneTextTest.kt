package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class DayOneTextTest {
    private val id = "8E10D5762A9A4ADE9B280630873AD338"
    @Test fun removesAttachmentPlaceholdersAndEmptyParagraphs() {
        assertEquals("正文\n\n后文", cleanDayOneText("![](dayone-moment://$id)\n\n正文\n\n![](dayone-moment:///$id)\n\n后文\n\n![](dayon-moment://$id)"))
    }
    @Test fun removesVideoPlaceholdersWithoutChangingOtherVideoText() {
        assertEquals("正文", cleanDayOneText("正文\n\n![](dayone-moment:/video/$id)"))
        assertEquals("视频说明", cleanDayOneText("![视频说明](dayone-moment:/video/$id)"))
        assertEquals("", cleanDayOneText("![](dayone-moment://video/$id)"))
        val normal = "/video\n![视频](https://example.com/video/$id)"
        assertEquals(normal, cleanDayOneText(normal))
    }
    @Test fun preservesCaptionsAndNormalMarkdownLinks() {
        assertEquals("照片说明", cleanDayOneText("![照片说明](dayone-moment://$id)"))
        val ordinary = "# 标题\n\n![网络图](https://example.com/photo.jpg)\n\n\n[链接](https://example.com)\n"
        assertEquals(ordinary, cleanDayOneText(ordinary))
    }
    @Test fun cleanupIsIdempotentAndPreservesSurroundingText() {
        val cleaned = cleanDayOneText("前![](dayone-moment://$id)后")
        assertEquals("前后", cleaned); assertEquals(cleaned, cleanDayOneText(cleaned))
    }
}
