package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Tracks a page that was consumed as a merge partner at render time (online chapters).
     * Set by [detectAndMergeAtRuntime] inside [process], read and cleared in [setImage] after
     * the image is displayed so that [PagerViewer.onStripMerged] is called only once the current
     * page is already on-screen — preventing the flash caused by an early notifyDataSetChanged().
     */
    @Volatile
    private var pageConsumedByMerge: ReaderPage? = null

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        pageConsumedByMerge = null

        val streamFn = page.stream ?: return

        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
                // Remove the consumed partner page from the adapter now that the current page's
                // image is already displayed — this avoids the flash that occurred when
                // notifyDataSetChanged() was triggered before setImage() in the old code.
                pageConsumedByMerge?.let {
                    viewer.onStripMerged(it)
                    pageConsumedByMerge = null
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        // Merge with pre-identified partner page (set during chapter loading in PagerViewerAdapter),
        // or fall back to runtime detection for online chapters whose streams weren't available yet.
        if (viewer.config.mergeSplitPages && page !is InsertPage) {
            val merged = mergeWithPartner(page, imageSource)
            if (merged != null) return merged
        }

        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    /**
     * Merges this page with its merge partner.
     *
     * If [ReaderPage.mergePartner] was pre-identified during chapter loading (downloaded chapters),
     * uses that directly.  Otherwise falls back to runtime dimension-based detection for online
     * chapters whose streams were not available at chapter-load time; in that case the consumed
     * partner page is stored in [pageConsumedByMerge] and removed from the adapter *after*
     * [setImage] completes so the current page is already on-screen before any adapter mutation.
     *
     * Returns null if the page should not be merged (caller falls through to normal rendering).
     */
    private fun mergeWithPartner(page: ReaderPage, imageSource: BufferedSource): BufferedSource? {
        // Fast path: merge partner was pre-identified at chapter-load time.
        if (page.mergePartner != null) {
            val partner = page.mergePartner!!
            val partnerStream = partner.stream ?: return null
            return try {
                val partnerSource = partnerStream().use { Buffer().readFrom(it) }
                ImageUtil.mergeVertically(imageSource, partnerSource)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to merge with pre-assigned partner page" }
                null
            }
        }

        // Slow path: runtime detection for online chapters.
        return detectAndMergeAtRuntime(page, imageSource)
    }

    /**
     * Runtime merge detection for online chapters where streams weren't available during
     * preprocessing in [PagerViewerAdapter].  Mirrors the dimension checks used by
     * [PagerViewerAdapter.tryPreprocessMergePair] but runs at render time.
     *
     * On success, sets [ReaderPage.mergePartner] on this page and stores the consumed partner
     * in [pageConsumedByMerge]; [setImage] will call [PagerViewer.onStripMerged] after the
     * current page's image is displayed to avoid adapter-mutation-induced flashing.
     */
    private fun detectAndMergeAtRuntime(page: ReaderPage, imageSource: BufferedSource): BufferedSource? {
        val (width, height) = ImageUtil.getImageDimensions(imageSource) ?: return null
        val aspectRatio = width.toFloat() / height

        val minNextAspectRatio = when {
            aspectRatio < 1.0f -> 1.5f  // PATH 1: portrait → next must be a wide strip
            aspectRatio > 1.0f -> 1.0f  // PATH 2: landscape → next must also be landscape
            else -> return null          // Exactly square — skip
        }

        val pages = page.chapter.pages ?: return null
        val pageIndex = pages.indexOf(page)
        if (pageIndex < 0) return null
        val nextPage = pages.getOrNull(pageIndex + 1) ?: return null

        val nextStream = nextPage.stream ?: return null
        val nextSource = nextStream().use { Buffer().readFrom(it) }

        val (nextWidth, nextHeight) = ImageUtil.getImageDimensions(nextSource) ?: return null

        // Next page must meet the minimum aspect ratio threshold.
        // Fall back to content-bounds check to handle pages with large black padding.
        val nextIsWide = nextWidth.toFloat() / nextHeight > minNextAspectRatio
        if (!nextIsWide) {
            val contentBounds = ImageUtil.getContentBounds(nextSource)
            val effectiveHeight = contentBounds?.height() ?: 0
            if (effectiveHeight <= 0 || nextWidth.toFloat() / effectiveHeight <= minNextAspectRatio) return null
        }

        // Widths must be approximately equal (within 10%).
        val widthRatio = width.toFloat() / nextWidth
        if (widthRatio < 0.9f || widthRatio > 1.1f) return null

        page.mergePartner = nextPage
        // Signal to setImage() that nextPage should be removed from the adapter once the
        // current page's image is already on-screen (prevents notifyDataSetChanged() flash).
        pageConsumedByMerge = nextPage
        return ImageUtil.mergeVertically(imageSource, nextSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
