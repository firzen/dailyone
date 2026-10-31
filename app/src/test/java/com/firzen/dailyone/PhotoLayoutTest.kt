package com.firzen.dailyone

import org.junit.Assert.*
import org.junit.Test

class PhotoLayoutTest {
    @Test fun threePortraitsUseHeroComposition() {
        assertTrue(photoLayout(listOf(.7f, .75f, .8f)) is PhotoLayout.PortraitHero)
    }
    @Test fun landscapesStayFullWidthLikeReference() {
        val layout = photoLayout(listOf(1.78f, 1.78f, 1.78f)) as PhotoLayout.Rows
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), layout.rows.map { it.indices })
    }
    @Test fun mixedCollectionKeepsAllPhotosInOrder() {
        val ratios = listOf(.75f, 1.33f, 1.33f, 1.33f, .75f, 1.33f, .8f, .8f)
        val layout = photoLayout(ratios) as PhotoLayout.Rows
        assertEquals(ratios.indices.toList(), layout.rows.flatMap { it.indices })
        assertTrue(layout.rows.all { it.indices.size in 1..3 })
    }
    @Test fun unknownOrBrokenDimensionsAreSafe() {
        val layout = photoLayout(listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, -1f)) as PhotoLayout.Rows
        assertTrue(layout.rows.flatMap { it.ratios }.all { it.isFinite() && it > 0f })
    }
    @Test fun emptyCollectionProducesNoRows() {
        assertTrue((photoLayout(emptyList()) as PhotoLayout.Rows).rows.isEmpty())
    }
    @Test fun noPhotoIsLostForAnySupportedCollectionSize() {
        for (count in 1..20) {
            val layout = photoLayout(List(count) { if (it % 2 == 0) .67f else 1.78f })
            val indices = when (layout) {
                is PhotoLayout.PortraitHero -> layout.indices
                is PhotoLayout.Rows -> layout.rows.flatMap { it.indices }
            }
            assertEquals((0 until count).toList(), indices)
        }
    }
}
