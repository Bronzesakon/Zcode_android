package com.zcode.remote.core

/** Main-thread presentation ownership; a cover never changes the page's protocol. */
class HandoffCoverState {
    var visible = false
        private set
    var returning = false
        private set
    var carrierEpoch = -1L
        private set
    private var sourceDocument = ""
    private var candidateDocument = ""
    private var activeDocument = ""
    private val retiredDocuments = mutableSetOf<String>()
    private var loadStarted = false
    private var revision = 0L
    private var rejectedCarrierEpoch = -1L

    fun carrierMatched(epoch: Long, away: Boolean, document: String): Boolean {
        if (!away || document.isEmpty() || epoch <= rejectedCarrierEpoch) return false
        if (visible && carrierEpoch == epoch) return false
        carrierEpoch = epoch
        sourceDocument = document
        candidateDocument = ""
        activeDocument = ""
        retiredDocuments.clear()
        retiredDocuments.add(document)
        loadStarted = false
        returning = false
        visible = true
        revision += 1
        return true
    }

    /**
     * 前台自我接管的恢复遮罩（KICKED 落在承载窗口内、用户就在前台）。
     *
     * 与 [rendererGone] 同构：前台没有"回前台跃迁"可等，所以 `returning` 直接置真，
     * 由新文档的 pageStarted → pageReady → renderedCandidate → pagePresented 撤罩，
     * [expire] 是超时兜底。**绝不能**用 [carrierMatched] 建前台罩——它把 `returning`
     * 置假，pageStarted/pagePresented/expire 全被 `!returning` 短路，罩会永久滞留并
     * 吃掉整屏触摸（2026-11-04 审查 P0-1）。
     */
    fun foregroundRecovery(epoch: Long): Boolean {
        if (visible) return false
        visible = true
        returning = true
        carrierEpoch = epoch
        sourceDocument = ""
        activeDocument = ""
        candidateDocument = ""
        loadStarted = false
        retiredDocuments.clear()
        revision += 1
        return true
    }

    fun beginReturn(): Long? {
        if (!visible || returning) return null
        returning = true
        return carrierEpoch
    }

    fun pageStarted() {
        if (!visible || !returning) return
        loadStarted = true
        if (activeDocument.isNotEmpty()) retiredDocuments.add(activeDocument)
        activeDocument = ""
        candidateDocument = ""
        revision += 1
    }

    fun pageReady(document: String) {
        if (!visible || !returning || !loadStarted || document.isEmpty() || document in retiredDocuments) return
        activeDocument = document
    }

    /** Any fresh logo or status frame gives presentation back to the page. */
    fun renderedCandidate(document: String): Long? {
        if (!visible || !returning || !loadStarted || document.isEmpty() || document != activeDocument) return null
        candidateDocument = document
        return revision
    }

    fun pagePresented(document: String, ticket: Long): Boolean {
        if (!visible || !returning || ticket != revision || document != candidateDocument) return false
        return clear()
    }

    fun pageFailed(document: String, reportedForeground: Boolean): Boolean {
        if (!visible || document.isEmpty()) return false
        if (document != sourceDocument && document in retiredDocuments) return false
        // The old document can deliver its own background KICK after return.
        if (document == sourceDocument && !reportedForeground) return false
        return clear()
    }

    fun carrierKicked(epoch: Long): Boolean {
        rejectedCarrierEpoch = maxOf(rejectedCarrierEpoch, epoch)
        if (carrierEpoch != epoch) return false
        return clear()
    }

    /**
     * The renderer died and a fresh WebView is being built (`onRenderProcessGone`).
     *
     * Same cover, different cause: there is no carrier epoch to wait for, so it is
     * pinned to -1 and released by the first frame of the new document exactly like
     * a handback ([pageStarted] → [pageReady] → [renderedCandidate] → [pagePresented]).
     * [expire] with -1 is the timeout path.
     */
    fun rendererGone(): Boolean {
        if (visible) {
            // 已有罩（多半是 returning=false 的承载罩，审查 Kotlin P2-8）：转成"可由
            // 新文档首帧撤销"的形态，否则重建后的释放路径全被 !returning 短路、罩永挂。
            // epoch 一并钉回 -1（渲染恢复没有承载代次可等，expire(-1) 才能兜底）。
            returning = true
            carrierEpoch = -1L
            sourceDocument = ""
            activeDocument = ""
            candidateDocument = ""
            loadStarted = false
            retiredDocuments.clear()
            revision += 1
            return true
        }
        visible = true
        returning = true
        carrierEpoch = -1L
        sourceDocument = ""
        activeDocument = ""
        candidateDocument = ""
        loadStarted = false
        retiredDocuments.clear()
        revision += 1
        return true
    }

    fun expire(epoch: Long): Boolean {
        if (!visible || !returning || carrierEpoch != epoch) return false
        return clear()
    }

    fun clear(): Boolean {
        if (!visible) return false
        visible = false
        returning = false
        candidateDocument = ""
        activeDocument = ""
        retiredDocuments.clear()
        revision += 1
        return true
    }

    companion object {
        const val RETURN_TIMEOUT_MS = 15_000L
    }
}
