package com.zcode.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffCoverStateTest {
    private fun returning(): HandoffCoverState = HandoffCoverState().apply {
        carrierMatched(1L, true, "old")
        beginReturn()
        pageStarted()
    }

    @Test
    fun `only a background pairing with a known page can cover`() {
        val state = HandoffCoverState()
        assertFalse(state.carrierMatched(1L, false, "old"))
        assertFalse(state.carrierMatched(1L, true, ""))
        assertFalse(state.visible)
        assertTrue(state.carrierMatched(1L, true, "old"))
        assertFalse(state.carrierMatched(1L, true, "old"))
    }

    @Test
    fun `fresh first frame returns ownership without waiting for workspace connection`() {
        val state = returning()
        state.pageReady("old")
        assertNull(state.renderedCandidate("old"))
        assertNull(state.renderedCandidate("new"))
        state.pageReady("new")
        assertTrue(state.visible)
        val ticket = state.renderedCandidate("new")!!
        assertTrue(state.visible)
        assertTrue(state.pagePresented("new", ticket))
        assertFalse(state.visible)
    }

    @Test
    fun `a later navigation invalidates the prior visual commit and document`() {
        val state = returning()
        state.pageReady("first")
        val oldTicket = state.renderedCandidate("first")!!
        state.pageStarted()
        state.pageReady("first")
        assertNull(state.renderedCandidate("first"))
        state.pageReady("second")
        val ticket = state.renderedCandidate("second")!!
        assertFalse(state.pagePresented("first", oldTicket))
        assertFalse(state.pageFailed("first", true))
        assertTrue(state.visible)
        assertTrue(state.pagePresented("second", ticket))
    }

    @Test
    fun `late self kick stays covered while a new foreground kick is revealed`() {
        val state = returning()
        assertFalse(state.pageFailed("old", false))
        assertTrue(state.visible)
        state.pageReady("new")
        val ticket = state.renderedCandidate("new")!!
        assertTrue(state.pageFailed("new", true))
        assertFalse(state.pagePresented("new", ticket))
        assertFalse(state.visible)
    }

    @Test
    fun `foreground failure on the source document is also revealed`() {
        val state = returning()
        assertTrue(state.pageFailed("old", true))
        assertFalse(state.visible)
    }

    @Test
    fun `unattributed prior kick prevents masking that background window`() {
        val state = HandoffCoverState()
        assertFalse(state.carrierKicked(1L))
        assertFalse(state.carrierMatched(1L, true, "old"))
        assertTrue(state.carrierMatched(2L, true, "new"))
    }

    @Test
    fun `native kick reveals the conflict and prevents covering it again`() {
        val state = HandoffCoverState()
        state.carrierMatched(1L, true, "old")
        assertTrue(state.carrierKicked(1L))
        assertFalse(state.carrierMatched(1L, true, "old"))
        assertFalse(state.visible)
    }

    @Test
    fun `old carrier callbacks and timeout cannot cancel a new window`() {
        val state = returning()
        state.clear()
        state.carrierMatched(2L, true, "new")
        state.beginReturn()
        assertFalse(state.carrierKicked(1L))
        assertFalse(state.expire(1L))
        assertTrue(state.visible)
    }

    @Test
    fun `timeout starts only after return and dismisses instead of reloading`() {
        val state = HandoffCoverState()
        state.carrierMatched(1L, true, "old")
        assertFalse(state.expire(1L))
        state.beginReturn()
        assertNull(state.beginReturn())
        assertTrue(state.expire(1L))
        assertFalse(state.visible)
        assertFalse(state.expire(1L))
    }

    @Test
    fun `renderer death covers without a carrier and releases on the fresh document`() {
        val state = HandoffCoverState()
        assertTrue(state.rendererGone())
        assertTrue(state.visible)
        // 已有罩时再死一次渲染器（审查 Kotlin P2-8）：必须转成可撤形态并返回 true，
        // 否则重建后的释放路径全被 !returning 短路（与 P0-1 同机理）。
        assertTrue("已有罩也要接管释放", state.rendererGone())
        state.pageStarted()
        state.pageReady("rebuilt")
        val ticket = state.renderedCandidate("rebuilt")!!
        assertTrue(state.pagePresented("rebuilt", ticket))
        assertFalse(state.visible)
    }

    @Test
    fun `a stale carrier cover converts to a renderer cover and can time out`() {
        val state = returning() // returning=false 的承载罩形态
        // beginReturn 之后的形态是 returning=true；再造一个 returning=false 的：
        val covered = HandoffCoverState().apply { carrierMatched(1L, true, "old") }
        assertTrue(covered.visible)
        assertFalse(covered.returning)
        assertTrue(covered.rendererGone())
        assertTrue("rendererGone 必须把 returning 置真", covered.returning)
        assertTrue("expire(-1) 必须能兜底", covered.expire(-1L))
        assertFalse(covered.visible)
    }

    @Test
    fun `renderer cover is released by the timeout even if no frame arrives`() {
        val state = HandoffCoverState()
        assertTrue(state.rendererGone())
        // -1 是渲染恢复专用的 epoch（没有承载代次可等）。
        assertTrue(state.expire(-1L))
        assertFalse(state.visible)
    }

    @Test
    fun `foreground self-kick cover releases on the fresh document or the timeout`() {
        // 审查 P0-1 的形状：前台 KICK 若用 carrierMatched(away=true) 建罩，returning 为假，
        // pageStarted/pagePresented/expire 全部空转，罩永久滞留。foregroundRecovery 必须可撤。
        val state = HandoffCoverState()
        assertTrue(state.foregroundRecovery(7L))
        assertTrue(state.visible)
        assertFalse("already up", state.foregroundRecovery(7L))
        state.pageStarted()
        state.pageReady("fresh")
        val ticket = state.renderedCandidate("fresh")!!
        assertTrue(state.pagePresented("fresh", ticket))
        assertFalse(state.visible)

        // 另一条出路：新文档一直不出帧时，超时也必须能撤罩。
        val stalled = HandoffCoverState()
        assertTrue(stalled.foregroundRecovery(8L))
        assertTrue(stalled.expire(8L))
        assertFalse(stalled.visible)
    }
}
