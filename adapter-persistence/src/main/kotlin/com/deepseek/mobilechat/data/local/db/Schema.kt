package com.deepseek.mobilechat.data.local.db

/**
 * Hand-written DDL for the local store, versioned by `PRAGMA user_version`.
 *
 * Version history
 * ---------------
 * - **1** — initial schema (conversations, message tree, presets, providers,
 *   injections, compactions, folders/tags, attachments, FTS4 index).
 * - **2** — `ThinkingLevel.MEDIUM` folded into `HIGH` (the enum is now
 *   `OFF/LOW/HIGH/MAX`) in `presets` and in the JSON columns that persist the
 *   enum name, and `attachments.conversation_id` relaxed to NULL so an
 *   attachment picked for a not-yet-persisted draft conversation has a home
 *   ([migrateV1ToV2] rebuilds that table).
 *
 * The FTS index is an FTS4 **external content** table over `messages.plain_text`
 * and is kept in sync by SQL triggers (see [CREATE_STATEMENTS]). Triggers were
 * chosen over hand-written dual writes because:
 *  - every write path (append, update, cascade delete, future import/bulk load)
 *    goes through `messages` and therefore can never forget the index;
 *  - streaming checkpoints update `plain_text` only, and the `OF plain_text` /
 *    `WHEN old.plain_text IS NOT new.plain_text` guards limit re-indexing to
 *    writes that actually change the indexed text;
 *  - `ON DELETE CASCADE` removal of a conversation reaches the triggers as well,
 *    so deleted rows cannot leave stale FTS entries behind.
 *
 * For external-content FTS4, SQLite resolves the terms to remove from the
 * content table, so index rows are removed in **BEFORE** DELETE/UPDATE triggers
 * (the old text is still visible there) and re-added by **AFTER** triggers.
 * The `'delete'` special command documented for contentless tables is rejected
 * here with "SQL logic error"; `DELETE FROM messages_fts WHERE docid = ?` is the
 * form this engine accepts and is the one the triggers use.
 */
object Schema {

    const val VERSION: Int = 2

    const val DATABASE_NAME: String = "mobilechat.db"

