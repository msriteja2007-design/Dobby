package com.yogitha.dobby

data class TeachingStep(
    val timestamp: Long,
    val packageName: String,
    val eventType: String,
    val text: String,
    val contentDescription: String,
    val className: String?
)