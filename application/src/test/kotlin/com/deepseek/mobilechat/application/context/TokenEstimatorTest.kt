package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenEstimatorTest {

    private val estimator = HeuristicTokenEstimator()

    @Test
    fun `empty text costs nothing`() {
        assertEquals(0, estimator.estimate(""))
    }

    @Test
    fun `ascii counts four characters per token`() {
        assertEquals(1, estimator.estimate("a"))
        assertEquals(1, estimator.estimate("abcd"))
        assertEquals(2, estimator.estimate("abcde"))
        assertEquals(2, estimator.estimate("abcdefgh"))
    }

    @Test
    fun `cjk counts one point six characters per token`() {
        assertEquals(1, estimator.estimate("你"))
        assertEquals(2, estimator.estimate("你好"))
        assertEquals(3, estimator.estimate("你好世界"))
        assertEquals(5, estimator.estimate("你好世界你好世界"))
    }

    @Test
    fun `ascii and cjk accumulate in separate buckets`() {
        // 3 ascii = 0.75 token, 2 cjk = 1.25 token -> 2 tokens in total
        assertEquals(2, estimator.estimate("abc你好"))
        // 4 ascii = 1 token, 2 cjk = 1.25 token -> 3 tokens in total
        assertEquals(3, estimator.estimate("abcd你好"))
    }

    @Test
    fun `each image part costs the flat image estimate`() {
        val one = PromptMessage(
            MessageRole.USER,
            listOf(MessagePart.Image(AttachmentId("a1"))),
        )
        assertEquals(4 + HeuristicTokenEstimator.IMAGE_TOKENS, estimator.estimate(one))
        assertEquals(804, estimator.estimate(one))

        val two = PromptMessage(
            MessageRole.USER,
            listOf(MessagePart.Image(AttachmentId("a1")), MessagePart.Image(AttachmentId("a2"))),
        )
        assertEquals(4 + 2 * HeuristicTokenEstimator.IMAGE_TOKENS, estimator.estimate(two))
    }

    @Test
    fun `reasoning is counted exactly like text`() {
        val text = PromptMessage(MessageRole.ASSISTANT, listOf(MessagePart.Text("abcd")))
        val reasoning = PromptMessage(
            MessageRole.ASSISTANT,
            listOf(MessagePart.Reasoning("abcd", signature = "sig")),
        )
        assertEquals(estimator.estimate(text), estimator.estimate(reasoning))
        assertEquals(5, estimator.estimate(reasoning))
    }

    @Test
    fun `every prompt message pays the role overhead`() {
        assertEquals(4, estimator.estimate(PromptMessage(MessageRole.USER, emptyList())))
        assertEquals(
            4 + estimator.estimate("body"),
            estimator.estimate(PromptMessage(MessageRole.USER, listOf(MessagePart.Text("body")))),
        )
    }

    @Test
    fun `context blocks and search results are estimated from their text`() {
        val block = PromptMessage(
            MessageRole.USER,
            listOf(
                MessagePart.ContextBlock(
                    title = "搜索结果",
                    body = "a snippet of the result",
                    source = BlockSource.SEARCH,
                ),
            ),
        )
        assertTrue(estimator.estimate(block) > 4)

        val results = MessagePart.SearchResults(
            query = "kotlin",
            hits = listOf(
                SearchHit("Kotlin", "https://kotlinlang.org", "Modern programming language"),
            ),
        )
        assertTrue(estimator.estimate(PromptMessage(MessageRole.USER, listOf(results))) > 4)
    }

    @Test
    fun `file refs carry no text and cost nothing on their own`() {
        val fileRef = PromptMessage(MessageRole.USER, listOf(MessagePart.FileRef(AttachmentId("a1"))))
        assertEquals(4, estimator.estimate(fileRef))
    }
}
