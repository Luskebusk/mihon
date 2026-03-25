package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    var items: MutableList<Any> = mutableListOf()
        private set

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: MutableMap<Int, InsertPage> = mutableMapOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition
        chapters.prevChapter?.pages?.let { prevPages ->
            if (viewer.config.mergeSplitPages) {
                val mutablePrev = prevPages.toMutableList()
                val consumed = preprocessMergePairs(mutablePrev)
                mutablePrev.removeAll(consumed)
                newItems.addAll(mutablePrev)
            } else {
                newItems.addAll(prevPages)
            }
        }

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        var insertPageLastPage: InsertPage? = null

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            val pages = currPages.toMutableList()

            val lastPage = pages.last()

            // Insert preprocessed pages into current page list
            preprocessed.keys.sortedDescending()
                .forEach { key ->
                    if (lastPage.index == key) {
                        insertPageLastPage = preprocessed[key]
                    }
                    preprocessed[key]?.let { pages.add(key + 1, it) }
                }

            if (viewer.config.mergeSplitPages) {
                val consumed = preprocessMergePairs(pages)
                pages.removeAll(consumed)
            }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        chapters.nextChapter?.pages?.let { nextPages ->
            if (viewer.config.mergeSplitPages) {
                val mutableNext = nextPages.toMutableList()
                val consumed = preprocessMergePairs(mutableNext)
                mutableNext.removeAll(consumed)
                newItems.addAll(mutableNext)
            } else {
                newItems.addAll(nextPages)
            }
        }

        // Resets double-page splits, else insert pages get misplaced
        items.filterIsInstance<InsertPage>().also { items.removeAll(it) }

        if (viewer is R2LPagerViewer) {
            newItems.reverse()
        }

        preprocessed = mutableMapOf()
        items = newItems
        notifyDataSetChanged()

        // Will skip insert page otherwise
        if (insertPageLastPage != null) {
            viewer.moveToPage(insertPageLastPage)
        }
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = items.indexOf(currentPage)

        // Put aside preprocessed pages for next chapter so they don't get removed when changing chapter
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            preprocessed[newPage.index] = newPage
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && items[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (items[placeAtIndex] is InsertPage) {
            return
        }

        items.add(placeAtIndex, newPage)

        notifyDataSetChanged()
    }

    fun cleanupPageSplit() {
        val insertPages = items.filterIsInstance<InsertPage>()
        items.removeAll(insertPages)
        notifyDataSetChanged()
    }

    /**
     * Removes [stripPage] from the adapter after a runtime merge (online chapters).
     * Called from the UI thread by [PagerViewer.onStripMerged] *after* the merging page's image
     * has been displayed, so [notifyDataSetChanged] no longer causes a flash on the current page.
     */
    fun onStripMerged(stripPage: ReaderPage) {
        val index = items.indexOf(stripPage)
        if (index != -1) {
            items.removeAt(index)
            notifyDataSetChanged()
        }
    }

    /**
     * Scans [pages] for consecutive pairs that should be merged vertically (per the "Merge split
     * pages" setting).  For each qualifying pair, sets [ReaderPage.mergePartner] on the first page
     * and returns the second pages (the "consumed" ones) so the caller can remove them from the
     * adapter item list before the ViewPager ever sees them.
     *
     * Only pages whose streams are already available are considered, so this is safe to call at
     * chapter-load time even for chapters that are still being downloaded (unloaded pages are
     * simply left alone and will not be merged).
     */
    private fun preprocessMergePairs(pages: MutableList<ReaderPage>): Set<ReaderPage> {
        val consumed = mutableSetOf<ReaderPage>()
        var i = 0
        while (i < pages.size) {
            val page = pages[i]
            if (page is InsertPage || page in consumed) {
                i++
                continue
            }
            if (tryPreprocessMergePair(page, pages, i, consumed)) {
                i += 2
            } else {
                i++
            }
        }
        return consumed
    }

    /**
     * Attempts to identify [page] (at [index] in [pages]) as the first of a merge pair.
     * If successful, sets [page.mergePartner][ReaderPage.mergePartner] and adds the second page to
     * [consumed], then returns true.  Returns false if the pair does not qualify.
     */
    private fun tryPreprocessMergePair(
        page: ReaderPage,
        pages: List<ReaderPage>,
        index: Int,
        consumed: MutableSet<ReaderPage>,
    ): Boolean {
        val nextPage = pages.getOrNull(index + 1) ?: return false
        if (nextPage is InsertPage || nextPage in consumed) return false

        val stream = page.stream ?: return false
        val nextStream = nextPage.stream ?: return false

        return try {
            val source = stream().use { Buffer().readFrom(it) }
            val (width, height) = ImageUtil.getImageDimensions(source) ?: return false
            val aspectRatio = width.toFloat() / height

            val minNextAspectRatio = when {
                aspectRatio < 1.0f -> 1.5f  // PATH 1: portrait → next must be a wide strip
                aspectRatio > 1.0f -> 1.0f  // PATH 2: landscape → next must also be landscape
                else -> return false         // Exactly square — skip
            }

            val nextSource = nextStream().use { Buffer().readFrom(it) }
            val (nextWidth, nextHeight) = ImageUtil.getImageDimensions(nextSource) ?: return false

            // Next page must meet the minimum aspect ratio threshold.
            // Fall back to content-bounds check to handle pages with large black padding.
            val nextIsWide = nextWidth.toFloat() / nextHeight > minNextAspectRatio
            if (!nextIsWide) {
                val contentBounds = ImageUtil.getContentBounds(nextSource)
                val effectiveHeight = contentBounds?.height() ?: 0
                if (effectiveHeight <= 0 || nextWidth.toFloat() / effectiveHeight <= minNextAspectRatio) return false
            }

            // Widths must be approximately equal (within 10%).
            val widthRatio = width.toFloat() / nextWidth
            if (widthRatio < 0.9f || widthRatio > 1.1f) return false

            page.mergePartner = nextPage
            consumed.add(nextPage)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}
