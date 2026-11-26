package com.evofactory.hal

private val logUrlLogin = Regex("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@")
// Query names can be arbitrary signed credentials. Keep names, redact every query value.
private val logQueryValue = Regex("([?&][^=\\s&?#]+)=([^&\\s#]*)")
private val logSecret = Regex("(?i)((?:access[_-]?token|refresh[_-]?token|token|password|api[_-]?key|authorization|cookie)\\s*[:=]\\s*)(?:Bearer\\s+)?[^\\s,;]+")
private val logJsonSecret = Regex("(?i)(\"(?:access[_-]?token|refresh[_-]?token|token|password|api[_-]?key|authorization|cookie)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"")

internal fun sanitizeLog(message: String): String = message
    .replace(logUrlLogin, "$1***@")
    .replace(logQueryValue, "$1=***")
    .replace(logJsonSecret, "$1\"***\"")
    .replace(logSecret, "$1***")

internal fun logMessage(message: String, error: Throwable?): String =
    sanitizeLog(if (error == null) message else "$message\n${error.stackTraceToString()}")
