package com.example.aichatapp.ui.chat

/**
 * 单条聊天消息模型
 * @param id        稳定唯一 id（LazyColumn 的 key，避免重组错位）
 * @param text      消息内容
 * @param isFromUser true=用户发出的（右对齐），false=AI 回复（左对齐）
 * @param timestamp 发送时间戳（ms），后续可用于显示时间
 */
data class Message(
    val id: String,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
