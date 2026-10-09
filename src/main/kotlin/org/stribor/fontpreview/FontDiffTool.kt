package org.stribor.fontpreview

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.PrevNextDifferenceIterable
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.Locale

class FontDiffTool : FrameDiffTool {
    override fun getName() = "Font comparison"
    override fun canShow(context: DiffContext, request: DiffRequest): Boolean = accepts(request)
    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        FontDiffViewer(context, request as ContentDiffRequest)

    companion object {
        fun accepts(request: DiffRequest): Boolean {
            val contents = (request as? ContentDiffRequest)?.contents ?: return false
            return contents.size == 2 && contents.any { it is FileContent } && contents.all { content ->
                content is EmptyContent || content is FileContent && !content.file.isDirectory &&
                    content.file.extension?.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc", "otc", "woff")
            }
        }
    }
}

/** Use IntelliJ's rediff lifecycle for background work, cancellation and stale-result suppression. */
private class FontDiffViewer(context: DiffContext, request: ContentDiffRequest) : DiffViewerBase(context, request) {
    private val preferences = FontDiffPreferences.getInstance()
    private val panel = FontDiffPanel(request.contentTitles, this, preferences.snapshot(), preferences::remember)
    @Volatile private var cached: List<FontDocument?>? = null
    @Volatile private var leftFace = 0
    @Volatile private var rightFace = 0
    private val files = request.contents.map { (it as? FileContent)?.file }
    private val connection = ApplicationManager.getApplication().messageBus.connect(this)
    private val navigation = object : PrevNextDifferenceIterable {
        override fun canGoPrev() = panel.canGoChange(false)
        override fun canGoNext() = panel.canGoChange(true)
        override fun goPrev() = panel.goChange(false)
        override fun goNext() = panel.goChange(true)
    }

    init {
        panel.onFaceChange = {
            leftFace = panel.leftFaceIndex; rightFace = panel.rightFaceIndex
            scheduleRediff()
        }
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file in files.filterNotNull() }) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!isDisposed) { cached = null; scheduleRediff() }
                    }
                }
            }
        })
    }

    override fun getComponent() = panel
    override fun getPreferredFocusedComponent() = panel.leftGrid
    override fun getDifferenceIterable() = navigation
    override fun onBeforeRediff() { panel.showLoading() }
    override fun performRediff(indicator: ProgressIndicator): Runnable {
        try {
            val documents = cached ?: files.map { file ->
                indicator.checkCanceled()
                file?.let {
                    require(it.isValid) { "Font file is no longer available." }
                    require(it.length <= FontDocument.MAX_BYTES) { "Fonts larger than 128 MB are not supported." }
                    FontDocument.load(it.contentsToByteArray(), indicator::checkCanceled)
                }
            }
            val left = documents[0]; val right = documents[1]
            val a = left?.faces?.get(leftFace.coerceIn(0, left.faces.lastIndex))
            val b = right?.faces?.get(rightFace.coerceIn(0, right.faces.lastIndex))
            val model = FontComparison.compare(a, b, indicator::checkCanceled)
            indicator.checkCanceled()
            return Runnable { cached = documents; panel.install(left, right, model) }
        } catch (canceled: ProcessCanceledException) {
            throw canceled
        } catch (error: Exception) {
            return Runnable { cached = null; panel.showError(error.message ?: error.javaClass.simpleName) }
        }
    }

    override fun onDispose() {
        connection.disconnect(); cached = null; panel.clear()
        super.onDispose()
    }
}
