// ---------------------------------------------------------------------------
//  由 design/tokens/colors.json 机械生成的 Web 设计令牌（Android / Compose 侧）
//
//  数据来源：design/tokens/colors.json
//    - 上游快照：Reference_web/3.15.3/assets/index-BD2BL4Cc.css
//    - 上游 CSS sha256：40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f
//    - colors.json sha256：f1faa790d59b82faa73296233a8f902211134420ae2c5082907702b1f962f54c
//    - 快照版本：remote v4 / 3.15.3
//  生成时间：2026-10-17（默认主题口径重生成）
//
//  【不要直接改这个文件】
//  这里的每个值都是从 Web 快照逐条抽取出来的令牌，改值请改 design/tokens/*.json
//  之后重新生成，否则会和网页端一比一复刻的目标脱节。
//
//  取值口径：
//    - 明暗两套取「默认主题」口径：只应用 `:root,:host`（含 @layer / @supports）
//      与 `.dark`，**排除** `.theme-zai-*` 皮肤；`var(--color-*)` 引用按同一口径
//      递归解析（不能直接用 colors.json 里已按 zai 级联算好的 hex）。
//    - 有 27 个令牌在默认亮色主题里没有定义（只存在于 `.dark` 与 zai 皮肤，
//      如 tab / command-node / context-breakdown），这些令牌的亮色值保留 zai-light
//      的取值作为兜底；暗色值仍按默认 `.dark` 解析。
//    - 带 alpha 的令牌保留 alpha（ARGB 高位）。
//
//  构造方式说明（与「data class 181 个 Color 字段」的直觉写法不同，原因见下）：
//    Compose 的 Color 是 @JvmInline value class Color(val value: ULong)，在 JVM 上按
//    long 传参占 2 个参数槽；181 个 Color 参数 = 362 槽，超过 JVM 方法描述符 255 槽
//    上限。Kotlin 编译器不报错，但生成的 class 文件是非法的，运行期加载会抛
//    java.lang.ClassFormatError: Too many arguments in method signature。
//    因此主构造器收 181 个 ARGB Int（各占 1 槽），对外仍然暴露 181 个 Color 属性。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ===========================================================================
//  ZemoteColors —— 语义令牌（colors.json tokens.semantic，181 条）
// ===========================================================================

