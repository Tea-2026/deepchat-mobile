package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.BlockKind
import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.PromptMessage
import com.deepseek.mobilechat.application.TokenEstimatorPort
import com.deepseek.mobilechat.application.context.testutil.FakeAttachmentStore
import com.deepseek.mobilechat.application.context.testutil.FakeClock
import com.deepseek.mobilechat.application.context.testutil.FakeCompactionStore
import com.deepseek.mobilechat.application.context.testutil.FakeConversationStore
import com.deepseek.mobilechat.application.context.testutil.FakeIdPort
import com.deepseek.mobilechat.application.context.testutil.FakeInjectionStore
import com.deepseek.mobilechat.application.context.testutil.FakeMessageStore
import com.deepseek.mobilechat.application.context.testutil.FakePresetStore
import com.deepseek.mobilechat.application.context.testutil.FakeProviderProfileStore
import com.deepseek.mobilechat.application.context.testutil.FixedBudgetLookup
import com.deepseek.mobilechat.application.context.testutil.testAssistantMessage
import com.deepseek.mobilechat.application.context.testutil.testAttachment
import com.deepseek.mobilechat.application.context.testutil.testCompaction
import com.deepseek.mobilechat.application.context.testutil.testConversation
import com.deepseek.mobilechat.application.context.testutil.testInjection
import com.deepseek.mobilechat.application.context.testutil.testPreset
import com.deepseek.mobilechat.application.context.testutil.testUserMessage
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.BlockSource
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.InjectionScope
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.MessagePart
import com.deepseek.mobilechat.domain.MessageRole
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.SearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAssemblerTest {

    private val conversationStore = FakeConversationStore()
    private val messageStore = FakeMessageStore()
    private val injectionStore = FakeInjectionStore()
    private val compactionStore = FakeCompactionStore()
    private val attachmentStore = FakeAttachmentStore()
    private val presetStore = FakePresetStore()
    private val profileStore = FakeProviderProfileStore()
    private val clock = FakeClock()
    private val idPort = FakeIdPort()

    private fun assembler(
        budget: Int = DEFAULT_BUDGET,
        lookup: ModelBudgetLookup = FixedBudgetLookup(budget),
        estimator: TokenEstimatorPort = HeuristicTokenEstimator(),
    ): DefaultContextAssembler = DefaultContextAssembler(
        conversationStore = conversationStore,
        messageStore = messageStore,
        injectionStore = injectionStore,
        compactionStore = compactionStore,
        presetStore = presetStore,
        providerProfileStore = profileStore,
        attachmentStore = attachmentStore,
        tokenEstimator = estimator,
        modelBudgetLookup = lookup,
        clock = clock,
        idPort = idPort,
    )

    private fun <T> Outcome<T>.requireOk(): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> throw AssertionError("expected Outcome.Ok but got $error")
    }

    // -----------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------

    @Test
    fun `assembles blocks in the documented pipeline order`() = runTest {
        conversationStore.upsert(
            testConversation(
                "c1",
                activeLeafId = "m3",
                overrides = ConversationOverrides(presetId = PresetId("p1")),
            ),
        )
        presetStore.upsert(testPreset("p1", systemPrompt = "you are helpful"))
        messageStore.append(testUserMessage("m1", "c1", "hello", seq = 1))
        messageStore.append(testAssistantMessage("m2", "c1", "hi", seq = 2, parentId = "m1"))
        messageStore.append(testUserMessage("m3", "c1", "again", seq = 3, parentId = "m2"))
        // position 0 must win over position 1 regardless of insertion order
        injectionStore.add(testInjection("i1", "c1", "persist-1", position = 1, createdAt = 1))
        injectionStore.add(testInjection("i2", "c1", "persist-0", position = 0, createdAt = 2))
        injectionStore.add(
            testInjection("e1", "c1", "ephemeral", scope = InjectionScope.EPHEMERAL, createdAt = 3),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(
            listOf(
                "system",
                "injection:i2",
                "injection:i1",
                "history:m1",
                "history:m2",
                "injection:e1",
                "current",
            ),
            assembly.blocks.map { it.id },
        )
        assertEquals("you are helpful", assembly.systemPrompt)
        assertEquals(ConversationId("c1"), assembly.conversationId)
        assertEquals(MessageId("m3"), assembly.leafId)
        assertEquals(
            listOf(null, null, MessageId("m1"), MessageId("m2"), null, MessageId("m3")),
            assembly.prompt.map { it.sourceMessageId },
        )
        assertEquals(BlockKind.CURRENT_USER, assembly.blocks.last().kind)
        // the system prompt travels in systemPrompt only, never as a prompt message
        assertTrue(assembly.prompt.none { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun `conversation system prompt wins over the bound preset`() = runTest {
        conversationStore.upsert(
            testConversation(
                "c1",
                activeLeafId = "m1",
                overrides = ConversationOverrides(
                    presetId = PresetId("p1"),
                    systemPrompt = "override prompt",
                ),
            ),
        )
        presetStore.upsert(testPreset("p1", systemPrompt = "preset prompt"))
        messageStore.append(testUserMessage("m1", "c1", "hi", seq = 1))

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals("override prompt", assembly.systemPrompt)
        assertEquals("system", assembly.blocks.first().id)
        assertEquals(BlockKind.SYSTEM_PROMPT, assembly.blocks.first().kind)
        assertEquals(HeuristicTokenEstimator().estimate("override prompt"), assembly.blocks.first().tokens)
    }

    // -----------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------

    @Test
    fun `active compaction replaces its covered history range`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m4"))
        messageStore.append(testUserMessage("m1", "c1", "one", seq = 1))
        messageStore.append(testAssistantMessage("m2", "c1", "two", seq = 2, parentId = "m1"))
        messageStore.append(testUserMessage("m3", "c1", "three", seq = 3, parentId = "m2"))
        messageStore.append(testAssistantMessage("m4", "c1", "four", seq = 4, parentId = "m3"))
        compactionStore.add(
            testCompaction("k1", "c1", from = "m1", to = "m2", summary = "earlier summary"),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(
            listOf("compaction:k1", "history:m3", "history:m4"),
            assembly.blocks.map { it.id },
        )
        val compactionBlock = assembly.blocks.first()
        assertEquals(BlockKind.COMPACTION, compactionBlock.kind)
        assertEquals("已压缩的 2 条消息", compactionBlock.title)
        assertEquals(listOf(MessageId("m1"), MessageId("m2")), compactionBlock.messageIds)
        // compaction summary is sent on its own, covered history is not sent at all
        assertEquals(
            listOf(MessageId("m1"), MessageId("m3"), MessageId("m4")),
            assembly.prompt.map { it.sourceMessageId },
        )
        assertTrue(assembly.prompt.none { it.sourceMessageId == MessageId("m2") })
    }

    @Test
    fun `undone compaction no longer replaces history`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m2"))
        messageStore.append(testUserMessage("m1", "c1", "one", seq = 1))
        messageStore.append(testAssistantMessage("m2", "c1", "two", seq = 2, parentId = "m1"))
        compactionStore.add(
            testCompaction("k1", "c1", from = "m1", to = "m2", undoneAt = 99L),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(listOf("history:m1", "history:m2"), assembly.blocks.map { it.id })
        assertTrue(assembly.blocks.none { it.kind == BlockKind.COMPACTION })
    }

    @Test
    fun `pinned messages are exempt from compaction replacement`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m4"))
        messageStore.append(testUserMessage("m1", "c1", "one", seq = 1))
        messageStore.append(
            testAssistantMessage("m2", "c1", "pinned answer", seq = 2, parentId = "m1", pinned = true),
        )
        messageStore.append(testUserMessage("m3", "c1", "three", seq = 3, parentId = "m2"))
        messageStore.append(testAssistantMessage("m4", "c1", "four", seq = 4, parentId = "m3"))
        compactionStore.add(testCompaction("k1", "c1", from = "m1", to = "m3"))

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(
            listOf("compaction:k1", "history:m2", "history:m4"),
            assembly.blocks.map { it.id },
        )
        val compactionBlock = assembly.blocks.first()
        assertEquals("已压缩的 2 条消息", compactionBlock.title)
        assertEquals(listOf(MessageId("m1"), MessageId("m3")), compactionBlock.messageIds)
        // the pinned message survives verbatim
        val pinnedPrompt = assembly.prompt.single { it.sourceMessageId == MessageId("m2") }
        assertEquals(MessageRole.ASSISTANT, pinnedPrompt.role)
        assertEquals("pinned answer", (pinnedPrompt.parts.single() as MessagePart.Text).text)
    }

    // -----------------------------------------------------------------------
    // Hanging blocks
    // -----------------------------------------------------------------------

    @Test
    fun `search and file parts on the current message become hanging blocks`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m1"))
        val searchResults = MessagePart.SearchResults(
            query = "kotlin coroutines",
            hits = listOf(
                SearchHit(
                    title = "Coroutines guide",
                    url = "https://kotlinlang.org/docs/coroutines-guide.html",
                    snippet = "A guide to kotlinx.coroutines.",
                ),
            ),
        )
        val fileRef = MessagePart.FileRef(AttachmentId("a1"))
        messageStore.append(
            testUserMessage(
                "m1",
                "c1",
                seq = 1,
                parts = listOf(MessagePart.Text("look at this"), searchResults, fileRef),
            ),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(listOf("search:1", "file:2", "current"), assembly.blocks.map { it.id })
        assertEquals(BlockKind.SEARCH, assembly.blocks[0].kind)
        assertEquals(BlockKind.FILE, assembly.blocks[1].kind)
        // the extracted payload is not sent twice: the current message keeps text only
        assertEquals(listOf(MessagePart.Text("look at this")), assembly.prompt.last().parts)
        assertEquals(MessageId("m1"), assembly.prompt.last().sourceMessageId)
        assertEquals(3, assembly.prompt.size)
    }

    @Test
    fun `file refs carry the extracted text and use the file name as title`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m1"))
        attachmentStore.put(
            testAttachment(
                id = "a1",
                fileName = "notes.txt",
                extractedText = "the extracted file body",
            ),
        )
        messageStore.append(
            testUserMessage(
                "m1",
                "c1",
                seq = 1,
                parts = listOf(
                    MessagePart.Text("please read"),
                    MessagePart.FileRef(AttachmentId("a1")),
                ),
            ),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertEquals(listOf("file:1", "current"), assembly.blocks.map { it.id })
        val fileBlock = assembly.blocks.first()
        assertEquals(BlockKind.FILE, fileBlock.kind)
        assertEquals("notes.txt", fileBlock.title)
        val filePart = assembly.prompt.first().parts.single() as MessagePart.ContextBlock
        assertEquals("notes.txt", filePart.title)
        assertEquals("the extracted file body", filePart.body)
        assertEquals(BlockSource.FILE_EXTRACT, filePart.source)
    }

    @Test
    fun `missing attachments degrade to an explicit missing-attachment body`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m1"))
        messageStore.append(
            testUserMessage(
                "m1",
                "c1",
                seq = 1,
                parts = listOf(MessagePart.FileRef(AttachmentId("gone"))),
            ),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        val fileBlock = assembly.blocks.single { it.kind == BlockKind.FILE }
        assertEquals("文件引用", fileBlock.title)
        val fileMessage = assembly.prompt.first()
        assertEquals("附件缺失：gone", (fileMessage.parts.single() as MessagePart.ContextBlock).body)
        // stripping the only part would empty the current message, so it keeps its original parts
        assertEquals(listOf(MessagePart.FileRef(AttachmentId("gone"))), assembly.prompt.last().parts)
    }

    @Test
    fun `attachments without extracted text say so and still use the file name`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m1"))
        attachmentStore.put(testAttachment(id = "a2", fileName = "scan.pdf", extractedText = null))
        messageStore.append(
            testUserMessage(
                "m1",
                "c1",
                seq = 1,
                parts = listOf(
                    MessagePart.Text("see attached"),
                    MessagePart.FileRef(AttachmentId("a2")),
                ),
            ),
        )

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        val fileBlock = assembly.blocks.single { it.kind == BlockKind.FILE }
        assertEquals("scan.pdf", fileBlock.title)
        val filePart = assembly.prompt.first().parts.single() as MessagePart.ContextBlock
        assertEquals("附件：scan.pdf（无法抽取文本）", filePart.body)
    }

    // -----------------------------------------------------------------------
    // Budget / tokens
    // -----------------------------------------------------------------------

    @Test
    fun `budget comes from the model lookup and flags overflow`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m2"))
        messageStore.append(testUserMessage("m1", "c1", "hello there", seq = 1))
        messageStore.append(testUserMessage("m2", "c1", "more", seq = 2, parentId = "m1"))

        val assembly = assembler(budget = 5).assemble(ConversationId("c1")).requireOk()

        assertEquals(5, assembly.budget.limit)
        assertTrue(assembly.budget.isOverBudget)
        assertTrue(assembly.budget.used > assembly.budget.limit)
        assertEquals(assembly.budget.used - assembly.budget.limit, assembly.budget.overflow)
    }

    @Test
    fun `constant lookup supplies known and fallback context windows`() = runTest {
        conversationStore.upsert(
            testConversation(
                "c1",
                activeLeafId = "m1",
                overrides = ConversationOverrides(
                    modelRef = ModelRef(ProviderId("openai"), ModelId("gpt-4o-mini")),
                ),
            ),
        )
        messageStore.append(testUserMessage("m1", "c1", "hi", seq = 1))

        val known = assembler(lookup = ConstantModelBudgetLookup())
            .assemble(ConversationId("c1")).requireOk()
        assertEquals(128_000, known.budget.limit)
        assertEquals(ModelRef(ProviderId("openai"), ModelId("gpt-4o-mini")), known.modelRef)
        assertFalse(known.budget.isOverBudget)

        conversationStore.upsert(
            testConversation(
                "c1",
                activeLeafId = "m1",
                overrides = ConversationOverrides(
                    modelRef = ModelRef(ProviderId("local"), ModelId("mystery-model")),
                ),
            ),
        )
        val unknown = assembler(lookup = ConstantModelBudgetLookup())
            .assemble(ConversationId("c1")).requireOk()
        assertEquals(ConstantModelBudgetLookup.DEFAULT_CONTEXT_WINDOW, unknown.budget.limit)
        assertEquals(32_000, unknown.budget.limit)
    }

    @Test
    fun `token totals are the sum of per block estimates`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m2"))
        messageStore.append(testUserMessage("m1", "c1", "abcd", seq = 1))
        messageStore.append(testUserMessage("m2", "c1", "你好世界", seq = 2, parentId = "m1"))
        val estimator = HeuristicTokenEstimator()

        val assembly = assembler(estimator = estimator).assemble(ConversationId("c1")).requireOk()

        val historyBlock = assembly.blocks.single { it.id == "history:m1" }
        val currentBlock = assembly.blocks.single { it.id == "current" }
        // "abcd" -> 1 token + 4 role overhead; "你好世界" -> 3 tokens + 4 role overhead
        assertEquals(5, historyBlock.tokens)
        assertEquals(7, currentBlock.tokens)
        assertEquals(12, assembly.budget.used)
        assertEquals(assembly.blocks.sumOf { it.tokens }, assembly.budget.used)
        assertEquals(
            estimator.estimate(PromptMessage(MessageRole.USER, listOf(MessagePart.Text("abcd")))),
            historyBlock.tokens,
        )
        assertEquals(listOf(MessageId("m1")), historyBlock.messageIds)
    }

    // -----------------------------------------------------------------------
    // Expected failures
    // -----------------------------------------------------------------------

    @Test
    fun `missing conversation, empty conversation and dangling leaf are validation errors`() = runTest {
        val missing = assembler().assemble(ConversationId("nope"))
        assertTrue(missing is Outcome.Err)
        assertTrue((missing as Outcome.Err).error is AppError.Validation)

        conversationStore.upsert(testConversation("c1", activeLeafId = null))
        val empty = assembler().assemble(ConversationId("c1"))
        assertTrue(empty is Outcome.Err)
        assertTrue((empty as Outcome.Err).error is AppError.Validation)

        conversationStore.upsert(testConversation("c2", activeLeafId = "ghost"))
        messageStore.append(testUserMessage("m1", "c2", "hi", seq = 1))
        val dangling = assembler().assemble(ConversationId("c2"))
        assertTrue(dangling is Outcome.Err)
        val error = (dangling as Outcome.Err).error
        assertTrue(error is AppError.Validation)
        assertTrue((error as AppError.Validation).message.contains("ghost"))
    }

    @Test
    fun `empty system prompt means no system block`() = runTest {
        conversationStore.upsert(testConversation("c1", activeLeafId = "m1"))
        messageStore.append(testUserMessage("m1", "c1", "hi", seq = 1))

        val assembly = assembler().assemble(ConversationId("c1")).requireOk()

        assertNull(assembly.systemPrompt)
        assertTrue(assembly.blocks.none { it.kind == BlockKind.SYSTEM_PROMPT })
        assertEquals(listOf("current"), assembly.blocks.map { it.id })
    }

    companion object {
        private const val DEFAULT_BUDGET = 32_000
    }
}
