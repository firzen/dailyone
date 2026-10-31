package com.firzen.dailyone

/** Layout uses the imported image dimensions; source files are never cropped. */
data class PhotoRow(val indices: List<Int>, val ratios: List<Float>)
sealed interface PhotoLayout {
    data class Rows(val rows: List<PhotoRow>) : PhotoLayout
    data class PortraitHero(val indices: List<Int>) : PhotoLayout
}
fun photoLayout(aspects: List<Float>): PhotoLayout {
    val ratios = aspects.map { if (it.isFinite() && it > 0f) it.coerceIn(.5f, 2.4f) else 1f }
    if (ratios.size == 3 && ratios.all { it < .95f }) return PhotoLayout.PortraitHero(ratios.indices.toList())
    if (ratios.all { it >= 1.2f }) return PhotoLayout.Rows(ratios.mapIndexed { i, r -> PhotoRow(listOf(i), listOf(r)) })
    val rows = mutableListOf<PhotoRow>()
    var index = 0
    while (index < ratios.size) {
        val start = index
        var sum = 0f
        do { sum += ratios[index++]; } while (index < ratios.size && index - start < 3 && sum < 2.5f)
        // Avoid a solitary portrait at the end when the preceding row can share it.
        if (ratios.size - index == 1 && ratios.last() < 1f && index - start == 3) index--
        val indices = (start until index).toList()
        rows += PhotoRow(indices, indices.map { ratios[it] })
    }
    return PhotoLayout.Rows(rows)
}