@Immutable
data class ZemoteColors(
    /** --color-accent */
    private val accentArgb: Int,
    /** --color-background */
    private val backgroundArgb: Int,
    /** --color-background-alt */
    private val backgroundAltArgb: Int,
    /** --color-background-win-alt */
    private val backgroundWinAltArgb: Int,
    /** --color-border */
    private val borderArgb: Int,
    /** --color-border-hover */
    private val borderHoverArgb: Int,
    /** --color-brand */
    private val brandArgb: Int,
    /** --color-card */
    private val cardArgb: Int,
    /** --color-card-border */
    private val cardBorderArgb: Int,
    /** --color-card-selected */
    private val cardSelectedArgb: Int,
    /** --color-command-node */
    private val commandNodeArgb: Int,
    /** --color-command-node-foreground */
    private val commandNodeForegroundArgb: Int,
    /** --color-command-node-hover */
    private val commandNodeHoverArgb: Int,
    /** --color-context-breakdown-1 */
    private val contextBreakdown1Argb: Int,
    /** --color-context-breakdown-2 */
    private val contextBreakdown2Argb: Int,
    /** --color-context-breakdown-3 */
    private val contextBreakdown3Argb: Int,
    /** --color-context-breakdown-4 */
    private val contextBreakdown4Argb: Int,
    /** --color-context-breakdown-5 */
    private val contextBreakdown5Argb: Int,
    /** --color-context-breakdown-6 */
    private val contextBreakdown6Argb: Int,
    /** --color-context-breakdown-7 */
    private val contextBreakdown7Argb: Int,
    /** --color-destructive */
    private val destructiveArgb: Int,
    /** --color-destructive-foreground */
    private val destructiveForegroundArgb: Int,
    /** --color-diff-added */
    private val diffAddedArgb: Int,
    /** --color-diff-added-foreground */
    private val diffAddedForegroundArgb: Int,
    /** --color-diff-removed */
    private val diffRemovedArgb: Int,
    /** --color-diff-removed-foreground */
    private val diffRemovedForegroundArgb: Int,
    /** --color-feedback-privacy-hint */
    private val feedbackPrivacyHintArgb: Int,
    /** --color-file-node */
    private val fileNodeArgb: Int,
    /** --color-file-node-foreground */
    private val fileNodeForegroundArgb: Int,
    /** --color-file-node-hover */
    private val fileNodeHoverArgb: Int,
    /** --color-find-highlight */
    private val findHighlightArgb: Int,
    /** --color-find-highlight-active */
    private val findHighlightActiveArgb: Int,
    /** --color-foreground */
    private val foregroundArgb: Int,
    /** --color-foreground-inverse */
    private val foregroundInverseArgb: Int,
    /** --color-foreground-subtle */
    private val foregroundSubtleArgb: Int,
    /** --color-foreground-subtlest */
    private val foregroundSubtlestArgb: Int,
    /** --color-git-added */
    private val gitAddedArgb: Int,
    /** --color-git-deleted */
    private val gitDeletedArgb: Int,
    /** --color-git-descendant */
    private val gitDescendantArgb: Int,
    /** --color-git-ignored */
    private val gitIgnoredArgb: Int,
    /** --color-git-modified */
    private val gitModifiedArgb: Int,
    /** --color-git-none */
    private val gitNoneArgb: Int,
    /** --color-git-renamed */
    private val gitRenamedArgb: Int,
    /** --color-git-untracked */
    private val gitUntrackedArgb: Int,
    /** --color-header */
    private val headerArgb: Int,
    /** --color-highspeed-accent */
    private val highspeedAccentArgb: Int,
    /** --color-highspeed-composer-border-hover */
    private val highspeedComposerBorderHoverArgb: Int,
    /** --color-highspeed-composer-border-idle */
    private val highspeedComposerBorderIdleArgb: Int,
    /** --color-highspeed-composer-border-pre-animation */
    private val highspeedComposerBorderPreAnimationArgb: Int,
    /** --color-highspeed-composer-surface */
    private val highspeedComposerSurfaceArgb: Int,
    /** --color-highspeed-glow-01 */
    private val highspeedGlow01Argb: Int,
    /** --color-highspeed-glow-04 */
    private val highspeedGlow04Argb: Int,
    /** --color-highspeed-glow-15 */
    private val highspeedGlow15Argb: Int,
    /** --color-highspeed-glow-26 */
    private val highspeedGlow26Argb: Int,
    /** --color-highspeed-model-sweep-highlight */
    private val highspeedModelSweepHighlightArgb: Int,
    /** --color-highspeed-particle */
    private val highspeedParticleArgb: Int,
    /** --color-highspeed-particle-highlight */
    private val highspeedParticleHighlightArgb: Int,
    /** --color-highspeed-particle-highlight-glow */
    private val highspeedParticleHighlightGlowArgb: Int,
    /** --color-highspeed-share-card-view-border */
    private val highspeedShareCardViewBorderArgb: Int,
    /** --color-highspeed-share-card-view-end */
    private val highspeedShareCardViewEndArgb: Int,
    /** --color-highspeed-share-card-view-start */
    private val highspeedShareCardViewStartArgb: Int,
    /** --color-highspeed-share-control-hover */
    private val highspeedShareControlHoverArgb: Int,
    /** --color-highspeed-share-dialog-bars-baseline */
    private val highspeedShareDialogBarsBaselineArgb: Int,
    /** --color-highspeed-share-dialog-bars-end */
    private val highspeedShareDialogBarsEndArgb: Int,
    /** --color-highspeed-share-dialog-bars-mid */
    private val highspeedShareDialogBarsMidArgb: Int,
    /** --color-highspeed-share-dialog-bars-start */
    private val highspeedShareDialogBarsStartArgb: Int,
    /** --color-highspeed-share-dialog-benefit-text */
    private val highspeedShareDialogBenefitTextArgb: Int,
    /** --color-highspeed-share-dialog-border */
    private val highspeedShareDialogBorderArgb: Int,
    /** --color-highspeed-share-dialog-control-hover */
    private val highspeedShareDialogControlHoverArgb: Int,
    /** --color-highspeed-share-dialog-download-foreground */
    private val highspeedShareDialogDownloadForegroundArgb: Int,
    /** --color-highspeed-share-dialog-download-hover-surface */
    private val highspeedShareDialogDownloadHoverSurfaceArgb: Int,
    /** --color-highspeed-share-dialog-download-surface */
    private val highspeedShareDialogDownloadSurfaceArgb: Int,
    /** --color-highspeed-share-dialog-foreground */
    private val highspeedShareDialogForegroundArgb: Int,
    /** --color-highspeed-share-dialog-input-border */
    private val highspeedShareDialogInputBorderArgb: Int,
    /** --color-highspeed-share-dialog-logo-border */
    private val highspeedShareDialogLogoBorderArgb: Int,
    /** --color-highspeed-share-dialog-logo-surface */
    private val highspeedShareDialogLogoSurfaceArgb: Int,
    /** --color-highspeed-share-dialog-marker-glow */
    private val highspeedShareDialogMarkerGlowArgb: Int,
    /** --color-highspeed-share-dialog-muted */
    private val highspeedShareDialogMutedArgb: Int,
    /** --color-highspeed-share-dialog-pill-text */
    private val highspeedShareDialogPillTextArgb: Int,
    /** --color-highspeed-share-dialog-stats-border */
    private val highspeedShareDialogStatsBorderArgb: Int,
    /** --color-highspeed-share-dialog-surface */
    private val highspeedShareDialogSurfaceArgb: Int,
    /** --color-highspeed-share-foreground */
    private val highspeedShareForegroundArgb: Int,
    /** --color-highspeed-share-foreground-subtle */
    private val highspeedShareForegroundSubtleArgb: Int,
    /** --color-hover */
    private val hoverArgb: Int,
    /** --color-icon-blue */
    private val iconBlueArgb: Int,
    /** --color-icon-purple */
    private val iconPurpleArgb: Int,
    /** --color-idle-task */
    private val idleTaskArgb: Int,
    /** --color-idle-task-surface */
    private val idleTaskSurfaceArgb: Int,
    /** --color-input */
    private val inputArgb: Int,
    /** --color-input-border */
    private val inputBorderArgb: Int,
    /** --color-input-border-focused */
    private val inputBorderFocusedArgb: Int,
    /** --color-input-border-hover */
    private val inputBorderHoverArgb: Int,
    /** --color-input-focused */
    private val inputFocusedArgb: Int,
    /** --color-interaction-ask-fill */
    private val interactionAskFillArgb: Int,
    /** --color-interaction-ask-foreground */
    private val interactionAskForegroundArgb: Int,
    /** --color-interaction-ask-surface */
    private val interactionAskSurfaceArgb: Int,
    /** --color-interaction-confirmation-foreground */
    private val interactionConfirmationForegroundArgb: Int,
    /** --color-interaction-confirmation-surface */
    private val interactionConfirmationSurfaceArgb: Int,
    /** --color-markdown-inline-code */
    private val markdownInlineCodeArgb: Int,
    /** --color-menu */
    private val menuArgb: Int,
    /** --color-menu-hover */
    private val menuHoverArgb: Int,
    /** --color-panel */
    private val panelArgb: Int,
    /** --color-plugin-node */
    private val pluginNodeArgb: Int,
    /** --color-plugin-node-foreground */
    private val pluginNodeForegroundArgb: Int,
    /** --color-plugin-node-hover */
    private val pluginNodeHoverArgb: Int,
    /** --color-plugin-paid-plan-badge */
    private val pluginPaidPlanBadgeArgb: Int,
    /** --color-plugin-paid-plan-badge-foreground */
    private val pluginPaidPlanBadgeForegroundArgb: Int,
    /** --color-popover */
    private val popoverArgb: Int,
    /** --color-popover-border */
    private val popoverBorderArgb: Int,
    /** --color-popover-foreground */
    private val popoverForegroundArgb: Int,
    /** --color-popover-header */
    private val popoverHeaderArgb: Int,
    /** --color-primary */
    private val primaryArgb: Int,
    /** --color-primary-foreground */
    private val primaryForegroundArgb: Int,
    /** --color-secondary */
    private val secondaryArgb: Int,
    /** --color-selected */
    private val selectedArgb: Int,
    /** --color-session-node */
    private val sessionNodeArgb: Int,
    /** --color-session-node-foreground */
    private val sessionNodeForegroundArgb: Int,
    /** --color-session-node-hover */
    private val sessionNodeHoverArgb: Int,
    /** --color-sidebar */
    private val sidebarArgb: Int,
    /** --color-skill-node */
    private val skillNodeArgb: Int,
    /** --color-skill-node-foreground */
    private val skillNodeForegroundArgb: Int,
    /** --color-skill-node-hover */
    private val skillNodeHoverArgb: Int,
    /** --color-subagent-node */
    private val subagentNodeArgb: Int,
    /** --color-subagent-node-foreground */
    private val subagentNodeForegroundArgb: Int,
    /** --color-subagent-node-hover */
    private val subagentNodeHoverArgb: Int,
    /** --color-success */
    private val successArgb: Int,
    /** --color-success-foreground */
    private val successForegroundArgb: Int,
    /** --color-surface */
    private val surfaceArgb: Int,
    /** --color-surface-hover */
    private val surfaceHoverArgb: Int,
    /** --color-tab */
    private val tabArgb: Int,
    /** --color-tab-active */
    private val tabActiveArgb: Int,
    /** --color-tab-border */
    private val tabBorderArgb: Int,
    /** --color-tag */
    private val tagArgb: Int,
    /** --color-terminal-bg */
    private val terminalBgArgb: Int,
    /** --color-terminal-black */
    private val terminalBlackArgb: Int,
    /** --color-terminal-blue */
    private val terminalBlueArgb: Int,
    /** --color-terminal-bright-black */
    private val terminalBrightBlackArgb: Int,
    /** --color-terminal-bright-blue */
    private val terminalBrightBlueArgb: Int,
    /** --color-terminal-bright-cyan */
    private val terminalBrightCyanArgb: Int,
    /** --color-terminal-bright-green */
    private val terminalBrightGreenArgb: Int,
    /** --color-terminal-bright-magenta */
    private val terminalBrightMagentaArgb: Int,
    /** --color-terminal-bright-red */
    private val terminalBrightRedArgb: Int,
    /** --color-terminal-bright-white */
    private val terminalBrightWhiteArgb: Int,
    /** --color-terminal-bright-yellow */
    private val terminalBrightYellowArgb: Int,
    /** --color-terminal-cursor */
    private val terminalCursorArgb: Int,
    /** --color-terminal-cursor-accent */
    private val terminalCursorAccentArgb: Int,
    /** --color-terminal-cyan */
    private val terminalCyanArgb: Int,
    /** --color-terminal-fg */
    private val terminalFgArgb: Int,
    /** --color-terminal-green */
    private val terminalGreenArgb: Int,
    /** --color-terminal-magenta */
    private val terminalMagentaArgb: Int,
    /** --color-terminal-red */
    private val terminalRedArgb: Int,
    /** --color-terminal-selection */
    private val terminalSelectionArgb: Int,
    /** --color-terminal-selection-inactive */
    private val terminalSelectionInactiveArgb: Int,
    /** --color-terminal-white */
    private val terminalWhiteArgb: Int,
    /** --color-terminal-yellow */
    private val terminalYellowArgb: Int,
    /** --color-toast */
    private val toastArgb: Int,
    /** --color-tooltip */
    private val tooltipArgb: Int,
    /** --color-tooltip-foreground */
    private val tooltipForegroundArgb: Int,
    /** --color-tooltip-tag */
    private val tooltipTagArgb: Int,
    /** --color-tooltip-tag-foreground */
    private val tooltipTagForegroundArgb: Int,
    /** --color-trajectory-assistant */
    private val trajectoryAssistantArgb: Int,
    /** --color-trajectory-reasoning */
    private val trajectoryReasoningArgb: Int,
    /** --color-trajectory-tool-call */
    private val trajectoryToolCallArgb: Int,
    /** --color-trajectory-tool-result */
    private val trajectoryToolResultArgb: Int,
    /** --color-trajectory-user */
    private val trajectoryUserArgb: Int,
    /** --color-usage-chart-1 */
    private val usageChart1Argb: Int,
    /** --color-usage-chart-2 */
    private val usageChart2Argb: Int,
    /** --color-usage-chart-3 */
    private val usageChart3Argb: Int,
    /** --color-usage-chart-4 */
    private val usageChart4Argb: Int,
    /** --color-usage-chart-5 */
    private val usageChart5Argb: Int,
    /** --color-usage-chart-6 */
    private val usageChart6Argb: Int,
    /** --color-usage-heatmap-0 */
    private val usageHeatmap0Argb: Int,
    /** --color-usage-heatmap-1 */
    private val usageHeatmap1Argb: Int,
    /** --color-usage-heatmap-2 */
    private val usageHeatmap2Argb: Int,
    /** --color-usage-heatmap-3 */
    private val usageHeatmap3Argb: Int,
    /** --color-usage-heatmap-4 */
    private val usageHeatmap4Argb: Int,
    /** --color-warning */
    private val warningArgb: Int,
    /** --color-warning-foreground */
    private val warningForegroundArgb: Int,
    /** --color-workflow-rule */
    private val workflowRuleArgb: Int,
    /** --color-workflow-trace */
    private val workflowTraceArgb: Int,
    /** --color-workflow-trace-strong */
    private val workflowTraceStrongArgb: Int,
) {
    /** --color-accent */
    val accent: Color = Color(accentArgb)
    /** --color-background */
    val background: Color = Color(backgroundArgb)
    /** --color-background-alt */
    val backgroundAlt: Color = Color(backgroundAltArgb)
    /** --color-background-win-alt */
    val backgroundWinAlt: Color = Color(backgroundWinAltArgb)
    /** --color-border */
    val border: Color = Color(borderArgb)
    /** --color-border-hover */
    val borderHover: Color = Color(borderHoverArgb)
    /** --color-brand */
    val brand: Color = Color(brandArgb)
    /** --color-card */
    val card: Color = Color(cardArgb)
    /** --color-card-border */
    val cardBorder: Color = Color(cardBorderArgb)
    /** --color-card-selected */
    val cardSelected: Color = Color(cardSelectedArgb)
    /** --color-command-node */
    val commandNode: Color = Color(commandNodeArgb)
    /** --color-command-node-foreground */
    val commandNodeForeground: Color = Color(commandNodeForegroundArgb)
    /** --color-command-node-hover */
    val commandNodeHover: Color = Color(commandNodeHoverArgb)
    /** --color-context-breakdown-1 */
    val contextBreakdown1: Color = Color(contextBreakdown1Argb)
    /** --color-context-breakdown-2 */
    val contextBreakdown2: Color = Color(contextBreakdown2Argb)
    /** --color-context-breakdown-3 */
    val contextBreakdown3: Color = Color(contextBreakdown3Argb)
    /** --color-context-breakdown-4 */
    val contextBreakdown4: Color = Color(contextBreakdown4Argb)
    /** --color-context-breakdown-5 */
    val contextBreakdown5: Color = Color(contextBreakdown5Argb)
    /** --color-context-breakdown-6 */
    val contextBreakdown6: Color = Color(contextBreakdown6Argb)
    /** --color-context-breakdown-7 */
    val contextBreakdown7: Color = Color(contextBreakdown7Argb)
    /** --color-destructive */
    val destructive: Color = Color(destructiveArgb)
    /** --color-destructive-foreground */
    val destructiveForeground: Color = Color(destructiveForegroundArgb)
    /** --color-diff-added */
    val diffAdded: Color = Color(diffAddedArgb)
    /** --color-diff-added-foreground */
    val diffAddedForeground: Color = Color(diffAddedForegroundArgb)
    /** --color-diff-removed */
    val diffRemoved: Color = Color(diffRemovedArgb)
    /** --color-diff-removed-foreground */
    val diffRemovedForeground: Color = Color(diffRemovedForegroundArgb)
    /** --color-feedback-privacy-hint */
    val feedbackPrivacyHint: Color = Color(feedbackPrivacyHintArgb)
    /** --color-file-node */
    val fileNode: Color = Color(fileNodeArgb)
    /** --color-file-node-foreground */
    val fileNodeForeground: Color = Color(fileNodeForegroundArgb)
    /** --color-file-node-hover */
    val fileNodeHover: Color = Color(fileNodeHoverArgb)
    /** --color-find-highlight */
    val findHighlight: Color = Color(findHighlightArgb)
    /** --color-find-highlight-active */
    val findHighlightActive: Color = Color(findHighlightActiveArgb)
    /** --color-foreground */
    val foreground: Color = Color(foregroundArgb)
    /** --color-foreground-inverse */
    val foregroundInverse: Color = Color(foregroundInverseArgb)
    /** --color-foreground-subtle */
    val foregroundSubtle: Color = Color(foregroundSubtleArgb)
    /** --color-foreground-subtlest */
    val foregroundSubtlest: Color = Color(foregroundSubtlestArgb)
    /** --color-git-added */
    val gitAdded: Color = Color(gitAddedArgb)
    /** --color-git-deleted */
    val gitDeleted: Color = Color(gitDeletedArgb)
    /** --color-git-descendant */
    val gitDescendant: Color = Color(gitDescendantArgb)
    /** --color-git-ignored */
    val gitIgnored: Color = Color(gitIgnoredArgb)
    /** --color-git-modified */
    val gitModified: Color = Color(gitModifiedArgb)
    /** --color-git-none */
    val gitNone: Color = Color(gitNoneArgb)
    /** --color-git-renamed */
    val gitRenamed: Color = Color(gitRenamedArgb)
    /** --color-git-untracked */
    val gitUntracked: Color = Color(gitUntrackedArgb)
    /** --color-header */
    val header: Color = Color(headerArgb)
    /** --color-highspeed-accent */
    val highspeedAccent: Color = Color(highspeedAccentArgb)
    /** --color-highspeed-composer-border-hover */
    val highspeedComposerBorderHover: Color = Color(highspeedComposerBorderHoverArgb)
    /** --color-highspeed-composer-border-idle */
    val highspeedComposerBorderIdle: Color = Color(highspeedComposerBorderIdleArgb)
    /** --color-highspeed-composer-border-pre-animation */
    val highspeedComposerBorderPreAnimation: Color = Color(highspeedComposerBorderPreAnimationArgb)
    /** --color-highspeed-composer-surface */
    val highspeedComposerSurface: Color = Color(highspeedComposerSurfaceArgb)
    /** --color-highspeed-glow-01 */
    val highspeedGlow01: Color = Color(highspeedGlow01Argb)
    /** --color-highspeed-glow-04 */
    val highspeedGlow04: Color = Color(highspeedGlow04Argb)
    /** --color-highspeed-glow-15 */
    val highspeedGlow15: Color = Color(highspeedGlow15Argb)
    /** --color-highspeed-glow-26 */
    val highspeedGlow26: Color = Color(highspeedGlow26Argb)
    /** --color-highspeed-model-sweep-highlight */
    val highspeedModelSweepHighlight: Color = Color(highspeedModelSweepHighlightArgb)
    /** --color-highspeed-particle */
    val highspeedParticle: Color = Color(highspeedParticleArgb)
    /** --color-highspeed-particle-highlight */
    val highspeedParticleHighlight: Color = Color(highspeedParticleHighlightArgb)
    /** --color-highspeed-particle-highlight-glow */
    val highspeedParticleHighlightGlow: Color = Color(highspeedParticleHighlightGlowArgb)
    /** --color-highspeed-share-card-view-border */
    val highspeedShareCardViewBorder: Color = Color(highspeedShareCardViewBorderArgb)
    /** --color-highspeed-share-card-view-end */
    val highspeedShareCardViewEnd: Color = Color(highspeedShareCardViewEndArgb)
    /** --color-highspeed-share-card-view-start */
    val highspeedShareCardViewStart: Color = Color(highspeedShareCardViewStartArgb)
    /** --color-highspeed-share-control-hover */
    val highspeedShareControlHover: Color = Color(highspeedShareControlHoverArgb)
    /** --color-highspeed-share-dialog-bars-baseline */
    val highspeedShareDialogBarsBaseline: Color = Color(highspeedShareDialogBarsBaselineArgb)
    /** --color-highspeed-share-dialog-bars-end */
    val highspeedShareDialogBarsEnd: Color = Color(highspeedShareDialogBarsEndArgb)
    /** --color-highspeed-share-dialog-bars-mid */
    val highspeedShareDialogBarsMid: Color = Color(highspeedShareDialogBarsMidArgb)
    /** --color-highspeed-share-dialog-bars-start */
    val highspeedShareDialogBarsStart: Color = Color(highspeedShareDialogBarsStartArgb)
    /** --color-highspeed-share-dialog-benefit-text */
    val highspeedShareDialogBenefitText: Color = Color(highspeedShareDialogBenefitTextArgb)
    /** --color-highspeed-share-dialog-border */
    val highspeedShareDialogBorder: Color = Color(highspeedShareDialogBorderArgb)
    /** --color-highspeed-share-dialog-control-hover */
    val highspeedShareDialogControlHover: Color = Color(highspeedShareDialogControlHoverArgb)
    /** --color-highspeed-share-dialog-download-foreground */
    val highspeedShareDialogDownloadForeground: Color = Color(highspeedShareDialogDownloadForegroundArgb)
    /** --color-highspeed-share-dialog-download-hover-surface */
    val highspeedShareDialogDownloadHoverSurface: Color = Color(highspeedShareDialogDownloadHoverSurfaceArgb)
    /** --color-highspeed-share-dialog-download-surface */
    val highspeedShareDialogDownloadSurface: Color = Color(highspeedShareDialogDownloadSurfaceArgb)
    /** --color-highspeed-share-dialog-foreground */
    val highspeedShareDialogForeground: Color = Color(highspeedShareDialogForegroundArgb)
    /** --color-highspeed-share-dialog-input-border */
    val highspeedShareDialogInputBorder: Color = Color(highspeedShareDialogInputBorderArgb)
    /** --color-highspeed-share-dialog-logo-border */
    val highspeedShareDialogLogoBorder: Color = Color(highspeedShareDialogLogoBorderArgb)
    /** --color-highspeed-share-dialog-logo-surface */
    val highspeedShareDialogLogoSurface: Color = Color(highspeedShareDialogLogoSurfaceArgb)
    /** --color-highspeed-share-dialog-marker-glow */
    val highspeedShareDialogMarkerGlow: Color = Color(highspeedShareDialogMarkerGlowArgb)
    /** --color-highspeed-share-dialog-muted */
    val highspeedShareDialogMuted: Color = Color(highspeedShareDialogMutedArgb)
    /** --color-highspeed-share-dialog-pill-text */
    val highspeedShareDialogPillText: Color = Color(highspeedShareDialogPillTextArgb)
    /** --color-highspeed-share-dialog-stats-border */
    val highspeedShareDialogStatsBorder: Color = Color(highspeedShareDialogStatsBorderArgb)
    /** --color-highspeed-share-dialog-surface */
    val highspeedShareDialogSurface: Color = Color(highspeedShareDialogSurfaceArgb)
    /** --color-highspeed-share-foreground */
    val highspeedShareForeground: Color = Color(highspeedShareForegroundArgb)
    /** --color-highspeed-share-foreground-subtle */
    val highspeedShareForegroundSubtle: Color = Color(highspeedShareForegroundSubtleArgb)
    /** --color-hover */
    val hover: Color = Color(hoverArgb)
    /** --color-icon-blue */
    val iconBlue: Color = Color(iconBlueArgb)
    /** --color-icon-purple */
    val iconPurple: Color = Color(iconPurpleArgb)
    /** --color-idle-task */
    val idleTask: Color = Color(idleTaskArgb)
    /** --color-idle-task-surface */
    val idleTaskSurface: Color = Color(idleTaskSurfaceArgb)
    /** --color-input */
    val input: Color = Color(inputArgb)
    /** --color-input-border */
    val inputBorder: Color = Color(inputBorderArgb)
    /** --color-input-border-focused */
    val inputBorderFocused: Color = Color(inputBorderFocusedArgb)
    /** --color-input-border-hover */
    val inputBorderHover: Color = Color(inputBorderHoverArgb)
    /** --color-input-focused */
    val inputFocused: Color = Color(inputFocusedArgb)
    /** --color-interaction-ask-fill */
    val interactionAskFill: Color = Color(interactionAskFillArgb)
    /** --color-interaction-ask-foreground */
    val interactionAskForeground: Color = Color(interactionAskForegroundArgb)
    /** --color-interaction-ask-surface */
    val interactionAskSurface: Color = Color(interactionAskSurfaceArgb)
    /** --color-interaction-confirmation-foreground */
    val interactionConfirmationForeground: Color = Color(interactionConfirmationForegroundArgb)
    /** --color-interaction-confirmation-surface */
    val interactionConfirmationSurface: Color = Color(interactionConfirmationSurfaceArgb)
    /** --color-markdown-inline-code */
    val markdownInlineCode: Color = Color(markdownInlineCodeArgb)
    /** --color-menu */
    val menu: Color = Color(menuArgb)
    /** --color-menu-hover */
    val menuHover: Color = Color(menuHoverArgb)
    /** --color-panel */
    val panel: Color = Color(panelArgb)
    /** --color-plugin-node */
    val pluginNode: Color = Color(pluginNodeArgb)
    /** --color-plugin-node-foreground */
    val pluginNodeForeground: Color = Color(pluginNodeForegroundArgb)
    /** --color-plugin-node-hover */
    val pluginNodeHover: Color = Color(pluginNodeHoverArgb)
    /** --color-plugin-paid-plan-badge */
    val pluginPaidPlanBadge: Color = Color(pluginPaidPlanBadgeArgb)
    /** --color-plugin-paid-plan-badge-foreground */
    val pluginPaidPlanBadgeForeground: Color = Color(pluginPaidPlanBadgeForegroundArgb)
    /** --color-popover */
    val popover: Color = Color(popoverArgb)
    /** --color-popover-border */
    val popoverBorder: Color = Color(popoverBorderArgb)
    /** --color-popover-foreground */
    val popoverForeground: Color = Color(popoverForegroundArgb)
    /** --color-popover-header */
    val popoverHeader: Color = Color(popoverHeaderArgb)
    /** --color-primary */
    val primary: Color = Color(primaryArgb)
    /** --color-primary-foreground */
    val primaryForeground: Color = Color(primaryForegroundArgb)
    /** --color-secondary */
    val secondary: Color = Color(secondaryArgb)
    /** --color-selected */
    val selected: Color = Color(selectedArgb)
    /** --color-session-node */
    val sessionNode: Color = Color(sessionNodeArgb)
    /** --color-session-node-foreground */
    val sessionNodeForeground: Color = Color(sessionNodeForegroundArgb)
    /** --color-session-node-hover */
    val sessionNodeHover: Color = Color(sessionNodeHoverArgb)
    /** --color-sidebar */
    val sidebar: Color = Color(sidebarArgb)
    /** --color-skill-node */
    val skillNode: Color = Color(skillNodeArgb)
    /** --color-skill-node-foreground */
    val skillNodeForeground: Color = Color(skillNodeForegroundArgb)
    /** --color-skill-node-hover */
    val skillNodeHover: Color = Color(skillNodeHoverArgb)
    /** --color-subagent-node */
    val subagentNode: Color = Color(subagentNodeArgb)
    /** --color-subagent-node-foreground */
    val subagentNodeForeground: Color = Color(subagentNodeForegroundArgb)
    /** --color-subagent-node-hover */
    val subagentNodeHover: Color = Color(subagentNodeHoverArgb)
    /** --color-success */
    val success: Color = Color(successArgb)
    /** --color-success-foreground */
    val successForeground: Color = Color(successForegroundArgb)
    /** --color-surface */
    val surface: Color = Color(surfaceArgb)
    /** --color-surface-hover */
    val surfaceHover: Color = Color(surfaceHoverArgb)
    /** --color-tab */
    val tab: Color = Color(tabArgb)
    /** --color-tab-active */
    val tabActive: Color = Color(tabActiveArgb)
    /** --color-tab-border */
    val tabBorder: Color = Color(tabBorderArgb)
    /** --color-tag */
    val tag: Color = Color(tagArgb)
    /** --color-terminal-bg */
    val terminalBg: Color = Color(terminalBgArgb)
    /** --color-terminal-black */
    val terminalBlack: Color = Color(terminalBlackArgb)
    /** --color-terminal-blue */
    val terminalBlue: Color = Color(terminalBlueArgb)
    /** --color-terminal-bright-black */
    val terminalBrightBlack: Color = Color(terminalBrightBlackArgb)
    /** --color-terminal-bright-blue */
    val terminalBrightBlue: Color = Color(terminalBrightBlueArgb)
    /** --color-terminal-bright-cyan */
    val terminalBrightCyan: Color = Color(terminalBrightCyanArgb)
    /** --color-terminal-bright-green */
    val terminalBrightGreen: Color = Color(terminalBrightGreenArgb)
    /** --color-terminal-bright-magenta */
    val terminalBrightMagenta: Color = Color(terminalBrightMagentaArgb)
    /** --color-terminal-bright-red */
    val terminalBrightRed: Color = Color(terminalBrightRedArgb)
    /** --color-terminal-bright-white */
    val terminalBrightWhite: Color = Color(terminalBrightWhiteArgb)
    /** --color-terminal-bright-yellow */
    val terminalBrightYellow: Color = Color(terminalBrightYellowArgb)
    /** --color-terminal-cursor */
    val terminalCursor: Color = Color(terminalCursorArgb)
    /** --color-terminal-cursor-accent */
    val terminalCursorAccent: Color = Color(terminalCursorAccentArgb)
    /** --color-terminal-cyan */
    val terminalCyan: Color = Color(terminalCyanArgb)
    /** --color-terminal-fg */
    val terminalFg: Color = Color(terminalFgArgb)
    /** --color-terminal-green */
    val terminalGreen: Color = Color(terminalGreenArgb)
    /** --color-terminal-magenta */
    val terminalMagenta: Color = Color(terminalMagentaArgb)
    /** --color-terminal-red */
    val terminalRed: Color = Color(terminalRedArgb)
    /** --color-terminal-selection */
    val terminalSelection: Color = Color(terminalSelectionArgb)
    /** --color-terminal-selection-inactive */
    val terminalSelectionInactive: Color = Color(terminalSelectionInactiveArgb)
    /** --color-terminal-white */
    val terminalWhite: Color = Color(terminalWhiteArgb)
    /** --color-terminal-yellow */
    val terminalYellow: Color = Color(terminalYellowArgb)
    /** --color-toast */
    val toast: Color = Color(toastArgb)
    /** --color-tooltip */
    val tooltip: Color = Color(tooltipArgb)
    /** --color-tooltip-foreground */
    val tooltipForeground: Color = Color(tooltipForegroundArgb)
    /** --color-tooltip-tag */
    val tooltipTag: Color = Color(tooltipTagArgb)
    /** --color-tooltip-tag-foreground */
    val tooltipTagForeground: Color = Color(tooltipTagForegroundArgb)
    /** --color-trajectory-assistant */
    val trajectoryAssistant: Color = Color(trajectoryAssistantArgb)
    /** --color-trajectory-reasoning */
    val trajectoryReasoning: Color = Color(trajectoryReasoningArgb)
    /** --color-trajectory-tool-call */
    val trajectoryToolCall: Color = Color(trajectoryToolCallArgb)
    /** --color-trajectory-tool-result */
    val trajectoryToolResult: Color = Color(trajectoryToolResultArgb)
    /** --color-trajectory-user */
    val trajectoryUser: Color = Color(trajectoryUserArgb)
    /** --color-usage-chart-1 */
    val usageChart1: Color = Color(usageChart1Argb)
    /** --color-usage-chart-2 */
    val usageChart2: Color = Color(usageChart2Argb)
    /** --color-usage-chart-3 */
    val usageChart3: Color = Color(usageChart3Argb)
    /** --color-usage-chart-4 */
    val usageChart4: Color = Color(usageChart4Argb)
    /** --color-usage-chart-5 */
    val usageChart5: Color = Color(usageChart5Argb)
    /** --color-usage-chart-6 */
    val usageChart6: Color = Color(usageChart6Argb)
    /** --color-usage-heatmap-0 */
    val usageHeatmap0: Color = Color(usageHeatmap0Argb)
    /** --color-usage-heatmap-1 */
    val usageHeatmap1: Color = Color(usageHeatmap1Argb)
    /** --color-usage-heatmap-2 */
    val usageHeatmap2: Color = Color(usageHeatmap2Argb)
    /** --color-usage-heatmap-3 */
    val usageHeatmap3: Color = Color(usageHeatmap3Argb)
    /** --color-usage-heatmap-4 */
    val usageHeatmap4: Color = Color(usageHeatmap4Argb)
    /** --color-warning */
    val warning: Color = Color(warningArgb)
    /** --color-warning-foreground */
    val warningForeground: Color = Color(warningForegroundArgb)
    /** --color-workflow-rule */
    val workflowRule: Color = Color(workflowRuleArgb)
    /** --color-workflow-trace */
    val workflowTrace: Color = Color(workflowTraceArgb)
    /** --color-workflow-trace-strong */
    val workflowTraceStrong: Color = Color(workflowTraceStrongArgb)

    companion object {
        /** 应用默认浅色主题（.theme-zai-light 口径）。 */
        val Light: ZemoteColors = ZemoteColors(
            accentArgb = 0xFFF0F9FF.toInt(),
            backgroundArgb = 0xFFFAFAFA.toInt(),
            backgroundAltArgb = 0x99F5F5F5.toInt(),
            backgroundWinAltArgb = 0xFFE5E5E5.toInt(),
            borderArgb = 0x1A0A0A0A,
            borderHoverArgb = 0x330A0A0A,
            brandArgb = 0xFF00BCFF.toInt(),
            cardArgb = 0xFFFFFFFF.toInt(),
            cardBorderArgb = 0x1A0A0A0A,
            cardSelectedArgb = 0xFFE5E5E5.toInt(),
            commandNodeArgb = 0x1A566270,
            commandNodeForegroundArgb = 0xFF45556C.toInt(),
            commandNodeHoverArgb = 0x29566270,
            contextBreakdown1Argb = 0xFF0B7FFF.toInt(),
            contextBreakdown2Argb = 0xFF338FFF.toInt(),
            contextBreakdown3Argb = 0xFF5CA7FF.toInt(),
            contextBreakdown4Argb = 0xFF85BBFF.toInt(),
            contextBreakdown5Argb = 0xFFACD0FF.toInt(),
            contextBreakdown6Argb = 0xFFC8DDFF.toInt(),
            contextBreakdown7Argb = 0xFFE0ECFF.toInt(),
            destructiveArgb = 0xFFE7000B.toInt(),
            destructiveForegroundArgb = 0xFFFFFFFF.toInt(),
            diffAddedArgb = 0xFF00A63E.toInt(),
            diffAddedForegroundArgb = 0xFFFFFFFF.toInt(),
            diffRemovedArgb = 0xFFE7000B.toInt(),
            diffRemovedForegroundArgb = 0xFFFFFFFF.toInt(),
            feedbackPrivacyHintArgb = 0xFFD08700.toInt(),
            fileNodeArgb = 0x240084D1,
            fileNodeForegroundArgb = 0xFF0084D1.toInt(),
            fileNodeHoverArgb = 0x291A70B8,
            findHighlightArgb = 0xFFFDE68A.toInt(),
            findHighlightActiveArgb = 0xFFFACC15.toInt(),
            foregroundArgb = 0xFF404040.toInt(),
            foregroundInverseArgb = 0xFFFFFFFF.toInt(),
            foregroundSubtleArgb = 0x99404040.toInt(),
            foregroundSubtlestArgb = 0x66404040,
            gitAddedArgb = 0xFF009689.toInt(),
            gitDeletedArgb = 0xFFE7000B.toInt(),
            gitDescendantArgb = 0xFFE17100.toInt(),
            gitIgnoredArgb = 0x66404040,
            gitModifiedArgb = 0xFFE17100.toInt(),
            gitNoneArgb = 0xFF262626.toInt(),
            gitRenamedArgb = 0xFF0084D1.toInt(),
            gitUntrackedArgb = 0xFF009689.toInt(),
            headerArgb = 0xFFF5F5F5.toInt(),
            highspeedAccentArgb = 0xFF9E77ED.toInt(),
            highspeedComposerBorderHoverArgb = 0xFF6041FF.toInt(),
            highspeedComposerBorderIdleArgb = 0xFF6041FF.toInt(),
            highspeedComposerBorderPreAnimationArgb = 0x1A0A0A0A,
            highspeedComposerSurfaceArgb = 0xFFFFFFFF.toInt(),
            highspeedGlow01Argb = 0x036041FF,
            highspeedGlow04Argb = 0x0A6041FF,
            highspeedGlow15Argb = 0x266041FF,
            highspeedGlow26Argb = 0x426041FF,
            highspeedModelSweepHighlightArgb = 0xFFB392FF.toInt(),
            highspeedParticleArgb = 0xC27453B0.toInt(),
            highspeedParticleHighlightArgb = 0xFF7453B0.toInt(),
            highspeedParticleHighlightGlowArgb = 0x617453B0,
            highspeedShareCardViewBorderArgb = 0x2E000000,
            highspeedShareCardViewEndArgb = 0x0F999999,
            highspeedShareCardViewStartArgb = 0x0FFFFFFF,
            highspeedShareControlHoverArgb = 0x0A0D0D0D,
            highspeedShareDialogBarsBaselineArgb = 0x0D0D0D0D,
            highspeedShareDialogBarsEndArgb = 0xFF80D68D.toInt(),
            highspeedShareDialogBarsMidArgb = 0xFFF0C76E.toInt(),
            highspeedShareDialogBarsStartArgb = 0xFFED6C70.toInt(),
            highspeedShareDialogBenefitTextArgb = 0xFF5C5C5C.toInt(),
            highspeedShareDialogBorderArgb = 0x1A000000,
            highspeedShareDialogControlHoverArgb = 0x12000000,
            highspeedShareDialogDownloadForegroundArgb = 0xFFFFFFFF.toInt(),
            highspeedShareDialogDownloadHoverSurfaceArgb = 0xFF1F1F1F.toInt(),
            highspeedShareDialogDownloadSurfaceArgb = 0xFF000000.toInt(),
            highspeedShareDialogForegroundArgb = 0xFF161518.toInt(),
            highspeedShareDialogInputBorderArgb = 0x1A000000,
            highspeedShareDialogLogoBorderArgb = 0x1FFFFFFF,
            highspeedShareDialogLogoSurfaceArgb = 0xFF2D2D2D.toInt(),
            highspeedShareDialogMarkerGlowArgb = 0x996BD77D.toInt(),
            highspeedShareDialogMutedArgb = 0x94000000.toInt(),
            highspeedShareDialogPillTextArgb = 0xFF5C5C5C.toInt(),
            highspeedShareDialogStatsBorderArgb = 0x1A000000,
            highspeedShareDialogSurfaceArgb = 0xFFFFFFFF.toInt(),
            highspeedShareForegroundArgb = 0xFF161518.toInt(),
            highspeedShareForegroundSubtleArgb = 0xCC000000.toInt(),
            hoverArgb = 0xFFE5E5E5.toInt(),
            iconBlueArgb = 0xFF00A6F4.toInt(),
            iconPurpleArgb = 0xFFE12AFB.toInt(),
            idleTaskArgb = 0xFF7F22FE.toInt(),
            idleTaskSurfaceArgb = 0xFFF5F3FF.toInt(),
            inputArgb = 0xFFFFFFFF.toInt(),
            inputBorderArgb = 0x1A0A0A0A,
            inputBorderFocusedArgb = 0xFF00BCFF.toInt(),
            inputBorderHoverArgb = 0x330A0A0A,
            inputFocusedArgb = 0xFFFAFAFA.toInt(),
            interactionAskFillArgb = 0x3300C950,
            interactionAskForegroundArgb = 0xFF0066DD.toInt(),
            interactionAskSurfaceArgb = 0xFFEBF4FF.toInt(),
            interactionConfirmationForegroundArgb = 0xFF008236.toInt(),
            interactionConfirmationSurfaceArgb = 0xFFF0FDF4.toInt(),
            markdownInlineCodeArgb = 0xFFE5E5E5.toInt(),
            menuArgb = 0xFFFFFFFF.toInt(),
            menuHoverArgb = 0xFFF5F5F5.toInt(),
            panelArgb = 0xFFF5F5F5.toInt(),
            pluginNodeArgb = 0x1AB87A1A,
            pluginNodeForegroundArgb = 0xFFE17100.toInt(),
            pluginNodeHoverArgb = 0x29B87A1A,
            pluginPaidPlanBadgeArgb = 0xFFFFE3C8.toInt(),
            pluginPaidPlanBadgeForegroundArgb = 0xFF4B280F.toInt(),
            popoverArgb = 0xFFFFFFFF.toInt(),
            popoverBorderArgb = 0x1A0A0A0A,
            popoverForegroundArgb = 0xFF404040.toInt(),
            popoverHeaderArgb = 0xFFF8F8F8.toInt(),
            primaryArgb = 0xFF0A0A0A.toInt(),
            primaryForegroundArgb = 0xFFFAFAFA.toInt(),
            secondaryArgb = 0xFFD4D4D4.toInt(),
            selectedArgb = 0x1A0A0A0A,
            sessionNodeArgb = 0x1A14807A,
            sessionNodeForegroundArgb = 0xFF009689.toInt(),
            sessionNodeHoverArgb = 0x2914807A,
            sidebarArgb = 0xFFF5F5F5.toInt(),
            skillNodeArgb = 0x1A7453B0,
            skillNodeForegroundArgb = 0xFF7F22FE.toInt(),
            skillNodeHoverArgb = 0x297453B0,
            subagentNodeArgb = 0x1A6F7A2F,
            subagentNodeForegroundArgb = 0xFF6F7A2F.toInt(),
            subagentNodeHoverArgb = 0x296F7A2F,
            successArgb = 0xFF00A63E.toInt(),
            successForegroundArgb = 0xFFFFFFFF.toInt(),
            surfaceArgb = 0x080A0A0A,
            surfaceHoverArgb = 0x0D0A0A0A,
            tabArgb = 0xFFF0F0F0.toInt(),
            tabActiveArgb = 0xFFFFFFFF.toInt(),
            tabBorderArgb = 0x1A0D0D0D,
            tagArgb = 0xFFE5E5E5.toInt(),
            terminalBgArgb = 0xFFFAFAFA.toInt(),
            terminalBlackArgb = 0xFF404040.toInt(),
            terminalBlueArgb = 0xFF00A6F4.toInt(),
            terminalBrightBlackArgb = 0xFF737373.toInt(),
            terminalBrightBlueArgb = 0xFF00A6F4.toInt(),
            terminalBrightCyanArgb = 0xFF00B8DB.toInt(),
            terminalBrightGreenArgb = 0xFF00C950.toInt(),
            terminalBrightMagentaArgb = 0xFFE12AFB.toInt(),
            terminalBrightRedArgb = 0xFFFB2C36.toInt(),
            terminalBrightWhiteArgb = 0xFF0A0A0A.toInt(),
            terminalBrightYellowArgb = 0xFFF0B100.toInt(),
            terminalCursorArgb = 0xFF0A0A0A.toInt(),
            terminalCursorAccentArgb = 0xFFFAFAFA.toInt(),
            terminalCyanArgb = 0xFF00B8DB.toInt(),
            terminalFgArgb = 0xFF0A0A0A.toInt(),
            terminalGreenArgb = 0xFF00C950.toInt(),
            terminalMagentaArgb = 0xFFE12AFB.toInt(),
            terminalRedArgb = 0xFFFB2C36.toInt(),
            terminalSelectionArgb = 0x5200A6F4,
            terminalSelectionInactiveArgb = 0x3800A6F4,
            terminalWhiteArgb = 0xFF737373.toInt(),
            terminalYellowArgb = 0xFFF0B100.toInt(),
            toastArgb = 0xFFFFFFFF.toInt(),
            tooltipArgb = 0xFFF5F5F5.toInt(),
            tooltipForegroundArgb = 0xFF0A0A0A.toInt(),
            tooltipTagArgb = 0xFFE5E5E5.toInt(),
            tooltipTagForegroundArgb = 0x990A0A0A.toInt(),
            trajectoryAssistantArgb = 0xFF0F766E.toInt(),
            trajectoryReasoningArgb = 0xFF7C3AED.toInt(),
            trajectoryToolCallArgb = 0xFFD97706.toInt(),
            trajectoryToolResultArgb = 0xFF0284C7.toInt(),
            trajectoryUserArgb = 0xFF2563EB.toInt(),
            usageChart1Argb = 0xFF0084D1.toInt(),
            usageChart2Argb = 0xFF009689.toInt(),
            usageChart3Argb = 0xFF7F22FE.toInt(),
            usageChart4Argb = 0xFFFF2056.toInt(),
            usageChart5Argb = 0xFF615FFF.toInt(),
            usageChart6Argb = 0xFF0092B8.toInt(),
            usageHeatmap0Argb = 0x080D0D0D,
            usageHeatmap1Argb = 0x341391D3,
            usageHeatmap2Argb = 0x610A9DE6,
            usageHeatmap3Argb = 0x970381CC.toInt(),
            usageHeatmap4Argb = 0xD30168A7.toInt(),
            warningArgb = 0xFFD08700.toInt(),
            warningForegroundArgb = 0xFFFFFFFF.toInt(),
            workflowRuleArgb = 0x0E0A0A0A,
            workflowTraceArgb = 0x4D0A0A0A,
            workflowTraceStrongArgb = 0x8C0A0A0A.toInt(),
        )

        /** 应用默认深色主题（.theme-zai-dark 口径）。 */
        val Dark: ZemoteColors = ZemoteColors(
            accentArgb = 0x80052F4A.toInt(),
            backgroundArgb = 0xFF171717.toInt(),
            backgroundAltArgb = 0x99262626.toInt(),
            backgroundWinAltArgb = 0xFF262626.toInt(),
            borderArgb = 0x1AFAFAFA,
            borderHoverArgb = 0x4DFAFAFA,
            brandArgb = 0xFF00A6F4.toInt(),
            cardArgb = 0xFF262626.toInt(),
            cardBorderArgb = 0x1AFAFAFA,
            cardSelectedArgb = 0xFF404040.toInt(),
            commandNodeArgb = 0x29CAD5E2,
            commandNodeForegroundArgb = 0xFFCAD5E2.toInt(),
            commandNodeHoverArgb = 0x38CAD5E2,
            contextBreakdown1Argb = 0xFF00BCFF.toInt(),
            contextBreakdown2Argb = 0xFF74D4FF.toInt(),
            contextBreakdown3Argb = 0xFFB8E6FE.toInt(),
            contextBreakdown4Argb = 0xFFC4EAFE.toInt(),
            contextBreakdown5Argb = 0xFFCCEDFF.toInt(),
            contextBreakdown6Argb = 0xFFD5F0FF.toInt(),
            contextBreakdown7Argb = 0xFFDDF3FF.toInt(),
            destructiveArgb = 0xFFFB2C36.toInt(),
            destructiveForegroundArgb = 0xFFFFFFFF.toInt(),
            diffAddedArgb = 0xFF00C950.toInt(),
            diffAddedForegroundArgb = 0xFFFFFFFF.toInt(),
            diffRemovedArgb = 0xFFFB2C36.toInt(),
            diffRemovedForegroundArgb = 0xFFFFFFFF.toInt(),
            feedbackPrivacyHintArgb = 0xFFFFDF20.toInt(),
            fileNodeArgb = 0x2E00BCFF,
            fileNodeForegroundArgb = 0xFF74D4FF.toInt(),
            fileNodeHoverArgb = 0x3D00BCFF,
            findHighlightArgb = 0xFF713F12.toInt(),
            findHighlightActiveArgb = 0xFFA16207.toInt(),
            foregroundArgb = 0xFFE5E5E5.toInt(),
            foregroundInverseArgb = 0xFFFFFFFF.toInt(),
            foregroundSubtleArgb = 0x99E5E5E5.toInt(),
            foregroundSubtlestArgb = 0x4DE5E5E5,
            gitAddedArgb = 0xFF00D5BE.toInt(),
            gitDeletedArgb = 0xFFFF6467.toInt(),
            gitDescendantArgb = 0xFFFFB900.toInt(),
            gitIgnoredArgb = 0x4DE5E5E5,
            gitModifiedArgb = 0xFFFFB900.toInt(),
            gitNoneArgb = 0xFFE5E5E5.toInt(),
            gitRenamedArgb = 0xFF00BCFF.toInt(),
            gitUntrackedArgb = 0xFF00D5BE.toInt(),
            headerArgb = 0xFF171717.toInt(),
            highspeedAccentArgb = 0xFFB392FF.toInt(),
            highspeedComposerBorderHoverArgb = 0xFF6041FF.toInt(),
            highspeedComposerBorderIdleArgb = 0xFF6041FF.toInt(),
            highspeedComposerBorderPreAnimationArgb = 0x00000000,
            highspeedComposerSurfaceArgb = 0xFF151515.toInt(),
            highspeedGlow01Argb = 0x03A888F2,
            highspeedGlow04Argb = 0x0AA888F2,
            highspeedGlow15Argb = 0x26A888F2,
            highspeedGlow26Argb = 0x42A888F2,
            highspeedModelSweepHighlightArgb = 0xFFF0E9FF.toInt(),
            highspeedParticleArgb = 0xC2B392FF.toInt(),
            highspeedParticleHighlightArgb = 0xFFB392FF.toInt(),
            highspeedParticleHighlightGlowArgb = 0xADB392FF.toInt(),
            highspeedShareCardViewBorderArgb = 0x70FFFFFF,
            highspeedShareCardViewEndArgb = 0x0F999999,
            highspeedShareCardViewStartArgb = 0x0FFFFFFF,
            highspeedShareControlHoverArgb = 0x1AFFFFFF,
            highspeedShareDialogBarsBaselineArgb = 0x2EFFFFFF,
            highspeedShareDialogBarsEndArgb = 0xFF82D990.toInt(),
            highspeedShareDialogBarsMidArgb = 0xFFF1C86F.toInt(),
            highspeedShareDialogBarsStartArgb = 0xFFEF6F72.toInt(),
            highspeedShareDialogBenefitTextArgb = 0xFFADADAD.toInt(),
            highspeedShareDialogBorderArgb = 0x1FFFFFFF,
            highspeedShareDialogControlHoverArgb = 0x1AFFFFFF,
            highspeedShareDialogDownloadForegroundArgb = 0xFFFFFFFF.toInt(),
            highspeedShareDialogDownloadHoverSurfaceArgb = 0xFF1F1F1F.toInt(),
            highspeedShareDialogDownloadSurfaceArgb = 0xFF000000.toInt(),
            highspeedShareDialogForegroundArgb = 0xFFF7F5FA.toInt(),
            highspeedShareDialogInputBorderArgb = 0x1AFFFFFF,
            highspeedShareDialogLogoBorderArgb = 0x1FFFFFFF,
            highspeedShareDialogLogoSurfaceArgb = 0xFF2D2D2D.toInt(),
            highspeedShareDialogMarkerGlowArgb = 0x9973DD84.toInt(),
            highspeedShareDialogMutedArgb = 0xA3E7E4ED.toInt(),
            highspeedShareDialogPillTextArgb = 0xFFADADAD.toInt(),
            highspeedShareDialogStatsBorderArgb = 0x1AFFFFFF,
            highspeedShareDialogSurfaceArgb = 0xFF101010.toInt(),
            highspeedShareForegroundArgb = 0xFFE7E4ED.toInt(),
            highspeedShareForegroundSubtleArgb = 0xFFC8C2D2.toInt(),
            hoverArgb = 0x1AFFFFFF,
            iconBlueArgb = 0xFF0084D1.toInt(),
            iconPurpleArgb = 0xFFC800DE.toInt(),
            idleTaskArgb = 0xFFA684FF.toInt(),
            idleTaskSurfaceArgb = 0x802F0D68.toInt(),
            inputArgb = 0xFF262626.toInt(),
            inputBorderArgb = 0x1AFAFAFA,
            inputBorderFocusedArgb = 0xFF00A6F4.toInt(),
            inputBorderHoverArgb = 0x4DFAFAFA,
            inputFocusedArgb = 0xFF0A0A0A.toInt(),
            interactionAskFillArgb = 0x3D05DF72,
            interactionAskForegroundArgb = 0xFF74D4FF.toInt(),
            interactionAskSurfaceArgb = 0x80052F4A.toInt(),
            interactionConfirmationForegroundArgb = 0xFF7BF1A8.toInt(),
            interactionConfirmationSurfaceArgb = 0x73032E15,
            markdownInlineCodeArgb = 0xFF404040.toInt(),
            menuArgb = 0xFF0A0A0A.toInt(),
            menuHoverArgb = 0xFF171717.toInt(),
            panelArgb = 0xFF171717.toInt(),
            pluginNodeArgb = 0x2EFFB900,
            pluginNodeForegroundArgb = 0xFFFFD230.toInt(),
            pluginNodeHoverArgb = 0x3DFFB900,
            pluginPaidPlanBadgeArgb = 0xFF5A341B.toInt(),
            pluginPaidPlanBadgeForegroundArgb = 0xFFFFD8B8.toInt(),
            popoverArgb = 0xFF262626.toInt(),
            popoverBorderArgb = 0x1AFAFAFA,
            popoverForegroundArgb = 0xFFE5E5E5.toInt(),
            popoverHeaderArgb = 0xFF404040.toInt(),
            primaryArgb = 0xFFFAFAFA.toInt(),
            primaryForegroundArgb = 0xFF0A0A0A.toInt(),
            secondaryArgb = 0xFF404040.toInt(),
            selectedArgb = 0x1AFFFFFF,
            sessionNodeArgb = 0x2E00D5BE,
            sessionNodeForegroundArgb = 0xFF46ECD5.toInt(),
            sessionNodeHoverArgb = 0x3D00D5BE,
            sidebarArgb = 0xFF0A0A0A.toInt(),
            skillNodeArgb = 0x2EA684FF,
            skillNodeForegroundArgb = 0xFFC4B4FF.toInt(),
            skillNodeHoverArgb = 0x3DA684FF,
            subagentNodeArgb = 0x2EB7BD75,
            subagentNodeForegroundArgb = 0xFFB7BD75.toInt(),
            subagentNodeHoverArgb = 0x3DB7BD75,
            successArgb = 0xFF00C950.toInt(),
            successForegroundArgb = 0xFFFFFFFF.toInt(),
            surfaceArgb = 0x0DFFFFFF,
            surfaceHoverArgb = 0x1AFFFFFF,
            tabArgb = 0xFF262626.toInt(),
            tabActiveArgb = 0xFF0A0A0A.toInt(),
            tabBorderArgb = 0x1AFAFAFA,
            tagArgb = 0xFF404040.toInt(),
            terminalBgArgb = 0xFF0A0A0A.toInt(),
            terminalBlackArgb = 0xFF262626.toInt(),
            terminalBlueArgb = 0xFF0084D1.toInt(),
            terminalBrightBlackArgb = 0xFF737373.toInt(),
            terminalBrightBlueArgb = 0xFF0084D1.toInt(),
            terminalBrightCyanArgb = 0xFF0092B8.toInt(),
            terminalBrightGreenArgb = 0xFF00A63E.toInt(),
            terminalBrightMagentaArgb = 0xFFC800DE.toInt(),
            terminalBrightRedArgb = 0xFFE7000B.toInt(),
            terminalBrightWhiteArgb = 0xFFFAFAFA.toInt(),
            terminalBrightYellowArgb = 0xFFD08700.toInt(),
            terminalCursorArgb = 0xFFFAFAFA.toInt(),
            terminalCursorAccentArgb = 0xFF0A0A0A.toInt(),
            terminalCyanArgb = 0xFF0092B8.toInt(),
            terminalFgArgb = 0xFFFAFAFA.toInt(),
            terminalGreenArgb = 0xFF00A63E.toInt(),
            terminalMagentaArgb = 0xFFC800DE.toInt(),
            terminalRedArgb = 0xFFE7000B.toInt(),
            terminalSelectionArgb = 0x4200A6F4,
            terminalSelectionInactiveArgb = 0x29FAFAFA,
            terminalWhiteArgb = 0xFFE5E5E5.toInt(),
            terminalYellowArgb = 0xFFD08700.toInt(),
            toastArgb = 0xFF171717.toInt(),
            tooltipArgb = 0xFF171717.toInt(),
            tooltipForegroundArgb = 0xFFFAFAFA.toInt(),
            tooltipTagArgb = 0xFF262626.toInt(),
            tooltipTagForegroundArgb = 0x99FAFAFA.toInt(),
            trajectoryAssistantArgb = 0xFF2DD4BF.toInt(),
            trajectoryReasoningArgb = 0xFFA78BFA.toInt(),
            trajectoryToolCallArgb = 0xFFF59E0B.toInt(),
            trajectoryToolResultArgb = 0xFF38BDF8.toInt(),
            trajectoryUserArgb = 0xFF60A5FA.toInt(),
            usageChart1Argb = 0xFF00A6F4.toInt(),
            usageChart2Argb = 0xFF00D5BE.toInt(),
            usageChart3Argb = 0xFFA684FF.toInt(),
            usageChart4Argb = 0xFFFF637E.toInt(),
            usageChart5Argb = 0xFF7C86FF.toInt(),
            usageChart6Argb = 0xFF00D3F2.toInt(),
            usageHeatmap0Argb = 0x0DFFFFFF,
            usageHeatmap1Argb = 0x474EC6FF,
            usageHeatmap2Argb = 0x7334C1FF,
            usageHeatmap3Argb = 0xA321BEFF.toInt(),
            usageHeatmap4Argb = 0xCA76D5FF.toInt(),
            warningArgb = 0xFFF0B100.toInt(),
            warningForegroundArgb = 0xFFFFFFFF.toInt(),
            workflowRuleArgb = 0x11FFFFFF,
            workflowTraceArgb = 0x59FFFFFF,
            workflowTraceStrongArgb = 0x99FFFFFF.toInt(),
        )

        /**
         * 3.17.1 网页 zai-dark 皮肤（181 条语义令牌，值在 ZaiColors.kt）。
         *
         * 与 [Dark] 的区别：Dark 是 3.15.3 的默认 `.dark` 口径；ZaiDark 按 3.17.1 的
         * `:root,:host` + `.theme-zai-dark` 解析，是应用当前实际使用的暗色令牌。
         */
        val ZaiDark: ZemoteColors get() = ZaiColors.ZaiDark

        /**
         * 3.17.1 网页 zai-light 皮肤（181 条语义令牌，值在 ZaiColors.kt）。
         *
         * 与 [Light] 的区别同上：Light 是 3.15.3 默认口径，ZaiLight 是 3.17.1 实值。
         */
        val ZaiLight: ZemoteColors get() = ZaiColors.ZaiLight
    }
}

