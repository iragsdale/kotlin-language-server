package org.javacs.kt

import com.intellij.openapi.util.TextRange
import org.javacs.kt.compiler.PARSER
import org.javacs.kt.compiler.compileFully
import org.javacs.kt.compiler.compileIncrementally
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import java.net.URI

class LiveFile(private val fileName: URI, private var text: String) {
    private var file = PARSER.createFile(text)
    private var analyze = compileFully(file)
    /** For testing */
    var reAnalyzed = false

    /**
     * Try to re-analyze a section of the file, but fall back on full re-compilation if necessary
     */
    fun recover(newText: String, cursor: Int): CompilerSession? {
        val strategy = recoverStrategy(newText, cursor) ?: return null
        val textOffset = strategy.oldRange.startOffset

        return CompilerSession(strategy.newExpr, strategy.newContext, cursor, textOffset)
    }

    private fun recoverStrategy(newText: String, cursor: Int): RecoveryStrategy? {
        reAnalyzed = false

        // If there are no changes, we can use the existing analyze
        val changed = changedRegion(newText) ?: return NoChanges()
        // Look for a recoverable expression around the cursor
        val strategy = recoverAt(newText, cursor) ?: return fullRecompile(newText)
        // If the expression that we're going to re-compile doesn't include all the changes, give up
        if (!strategy.willRepair.contains(changed)) return fullRecompile(newText)

        return strategy
    }

    private fun fullRecompile(newText: String): RecoveryStrategy? {
        reAnalyze(newText)
        return NoChanges()
    }

    /**
     * Re-analyze the document if it has changed too much to be analyzed incrementally
     */
    private fun reAnalyze(newText: String) {
        LOG.info("Re-analyzing $fileName")

        text = newText
        file = PARSER.createFile(newText)
        analyze = compileFully(file)
        reAnalyzed = true
    }

    /**
     * Region that has been changed
     */
    private fun changedRegion(newText: String): TextRange? {
        if (text == newText) return null

        val prefix = text.commonPrefixWith(newText).length
        val suffix = text.commonSuffixWith(newText).length
        var end = text.length - suffix
        if (end > prefix) end = prefix

        return TextRange(prefix, end)
    }

    private fun recoverAt(newText: String, cursor: Int): RecoveryStrategy? {
        val leaf = file.findElementAt(cursor) ?: return null
        val elements = leaf.parentsWithSelf.filterIsInstance<KtElement>()
        val hasScope = elements.mapNotNull { recoveryStrategy(newText, it)}
        return hasScope.firstOrNull()
    }

    fun recoveryStrategy(newText: String, element: KtElement): RecoveryStrategy? {
        return when (element) {
            is KtNamedFunction -> {
                val start = element.textRange.startOffset
                val end = element.textRange.endOffset + newText.length - text.length
                val exprText = newText.substring(start, end)
                val expr = PARSER.createFunction(exprText)
                ReparseFunction(element, expr)
            }
            else -> null
        }
    }

    interface RecoveryStrategy {
        val oldRange: TextRange
        val willRepair: TextRange
        val oldExpr: KtElement
        val newExpr: KtElement
        val newContext: BindingContext
    }

    inner class ReparseFunction(override val oldExpr: KtNamedFunction, override val newExpr: KtNamedFunction): RecoveryStrategy {
        private val oldScope = analyze.bindingContext.get(BindingContext.LEXICAL_SCOPE, oldExpr.bodyExpression)!!
        override val oldRange = oldExpr.textRange
        override val willRepair = oldExpr.bodyExpression!!.textRange
        override val newContext = compileIncrementally(newExpr, oldScope)
    }

    inner class NoChanges: RecoveryStrategy {
        override val oldRange = file.textRange
        override val willRepair = file.textRange
        override val oldExpr = file
        override val newExpr = file
        override val newContext = analyze.bindingContext
    }
}