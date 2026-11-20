package com.deepseek.mobilechat.application.service.testutil

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.domain.Attachment
import com.deepseek.mobilechat.domain.AttachmentId
import com.deepseek.mobilechat.domain.AttachmentKind
import com.deepseek.mobilechat.domain.Conversation
import com.deepseek.mobilechat.domain.ConversationId
import com.deepseek.mobilechat.domain.ConversationOverrides
import com.deepseek.mobilechat.domain.FolderId
import com.deepseek.mobilechat.domain.MessageId
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelPricing
import com.deepseek.mobilechat.domain.ModelRef
import com.deepseek.mobilechat.domain.Preset
import com.deepseek.mobilechat.domain.PresetId
import com.deepseek.mobilechat.domain.ProviderId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.ProviderProtocol
import com.deepseek.mobilechat.domain.SamplingParams
import com.deepseek.mobilechat.domain.ThinkingLevel

fun conversation(
    id: String,
    title: String = "",
    activeLeafId: String? = null,
    overrides: ConversationOverrides = ConversationOverrides(),
    folderId: String? = null,
): Conversation = Conversation(
    id = ConversationId(id),
    title = title,
    activeLeafId = activeLeafId?.let { MessageId(it) },
    folderId = folderId?.let { FolderId(it) },
    overrides = overrides,
    createdAt = 0L,
    updatedAt = 0L,
)

fun preset(
    id: String,
    isDefault: Boolean = false,
    systemPrompt: String = "",
    modelRef: ModelRef? = null,
    contextStrategyId: String = "confirm-compaction",
): Preset = Preset(
    id = PresetId(id),
    name = id,
    systemPrompt = systemPrompt,
    modelRef = modelRef,
    params = SamplingParams(),
    thinkingLevel = ThinkingLevel.OFF,
    contextStrategyId = contextStrategyId,
    isDefault = isDefault,
)

fun providerProfile(
    id: String = "provider-1",
    name: String = id,
    secretRef: String = "provider.$id",
    defaultModelId: String? = "model-1",
): ProviderProfile = ProviderProfile(
    id = ProviderId(id),
    name = name,
    protocol = ProviderProtocol.OPENAI_COMPATIBLE,
    baseUrl = "https://example.invalid",
    secretRef = secretRef,
    defaultModelId = defaultModelId?.let { com.deepseek.mobilechat.domain.ModelId(it) },
    createdAt = 0L,
)

fun descriptor(
    id: String = "model-1",
    inputPerMTok: Double? = 1.0,
    outputPerMTok: Double? = 2.0,
): ModelDescriptor = ModelDescriptor(
    id = com.deepseek.mobilechat.domain.ModelId(id),
    displayName = id,
    pricing = if (inputPerMTok != null && outputPerMTok != null) {
        ModelPricing(inputPerMTok, outputPerMTok)
    } else {
        null
    },
)

fun attachment(
    id: String,
    kind: AttachmentKind,
    fileName: String = "$id.bin",
    mimeType: String = when (kind) {
        AttachmentKind.IMAGE -> "image/png"
        AttachmentKind.TEXT -> "text/plain"
        AttachmentKind.OTHER -> "application/octet-stream"
    },
): Attachment = Attachment(
    id = AttachmentId(id),
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = 1L,
    localPath = "memory/$fileName",
    sha256 = "",
    kind = kind,
    createdAt = 0L,
)

// ---------------------------------------------------------------------------
// Outcome assertions
// ---------------------------------------------------------------------------

fun <T> Outcome<T>.requireOk(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw AssertionError("expected Outcome.Ok but got $error")
}

fun <T> Outcome<T>.requireErr(): AppError = when (this) {
    is Outcome.Ok -> throw AssertionError("expected Outcome.Err but got $value")
    is Outcome.Err -> error
}
