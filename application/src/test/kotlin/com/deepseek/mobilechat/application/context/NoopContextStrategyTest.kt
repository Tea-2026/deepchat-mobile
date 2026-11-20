package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.ContextPlan
import com.deepseek.mobilechat.application.context.strategy.NoopContextStrategy
import com.deepseek.mobilechat.application.context.testutil.testAssembly
import com.deepseek.mobilechat.domain.ContextStrategyIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoopContextStrategyTest {

    private val strategy = NoopContextStrategy()

    @Test
    fun `fits when the assembly is within budget`() = runTest {
        val within = testAssembly(limit = 100, used = 60)
        assertSame(ContextPlan.Fits, strategy.plan(within, within.budget) { "unused" })

        val exactlyAtLimit = testAssembly(limit = 100, used = 100)
        assertSame(ContextPlan.Fits, strategy.plan(exactlyAtLimit, exactlyAtLimit.budget) { "unused" })
        assertEquals(ContextStrategyIds.NONE, strategy.id)
    }

    @Test
    fun `rejects when the assembly exceeds budget`() = runTest {
        val over = testAssembly(limit = 100, used = 140)

        val plan = strategy.plan(over, over.budget) { "unused" }

        assertTrue(plan is ContextPlan.Rejected)
        assertEquals("context exceeds budget", (plan as ContextPlan.Rejected).error.message)
    }
}
