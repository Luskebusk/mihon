package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /** The next page to merge with when "Merge split pages" is enabled. */
    var mergePartner: ReaderPage? = null

    /**
     * Whether merge-pair evaluation has completed for this page (with both image streams
     * available). Prevents repeated image decoding across adapter reloads and render passes.
     */
    var mergeChecked: Boolean = false
}