    /** Every statement needed to build the schema from scratch. Idempotent. */
    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS folders (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            sort_order INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS tags (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            color INTEGER NOT NULL DEFAULT 4285231744
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS conversations (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            active_leaf_id TEXT,
            folder_id TEXT REFERENCES folders(id) ON DELETE SET NULL,
            overrides_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            pinned INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY NOT NULL,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            parent_id TEXT REFERENCES messages(id) ON DELETE CASCADE,
            seq INTEGER NOT NULL DEFAULT 0,
            role TEXT NOT NULL,
            status TEXT NOT NULL,
            parts_json TEXT NOT NULL DEFAULT '{"v":1,"parts":[]}',
            plain_text TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            edited_at INTEGER,
            pinned INTEGER NOT NULL DEFAULT 0,
            model_info_json TEXT,
            usage_json TEXT,
            finish_reason TEXT,
            error TEXT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS presets (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            system_prompt TEXT NOT NULL DEFAULT '',
            model_ref_json TEXT,
            params_json TEXT NOT NULL DEFAULT '{}',
            thinking_level TEXT NOT NULL DEFAULT 'OFF',
            context_strategy_id TEXT NOT NULL DEFAULT 'confirm-compaction',
            is_default INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS provider_profiles (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            protocol TEXT NOT NULL,
            base_url TEXT NOT NULL,
            secret_ref TEXT NOT NULL,
            default_model_id TEXT,
            extra_headers_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS injections (
            id TEXT PRIMARY KEY NOT NULL,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            anchor_message_id TEXT,
            scope TEXT NOT NULL,
            position INTEGER NOT NULL DEFAULT 0,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS compactions (
            id TEXT PRIMARY KEY NOT NULL,
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            from_message_id TEXT NOT NULL,
            to_message_id TEXT NOT NULL,
            summary TEXT NOT NULL,
            kind TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            undone_at INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS conversation_tags (
            conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            tag_id TEXT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY (conversation_id, tag_id)
        )
        """.trimIndent(),
        // v2 shape: `conversation_id` is nullable on purpose. A draft
        // conversation is not persisted before its first message, so an
        // attachment picked for it is stored with a NULL owner and re-linked by
        // `AttachmentStore.linkToConversation` once the conversation exists.
        // The FK (and its cascade) is kept so linked rows still die with their
        // conversation.
        """
        CREATE TABLE IF NOT EXISTS attachments (
            id TEXT PRIMARY KEY NOT NULL,
            conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
            file_name TEXT NOT NULL,
            mime_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            local_path TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            kind TEXT NOT NULL,
            extracted_text TEXT,
            width INTEGER,
            height INTEGER,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at DESC, created_at DESC)",
        "CREATE INDEX IF NOT EXISTS idx_conversations_folder ON conversations(folder_id)",
        "CREATE INDEX IF NOT EXISTS idx_messages_tree ON messages(conversation_id, parent_id, seq)",
        "CREATE INDEX IF NOT EXISTS idx_messages_time ON messages(conversation_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_injections_conversation ON injections(conversation_id, position)",
        "CREATE INDEX IF NOT EXISTS idx_compactions_conversation ON compactions(conversation_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_attachments_conversation ON attachments(conversation_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_conversation_tags_tag ON conversation_tags(tag_id)",
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(
            plain_text,
            content=messages,
            tokenize=unicode61
        )
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages BEGIN
            INSERT INTO messages_fts(docid, plain_text) VALUES (new.rowid, new.plain_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_bd BEFORE DELETE ON messages BEGIN
            DELETE FROM messages_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_bu BEFORE UPDATE OF plain_text ON messages
        WHEN old.plain_text IS NOT new.plain_text
        BEGIN
            DELETE FROM messages_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE OF plain_text ON messages
        WHEN old.plain_text IS NOT new.plain_text
        BEGIN
            INSERT INTO messages_fts(docid, plain_text) VALUES (new.rowid, new.plain_text);
        END
        """.trimIndent(),
    )

    /** Creates the schema for a fresh database and stamps [VERSION]. */
    fun create(db: Db) {
        db.transaction {
            CREATE_STATEMENTS.forEach(db::exec)
            db.setUserVersion(VERSION)
        }
    }

    /**
     * Runs every registered migration step between [fromVersion] and
     * [toVersion]. `fromVersion == 0` means "fresh database" and builds the
     * current schema.
     *
     * Steps are keyed by the version they migrate *from*; each step must update
     * the schema only — [migrate] bumps `user_version` itself after the step.
     */
    fun migrate(db: Db, fromVersion: Int, toVersion: Int = VERSION) {
        require(fromVersion >= 0) { "fromVersion must be >= 0, was $fromVersion" }
        require(toVersion >= fromVersion) { "downgrade from $fromVersion to $toVersion is not supported" }
        if (fromVersion == toVersion) return

        db.transaction {
            var version = fromVersion
            if (version == 0) {
                require(toVersion >= VERSION) {
                    "a fresh database must be created at version $VERSION (requested $toVersion)"
                }
                CREATE_STATEMENTS.forEach(db::exec)
                db.setUserVersion(VERSION)
                version = VERSION
            }
            while (version < toVersion) {
                val step = MIGRATIONS[version]
                    ?: throw DbException("no migration registered from schema version $version")
                step(db)
                version += 1
                db.setUserVersion(version)
            }
        }
    }

    /** Rebuilds the FTS index from `messages.plain_text` (repair / import helper). */
    fun rebuildFullTextIndex(db: Db) {
        db.exec("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
    }

    private val MIGRATIONS: Map<Int, (Db) -> Unit> = mapOf(
        1 to ::migrateV1ToV2,
    )

    /**
     * v1 -> v2.
     *
     * Two domain contracts changed and one has to follow in the schema:
     *
     *  1. `ThinkingLevel` dropped `MEDIUM` (`OFF/LOW/MEDIUM/HIGH` became
     *     `OFF/LOW/HIGH/MAX`). A stored `MEDIUM` makes `ThinkingLevel.valueOf`
     *     throw, so every copy written by v1 is rewritten to `HIGH` — the level
     *     `MEDIUM` used to select. Besides `presets.thinking_level` the enum
     *     name sits in two JSON blobs that are decoded with `valueOf` as well:
     *     `conversations.overrides_json` and `messages.model_info_json`. Both
     *     are produced by `JsonCodecs` as compact JSON (no whitespace), hence
     *     the literal `replace`; the quoted key/value pair cannot occur inside a
     *     string value because those quotes are escaped, and `params` only
     *     carries numbers.
     *  2. `attachments.conversation_id` must accept NULL: a draft conversation
     *     has no row yet, so a draft attachment points at nothing. SQLite cannot
     *     relax a NOT NULL constraint in place, so the table is rebuilt with the
     *     documented "12-step" pattern reduced to the one table involved
     *     (create -> explicit-column copy -> drop -> rename -> recreate index).
     *     Both column lists are spelled out instead of `SELECT *` on purpose: a
     *     positional copy would silently shift data if a future DDL edit
     *     reorders columns.
     *
     * Foreign keys are left enabled:
     *  - `attachments` is the *child* side of its only FK, so the implicit
     *    `DELETE FROM attachments` that `DROP TABLE` performs cannot violate
     *    anything;
     *  - `PRAGMA foreign_keys` is a no-op inside a transaction and Android's
     *    `SQLiteOpenHelper` already wraps `onUpgrade` in one, so an OFF/ON pair
     *    here could never take effect on the phone — it would only make the JVM
     *    tests validate a different configuration than the device runs.
     *    `MigrationTest` instead runs this step inside an outer transaction with
     *    foreign keys ON (like the device) and asserts the FK and
     *    `ON DELETE CASCADE` still work afterwards.
     *
     * `messages_fts` is an external-content index over `messages` and no FTS
     * trigger mentions `attachments`, so the rebuild leaves search untouched.
     */
    private fun migrateV1ToV2(db: Db) {
        // (1) ThinkingLevel.MEDIUM no longer exists.
        db.exec("UPDATE presets SET thinking_level = 'HIGH' WHERE thinking_level = 'MEDIUM'")
        db.exec(
            """
            UPDATE conversations
            SET overrides_json = replace(
                overrides_json, '"thinkingLevel":"MEDIUM"', '"thinkingLevel":"HIGH"'
            )
            WHERE overrides_json LIKE '%"thinkingLevel":"MEDIUM"%'
            """.trimIndent(),
        )
        db.exec(
            """
            UPDATE messages
            SET model_info_json = replace(
                model_info_json, '"thinkingLevel":"MEDIUM"', '"thinkingLevel":"HIGH"'
            )
            WHERE model_info_json LIKE '%"thinkingLevel":"MEDIUM"%'
            """.trimIndent(),
        )

        // (2) attachments.conversation_id: NOT NULL -> NULL.
        db.exec(
            """
            CREATE TABLE attachments_new (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT REFERENCES conversations(id) ON DELETE CASCADE,
                file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                local_path TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                kind TEXT NOT NULL,
                extracted_text TEXT,
                width INTEGER,
                height INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.exec(
            """
            INSERT INTO attachments_new
                (id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256,
                 kind, extracted_text, width, height, created_at)
            SELECT
                id, conversation_id, file_name, mime_type, size_bytes, local_path, sha256,
                kind, extracted_text, width, height, created_at
            FROM attachments
            """.trimIndent(),
        )
        db.exec("DROP TABLE attachments")
        db.exec("ALTER TABLE attachments_new RENAME TO attachments")
        // Dropping the old table dropped its indexes with it; restore the one
        // v1 had so `attachments.observe` stays indexed.
        db.exec(
            "CREATE INDEX IF NOT EXISTS idx_attachments_conversation " +
                "ON attachments(conversation_id, created_at)",
        )
    }
}
