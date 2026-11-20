package com.deepseek.mobilechat.data.local

import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.Folder
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.Tag
import com.deepseek.mobilechat.domain.TagId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTagStoreTest {

    @Test
    fun foldersRoundTripAndOrderBySortOrder() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertFolder(Folder(FolderId("f2"), "Later", order = 2))
            env.foldersAndTags.upsertFolder(Folder(FolderId("f1"), "First", order = 1))

            assertEquals(
                listOf("f1", "f2"),
                env.foldersAndTags.observeFolders().first().map { it.id.raw },
            )

            env.foldersAndTags.upsertFolder(Folder(FolderId("f2"), "Renamed", order = 2))
            assertEquals(2, env.foldersAndTags.observeFolders().first().size)
            assertEquals("Renamed", env.foldersAndTags.observeFolders().first().last().name)
        }
    }

    @Test
    fun tagsRoundTripColour() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertTag(Tag(TagId("t1"), "Work", color = 0xFFFF0000L))
            env.foldersAndTags.upsertTag(Tag(TagId("t2"), "Home"))

            val tags = env.foldersAndTags.observeTags().first()
            assertEquals(listOf("Home", "Work"), tags.map { it.name })
            assertEquals(0xFFFF0000L, tags.last().color)
        }
    }

    @Test
    fun deletingFolderDetachesConversations() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertFolder(Folder(FolderId("f1"), "Work"))
            env.conversations.upsert(conversation("c1", folderId = "f1"))

            env.foldersAndTags.deleteFolder(FolderId("f1"))

            assertNull(env.conversations.get(ConversationId("c1"))!!.folderId)
            assertTrue(env.foldersAndTags.observeFolders().first().isEmpty())
        }
    }

    @Test
    fun deletingTagRemovesItFromConversations() = runBlocking {
        TestEnv().use { env ->
            env.foldersAndTags.upsertTag(Tag(TagId("t1"), "Work"))
            env.foldersAndTags.upsertTag(Tag(TagId("t2"), "Home"))
            env.conversations.upsert(conversation("c1", tagIds = setOf("t1", "t2")))

            env.foldersAndTags.deleteTag(TagId("t1"))

            assertEquals(setOf(TagId("t2")), env.conversations.get(ConversationId("c1"))!!.tagIds)
            assertTrue(env.foldersAndTags.observeTags().first().none { it.id == TagId("t1") })
        }
    }
}
