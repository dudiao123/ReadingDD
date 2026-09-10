package io.legado.app.utils

private val sensitiveHeaderPattern = Regex(
    "(?im)^(authorization|cookie|set-cookie|proxy-authorization)\\s*[:=]\\s*.*$"
)
private val sensitiveJsonPattern = Regex(
    "(?i)(\"?(?:token|access_token|refresh_token|password|passwd|secret)\"?\\s*[:=]\\s*[\"']?)[^\"'&,\\s}]+"
)
private val sensitiveQueryPattern = Regex(
    "(?i)([?&](?:token|access_token|refresh_token|password|passwd|secret)=)[^&#\\s]+"
)

/** Removes credentials before a source-debug report leaves the app. */
fun String.sanitizeDebugReport(): String =
    replace(sensitiveHeaderPattern) { "${it.groupValues[1]}: ***" }
        .replace(sensitiveJsonPattern, "$1***")
        .replace(sensitiveQueryPattern, "$1***")
