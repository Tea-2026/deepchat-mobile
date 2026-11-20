package com.deepseek.mobilechat.application.context.strategy

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.ContextAssembly
import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.ContextStrategy
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.domain.ContextBudget
import com.deepseek.mobilechat.domain.ContextStrategyIds

/**
 * Strategy for conversations that must never be compacted: either everything
 * fits, or the request is refused with a validation error. This is what the
 * `ContextStrategyIds.NONE` preset selects.
 */
class NoopContextStrategy : ContextStrategy {

    override val id: String = ContextStrategyIds.NONE

    override suspend fun plan(
        assembly: ContextAssembly,
        budget: ContextBudget,
        summarize: suspend (List<PromptMessage>) -> String,
    ): ContextPlan = if (budget.isOverBudget) {
        ContextPlan.Rejected(AppError.Validation("context exceeds budget"))
    } else {
        ContextPlan.Fits
    }
}
