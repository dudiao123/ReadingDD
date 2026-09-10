package io.legado.app.domain.model

import java.text.Normalizer
import java.util.Locale

/** Normalizes harmless typography differences before cross-source result merging. */
internal fun String.normalizedSearchIdentity(): String = Normalizer
    .normalize(trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .filter { it.isLetterOrDigit() }

/** Authors emitted as placeholders by sources should not split the same title. */
internal fun String.isUnknownSearchAuthor(): Boolean =
    normalizedSearchIdentity() in unknownSearchAuthors

private val unknownSearchAuthors = setOf(
    "",
    "佚名",
    "未知",
    "不详",
    "无名氏",
    "暂无",
    "unknown",
    "anonymous",
)