// ===========================================================================
//  ZemotePalette —— Tailwind 基础调色板（colors.json tokens.base_palette，127 条）
//  明暗同值（基础调色板不随主题切换），供偶发取用，不作为语义层。
// ===========================================================================

@Immutable
data class ZemotePalette(
    /** --color-amber-300 */
    private val amber300Argb: Int,
    /** --color-amber-400 */
    private val amber400Argb: Int,
    /** --color-amber-50 */
    private val amber50Argb: Int,
    /** --color-amber-500 */
    private val amber500Argb: Int,
    /** --color-amber-600 */
    private val amber600Argb: Int,
    /** --color-amber-700 */
    private val amber700Argb: Int,
    /** --color-amber-900 */
    private val amber900Argb: Int,
    /** --color-black */
    private val blackArgb: Int,
    /** --color-blue-100 */
    private val blue100Argb: Int,
    /** --color-blue-200 */
    private val blue200Argb: Int,
    /** --color-blue-400 */
    private val blue400Argb: Int,
    /** --color-blue-500 */
    private val blue500Argb: Int,
    /** --color-blue-600 */
    private val blue600Argb: Int,
    /** --color-blue-700 */
    private val blue700Argb: Int,
    /** --color-blue-900 */
    private val blue900Argb: Int,
    /** --color-cyan-100 */
    private val cyan100Argb: Int,
    /** --color-cyan-300 */
    private val cyan300Argb: Int,
    /** --color-cyan-400 */
    private val cyan400Argb: Int,
    /** --color-cyan-50 */
    private val cyan50Argb: Int,
    /** --color-cyan-500 */
    private val cyan500Argb: Int,
    /** --color-cyan-600 */
    private val cyan600Argb: Int,
    /** --color-cyan-700 */
    private val cyan700Argb: Int,
    /** --color-cyan-900 */
    private val cyan900Argb: Int,
    /** --color-emerald-300 */
    private val emerald300Argb: Int,
    /** --color-emerald-400 */
    private val emerald400Argb: Int,
    /** --color-emerald-50 */
    private val emerald50Argb: Int,
    /** --color-emerald-500 */
    private val emerald500Argb: Int,
    /** --color-emerald-600 */
    private val emerald600Argb: Int,
    /** --color-emerald-700 */
    private val emerald700Argb: Int,
    /** --color-emerald-900 */
    private val emerald900Argb: Int,
    /** --color-fuchsia-500 */
    private val fuchsia500Argb: Int,
    /** --color-fuchsia-600 */
    private val fuchsia600Argb: Int,
    /** --color-gray-100 */
    private val gray100Argb: Int,
    /** --color-gray-400 */
    private val gray400Argb: Int,
    /** --color-gray-700 */
    private val gray700Argb: Int,
    /** --color-gray-900 */
    private val gray900Argb: Int,
    /** --color-green-100 */
    private val green100Argb: Int,
    /** --color-green-300 */
    private val green300Argb: Int,
    /** --color-green-400 */
    private val green400Argb: Int,
    /** --color-green-50 */
    private val green50Argb: Int,
    /** --color-green-500 */
    private val green500Argb: Int,
    /** --color-green-600 */
    private val green600Argb: Int,
    /** --color-green-700 */
    private val green700Argb: Int,
    /** --color-green-900 */
    private val green900Argb: Int,
    /** --color-green-950 */
    private val green950Argb: Int,
    /** --color-indigo-400 */
    private val indigo400Argb: Int,
    /** --color-indigo-500 */
    private val indigo500Argb: Int,
    /** --color-neutral-100 */
    private val neutral100Argb: Int,
    /** --color-neutral-200 */
    private val neutral200Argb: Int,
    /** --color-neutral-300 */
    private val neutral300Argb: Int,
    /** --color-neutral-50 */
    private val neutral50Argb: Int,
    /** --color-neutral-500 */
    private val neutral500Argb: Int,
    /** --color-neutral-700 */
    private val neutral700Argb: Int,
    /** --color-neutral-800 */
    private val neutral800Argb: Int,
    /** --color-neutral-900 */
    private val neutral900Argb: Int,
    /** --color-neutral-950 */
    private val neutral950Argb: Int,
    /** --color-orange-100 */
    private val orange100Argb: Int,
    /** --color-orange-300 */
    private val orange300Argb: Int,
    /** --color-orange-400 */
    private val orange400Argb: Int,
    /** --color-orange-50 */
    private val orange50Argb: Int,
    /** --color-orange-500 */
    private val orange500Argb: Int,
    /** --color-orange-600 */
    private val orange600Argb: Int,
    /** --color-orange-700 */
    private val orange700Argb: Int,
    /** --color-orange-900 */
    private val orange900Argb: Int,
    /** --color-pink-300 */
    private val pink300Argb: Int,
    /** --color-pink-400 */
    private val pink400Argb: Int,
    /** --color-pink-50 */
    private val pink50Argb: Int,
    /** --color-pink-700 */
    private val pink700Argb: Int,
    /** --color-pink-900 */
    private val pink900Argb: Int,
    /** --color-red-100 */
    private val red100Argb: Int,
    /** --color-red-300 */
    private val red300Argb: Int,
    /** --color-red-400 */
    private val red400Argb: Int,
    /** --color-red-50 */
    private val red50Argb: Int,
    /** --color-red-500 */
    private val red500Argb: Int,
    /** --color-red-600 */
    private val red600Argb: Int,
    /** --color-red-700 */
    private val red700Argb: Int,
    /** --color-red-800 */
    private val red800Argb: Int,
    /** --color-red-900 */
    private val red900Argb: Int,
    /** --color-rose-300 */
    private val rose300Argb: Int,
    /** --color-rose-400 */
    private val rose400Argb: Int,
    /** --color-rose-50 */
    private val rose50Argb: Int,
    /** --color-rose-500 */
    private val rose500Argb: Int,
    /** --color-rose-600 */
    private val rose600Argb: Int,
    /** --color-rose-700 */
    private val rose700Argb: Int,
    /** --color-rose-900 */
    private val rose900Argb: Int,
    /** --color-sky-100 */
    private val sky100Argb: Int,
    /** --color-sky-200 */
    private val sky200Argb: Int,
    /** --color-sky-300 */
    private val sky300Argb: Int,
    /** --color-sky-400 */
    private val sky400Argb: Int,
    /** --color-sky-50 */
    private val sky50Argb: Int,
    /** --color-sky-500 */
    private val sky500Argb: Int,
    /** --color-sky-600 */
    private val sky600Argb: Int,
    /** --color-sky-700 */
    private val sky700Argb: Int,
    /** --color-sky-900 */
    private val sky900Argb: Int,
    /** --color-sky-950 */
    private val sky950Argb: Int,
    /** --color-slate-200 */
    private val slate200Argb: Int,
    /** --color-slate-300 */
    private val slate300Argb: Int,
    /** --color-slate-50 */
    private val slate50Argb: Int,
    /** --color-slate-500 */
    private val slate500Argb: Int,
    /** --color-slate-600 */
    private val slate600Argb: Int,
    /** --color-slate-700 */
    private val slate700Argb: Int,
    /** --color-slate-900 */
    private val slate900Argb: Int,
    /** --color-teal-300 */
    private val teal300Argb: Int,
    /** --color-teal-400 */
    private val teal400Argb: Int,
    /** --color-teal-600 */
    private val teal600Argb: Int,
    /** --color-violet-300 */
    private val violet300Argb: Int,
    /** --color-violet-400 */
    private val violet400Argb: Int,
    /** --color-violet-50 */
    private val violet50Argb: Int,
    /** --color-violet-500 */
    private val violet500Argb: Int,
    /** --color-violet-600 */
    private val violet600Argb: Int,
    /** --color-violet-700 */
    private val violet700Argb: Int,
    /** --color-violet-900 */
    private val violet900Argb: Int,
    /** --color-violet-950 */
    private val violet950Argb: Int,
    /** --color-white */
    private val whiteArgb: Int,
    /** --color-yellow-100 */
    private val yellow100Argb: Int,
    /** --color-yellow-300 */
    private val yellow300Argb: Int,
    /** --color-yellow-400 */
    private val yellow400Argb: Int,
    /** --color-yellow-500 */
    private val yellow500Argb: Int,
    /** --color-yellow-600 */
    private val yellow600Argb: Int,
    /** --color-yellow-700 */
    private val yellow700Argb: Int,
    /** --color-yellow-900 */
    private val yellow900Argb: Int,
    /** --color-zinc-100 */
    private val zinc100Argb: Int,
    /** --color-zinc-300 */
    private val zinc300Argb: Int,
    /** --color-zinc-400 */
    private val zinc400Argb: Int,
    /** --color-zinc-500 */
    private val zinc500Argb: Int,
    /** --color-zinc-800 */
    private val zinc800Argb: Int,
    /** --color-zinc-950 */
    private val zinc950Argb: Int,
) {
    /** --color-amber-300 */
    val amber300: Color = Color(amber300Argb)
    /** --color-amber-400 */
    val amber400: Color = Color(amber400Argb)
    /** --color-amber-50 */
    val amber50: Color = Color(amber50Argb)
    /** --color-amber-500 */
    val amber500: Color = Color(amber500Argb)
    /** --color-amber-600 */
    val amber600: Color = Color(amber600Argb)
    /** --color-amber-700 */
    val amber700: Color = Color(amber700Argb)
    /** --color-amber-900 */
    val amber900: Color = Color(amber900Argb)
    /** --color-black */
    val black: Color = Color(blackArgb)
    /** --color-blue-100 */
    val blue100: Color = Color(blue100Argb)
    /** --color-blue-200 */
    val blue200: Color = Color(blue200Argb)
    /** --color-blue-400 */
    val blue400: Color = Color(blue400Argb)
    /** --color-blue-500 */
    val blue500: Color = Color(blue500Argb)
    /** --color-blue-600 */
    val blue600: Color = Color(blue600Argb)
    /** --color-blue-700 */
    val blue700: Color = Color(blue700Argb)
    /** --color-blue-900 */
    val blue900: Color = Color(blue900Argb)
    /** --color-cyan-100 */
    val cyan100: Color = Color(cyan100Argb)
    /** --color-cyan-300 */
    val cyan300: Color = Color(cyan300Argb)
    /** --color-cyan-400 */
    val cyan400: Color = Color(cyan400Argb)
    /** --color-cyan-50 */
    val cyan50: Color = Color(cyan50Argb)
    /** --color-cyan-500 */
    val cyan500: Color = Color(cyan500Argb)
    /** --color-cyan-600 */
    val cyan600: Color = Color(cyan600Argb)
    /** --color-cyan-700 */
    val cyan700: Color = Color(cyan700Argb)
    /** --color-cyan-900 */
    val cyan900: Color = Color(cyan900Argb)
    /** --color-emerald-300 */
    val emerald300: Color = Color(emerald300Argb)
    /** --color-emerald-400 */
    val emerald400: Color = Color(emerald400Argb)
    /** --color-emerald-50 */
    val emerald50: Color = Color(emerald50Argb)
    /** --color-emerald-500 */
    val emerald500: Color = Color(emerald500Argb)
    /** --color-emerald-600 */
    val emerald600: Color = Color(emerald600Argb)
    /** --color-emerald-700 */
    val emerald700: Color = Color(emerald700Argb)
    /** --color-emerald-900 */
    val emerald900: Color = Color(emerald900Argb)
    /** --color-fuchsia-500 */
    val fuchsia500: Color = Color(fuchsia500Argb)
    /** --color-fuchsia-600 */
    val fuchsia600: Color = Color(fuchsia600Argb)
    /** --color-gray-100 */
    val gray100: Color = Color(gray100Argb)
    /** --color-gray-400 */
    val gray400: Color = Color(gray400Argb)
    /** --color-gray-700 */
    val gray700: Color = Color(gray700Argb)
    /** --color-gray-900 */
    val gray900: Color = Color(gray900Argb)
    /** --color-green-100 */
    val green100: Color = Color(green100Argb)
    /** --color-green-300 */
    val green300: Color = Color(green300Argb)
    /** --color-green-400 */
    val green400: Color = Color(green400Argb)
    /** --color-green-50 */
    val green50: Color = Color(green50Argb)
    /** --color-green-500 */
    val green500: Color = Color(green500Argb)
    /** --color-green-600 */
    val green600: Color = Color(green600Argb)
    /** --color-green-700 */
    val green700: Color = Color(green700Argb)
    /** --color-green-900 */
    val green900: Color = Color(green900Argb)
    /** --color-green-950 */
    val green950: Color = Color(green950Argb)
    /** --color-indigo-400 */
    val indigo400: Color = Color(indigo400Argb)
    /** --color-indigo-500 */
    val indigo500: Color = Color(indigo500Argb)
    /** --color-neutral-100 */
    val neutral100: Color = Color(neutral100Argb)
    /** --color-neutral-200 */
    val neutral200: Color = Color(neutral200Argb)
    /** --color-neutral-300 */
    val neutral300: Color = Color(neutral300Argb)
    /** --color-neutral-50 */
    val neutral50: Color = Color(neutral50Argb)
    /** --color-neutral-500 */
    val neutral500: Color = Color(neutral500Argb)
    /** --color-neutral-700 */
    val neutral700: Color = Color(neutral700Argb)
    /** --color-neutral-800 */
    val neutral800: Color = Color(neutral800Argb)
    /** --color-neutral-900 */
    val neutral900: Color = Color(neutral900Argb)
    /** --color-neutral-950 */
    val neutral950: Color = Color(neutral950Argb)
    /** --color-orange-100 */
    val orange100: Color = Color(orange100Argb)
    /** --color-orange-300 */
    val orange300: Color = Color(orange300Argb)
    /** --color-orange-400 */
    val orange400: Color = Color(orange400Argb)
    /** --color-orange-50 */
    val orange50: Color = Color(orange50Argb)
    /** --color-orange-500 */
    val orange500: Color = Color(orange500Argb)
    /** --color-orange-600 */
    val orange600: Color = Color(orange600Argb)
    /** --color-orange-700 */
    val orange700: Color = Color(orange700Argb)
    /** --color-orange-900 */
    val orange900: Color = Color(orange900Argb)
    /** --color-pink-300 */
    val pink300: Color = Color(pink300Argb)
    /** --color-pink-400 */
    val pink400: Color = Color(pink400Argb)
    /** --color-pink-50 */
    val pink50: Color = Color(pink50Argb)
    /** --color-pink-700 */
    val pink700: Color = Color(pink700Argb)
    /** --color-pink-900 */
    val pink900: Color = Color(pink900Argb)
    /** --color-red-100 */
    val red100: Color = Color(red100Argb)
    /** --color-red-300 */
    val red300: Color = Color(red300Argb)
    /** --color-red-400 */
    val red400: Color = Color(red400Argb)
    /** --color-red-50 */
    val red50: Color = Color(red50Argb)
    /** --color-red-500 */
    val red500: Color = Color(red500Argb)
    /** --color-red-600 */
    val red600: Color = Color(red600Argb)
    /** --color-red-700 */
    val red700: Color = Color(red700Argb)
    /** --color-red-800 */
    val red800: Color = Color(red800Argb)
    /** --color-red-900 */
    val red900: Color = Color(red900Argb)
    /** --color-rose-300 */
    val rose300: Color = Color(rose300Argb)
    /** --color-rose-400 */
    val rose400: Color = Color(rose400Argb)
    /** --color-rose-50 */
    val rose50: Color = Color(rose50Argb)
    /** --color-rose-500 */
    val rose500: Color = Color(rose500Argb)
    /** --color-rose-600 */
    val rose600: Color = Color(rose600Argb)
    /** --color-rose-700 */
    val rose700: Color = Color(rose700Argb)
    /** --color-rose-900 */
    val rose900: Color = Color(rose900Argb)
    /** --color-sky-100 */
    val sky100: Color = Color(sky100Argb)
    /** --color-sky-200 */
    val sky200: Color = Color(sky200Argb)
    /** --color-sky-300 */
    val sky300: Color = Color(sky300Argb)
    /** --color-sky-400 */
    val sky400: Color = Color(sky400Argb)
    /** --color-sky-50 */
    val sky50: Color = Color(sky50Argb)
    /** --color-sky-500 */
    val sky500: Color = Color(sky500Argb)
    /** --color-sky-600 */
    val sky600: Color = Color(sky600Argb)
    /** --color-sky-700 */
    val sky700: Color = Color(sky700Argb)
    /** --color-sky-900 */
    val sky900: Color = Color(sky900Argb)
    /** --color-sky-950 */
    val sky950: Color = Color(sky950Argb)
    /** --color-slate-200 */
    val slate200: Color = Color(slate200Argb)
    /** --color-slate-300 */
    val slate300: Color = Color(slate300Argb)
    /** --color-slate-50 */
    val slate50: Color = Color(slate50Argb)
    /** --color-slate-500 */
    val slate500: Color = Color(slate500Argb)
    /** --color-slate-600 */
    val slate600: Color = Color(slate600Argb)
    /** --color-slate-700 */
    val slate700: Color = Color(slate700Argb)
    /** --color-slate-900 */
    val slate900: Color = Color(slate900Argb)
    /** --color-teal-300 */
    val teal300: Color = Color(teal300Argb)
    /** --color-teal-400 */
    val teal400: Color = Color(teal400Argb)
    /** --color-teal-600 */
    val teal600: Color = Color(teal600Argb)
    /** --color-violet-300 */
    val violet300: Color = Color(violet300Argb)
    /** --color-violet-400 */
    val violet400: Color = Color(violet400Argb)
    /** --color-violet-50 */
    val violet50: Color = Color(violet50Argb)
    /** --color-violet-500 */
    val violet500: Color = Color(violet500Argb)
    /** --color-violet-600 */
    val violet600: Color = Color(violet600Argb)
    /** --color-violet-700 */
    val violet700: Color = Color(violet700Argb)
    /** --color-violet-900 */
    val violet900: Color = Color(violet900Argb)
    /** --color-violet-950 */
    val violet950: Color = Color(violet950Argb)
    /** --color-white */
    val white: Color = Color(whiteArgb)
    /** --color-yellow-100 */
    val yellow100: Color = Color(yellow100Argb)
    /** --color-yellow-300 */
    val yellow300: Color = Color(yellow300Argb)
    /** --color-yellow-400 */
    val yellow400: Color = Color(yellow400Argb)
    /** --color-yellow-500 */
    val yellow500: Color = Color(yellow500Argb)
    /** --color-yellow-600 */
    val yellow600: Color = Color(yellow600Argb)
    /** --color-yellow-700 */
    val yellow700: Color = Color(yellow700Argb)
    /** --color-yellow-900 */
    val yellow900: Color = Color(yellow900Argb)
    /** --color-zinc-100 */
    val zinc100: Color = Color(zinc100Argb)
    /** --color-zinc-300 */
    val zinc300: Color = Color(zinc300Argb)
    /** --color-zinc-400 */
    val zinc400: Color = Color(zinc400Argb)
    /** --color-zinc-500 */
    val zinc500: Color = Color(zinc500Argb)
    /** --color-zinc-800 */
    val zinc800: Color = Color(zinc800Argb)
    /** --color-zinc-950 */
    val zinc950: Color = Color(zinc950Argb)

    companion object {
        val Default: ZemotePalette = ZemotePalette(
            amber300Argb = 0xFFFFD230.toInt(),
            amber400Argb = 0xFFFFB900.toInt(),
            amber50Argb = 0xFFFFFBEB.toInt(),
            amber500Argb = 0xFFFE9A00.toInt(),
            amber600Argb = 0xFFE17100.toInt(),
            amber700Argb = 0xFFBB4D00.toInt(),
            amber900Argb = 0xFF7B3306.toInt(),
            blackArgb = 0xFF000000.toInt(),
            blue100Argb = 0xFFDBEAFE.toInt(),
            blue200Argb = 0xFFBEDBFF.toInt(),
            blue400Argb = 0xFF51A2FF.toInt(),
            blue500Argb = 0xFF2B7FFF.toInt(),
            blue600Argb = 0xFF155DFC.toInt(),
            blue700Argb = 0xFF1447E6.toInt(),
            blue900Argb = 0xFF1C398E.toInt(),
            cyan100Argb = 0xFFCEFAFE.toInt(),
            cyan300Argb = 0xFF53EAFD.toInt(),
            cyan400Argb = 0xFF00D3F2.toInt(),
            cyan50Argb = 0xFFECFEFF.toInt(),
            cyan500Argb = 0xFF00B8DB.toInt(),
            cyan600Argb = 0xFF0092B8.toInt(),
            cyan700Argb = 0xFF007595.toInt(),
            cyan900Argb = 0xFF104E64.toInt(),
            emerald300Argb = 0xFF5EE9B5.toInt(),
            emerald400Argb = 0xFF00D492.toInt(),
            emerald50Argb = 0xFFECFDF5.toInt(),
            emerald500Argb = 0xFF00BC7D.toInt(),
            emerald600Argb = 0xFF009966.toInt(),
            emerald700Argb = 0xFF007A55.toInt(),
            emerald900Argb = 0xFF004F3B.toInt(),
            fuchsia500Argb = 0xFFE12AFB.toInt(),
            fuchsia600Argb = 0xFFC800DE.toInt(),
            gray100Argb = 0xFFF3F4F6.toInt(),
            gray400Argb = 0xFF99A1AF.toInt(),
            gray700Argb = 0xFF364153.toInt(),
            gray900Argb = 0xFF101828.toInt(),
            green100Argb = 0xFFDCFCE7.toInt(),
            green300Argb = 0xFF7BF1A8.toInt(),
            green400Argb = 0xFF05DF72.toInt(),
            green50Argb = 0xFFF0FDF4.toInt(),
            green500Argb = 0xFF00C950.toInt(),
            green600Argb = 0xFF00A63E.toInt(),
            green700Argb = 0xFF008236.toInt(),
            green900Argb = 0xFF0D542B.toInt(),
            green950Argb = 0xFF032E15.toInt(),
            indigo400Argb = 0xFF7C86FF.toInt(),
            indigo500Argb = 0xFF615FFF.toInt(),
            neutral100Argb = 0xFFF5F5F5.toInt(),
            neutral200Argb = 0xFFE5E5E5.toInt(),
            neutral300Argb = 0xFFD4D4D4.toInt(),
            neutral50Argb = 0xFFFAFAFA.toInt(),
            neutral500Argb = 0xFF737373.toInt(),
            neutral700Argb = 0xFF404040.toInt(),
            neutral800Argb = 0xFF262626.toInt(),
            neutral900Argb = 0xFF171717.toInt(),
            neutral950Argb = 0xFF0A0A0A.toInt(),
            orange100Argb = 0xFFFFEDD4.toInt(),
            orange300Argb = 0xFFFFB86A.toInt(),
            orange400Argb = 0xFFFF8904.toInt(),
            orange50Argb = 0xFFFFF7ED.toInt(),
            orange500Argb = 0xFFFF6900.toInt(),
            orange600Argb = 0xFFF54900.toInt(),
            orange700Argb = 0xFFCA3500.toInt(),
            orange900Argb = 0xFF7E2A0C.toInt(),
            pink300Argb = 0xFFFDA5D5.toInt(),
            pink400Argb = 0xFFFB64B6.toInt(),
            pink50Argb = 0xFFFDF2F8.toInt(),
            pink700Argb = 0xFFC6005C.toInt(),
            pink900Argb = 0xFF861043.toInt(),
            red100Argb = 0xFFFFE2E2.toInt(),
            red300Argb = 0xFFFFA2A2.toInt(),
            red400Argb = 0xFFFF6467.toInt(),
            red50Argb = 0xFFFEF2F2.toInt(),
            red500Argb = 0xFFFB2C36.toInt(),
            red600Argb = 0xFFE7000B.toInt(),
            red700Argb = 0xFFC10007.toInt(),
            red800Argb = 0xFF9F0712.toInt(),
            red900Argb = 0xFF82181A.toInt(),
            rose300Argb = 0xFFFFA1AD.toInt(),
            rose400Argb = 0xFFFF637E.toInt(),
            rose50Argb = 0xFFFFF1F2.toInt(),
            rose500Argb = 0xFFFF2056.toInt(),
            rose600Argb = 0xFFEC003F.toInt(),
            rose700Argb = 0xFFC70036.toInt(),
            rose900Argb = 0xFF8B0836.toInt(),
            sky100Argb = 0xFFDFF2FE.toInt(),
            sky200Argb = 0xFFB8E6FE.toInt(),
            sky300Argb = 0xFF74D4FF.toInt(),
            sky400Argb = 0xFF00BCFF.toInt(),
            sky50Argb = 0xFFF0F9FF.toInt(),
            sky500Argb = 0xFF00A6F4.toInt(),
            sky600Argb = 0xFF0084D1.toInt(),
            sky700Argb = 0xFF0069A8.toInt(),
            sky900Argb = 0xFF024A70.toInt(),
            sky950Argb = 0xFF052F4A.toInt(),
            slate200Argb = 0xFFE2E8F0.toInt(),
            slate300Argb = 0xFFCAD5E2.toInt(),
            slate50Argb = 0xFFF8FAFC.toInt(),
            slate500Argb = 0xFF62748E.toInt(),
            slate600Argb = 0xFF45556C.toInt(),
            slate700Argb = 0xFF314158.toInt(),
            slate900Argb = 0xFF0F172B.toInt(),
            teal300Argb = 0xFF46ECD5.toInt(),
            teal400Argb = 0xFF00D5BE.toInt(),
            teal600Argb = 0xFF009689.toInt(),
            violet300Argb = 0xFFC4B4FF.toInt(),
            violet400Argb = 0xFFA684FF.toInt(),
            violet50Argb = 0xFFF5F3FF.toInt(),
            violet500Argb = 0xFF8E51FF.toInt(),
            violet600Argb = 0xFF7F22FE.toInt(),
            violet700Argb = 0xFF7008E7.toInt(),
            violet900Argb = 0xFF4D179A.toInt(),
            violet950Argb = 0xFF2F0D68.toInt(),
            whiteArgb = 0xFFFFFFFF.toInt(),
            yellow100Argb = 0xFFFEF9C2.toInt(),
            yellow300Argb = 0xFFFFDF20.toInt(),
            yellow400Argb = 0xFFFDC700.toInt(),
            yellow500Argb = 0xFFF0B100.toInt(),
            yellow600Argb = 0xFFD08700.toInt(),
            yellow700Argb = 0xFFA65F00.toInt(),
            yellow900Argb = 0xFF733E0A.toInt(),
            zinc100Argb = 0xFFF4F4F5.toInt(),
            zinc300Argb = 0xFFD4D4D8.toInt(),
            zinc400Argb = 0xFF9F9FA9.toInt(),
            zinc500Argb = 0xFF71717B.toInt(),
            zinc800Argb = 0xFF27272A.toInt(),
            zinc950Argb = 0xFF09090B.toInt(),
        )
    }
}

// ===========================================================================
//  CompositionLocal
// ===========================================================================

/** 当前主题的语义色。由 ZemoteTheme 注入。 */
val LocalZemoteColors = staticCompositionLocalOf<ZemoteColors> {
    error("LocalZemoteColors 未提供：请把内容包在 ZemoteTheme { } 里。")
}

/** Tailwind 基础调色板，不随主题变化。由 ZemoteTheme 注入。 */
val LocalZemotePalette = staticCompositionLocalOf<ZemotePalette> {
    error("LocalZemotePalette 未提供：请把内容包在 ZemoteTheme { } 里。")
}
