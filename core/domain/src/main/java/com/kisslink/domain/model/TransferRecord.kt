package com.kisslink.domain.model

/** Domain representation of a completed transfer entry (direction-agnostic). */
data class TransferRecord(
    val id: Long = 0,
    val direction: String,       // "SEND" or "RECEIVE"
    val fileName: String,
    val fileSizeBytes: Long,
    val timestampMs: Long,
    val success: Boolean,
    val avgSpeedBps: Long,
    val filePath: String? = null,
    val mimeType: String? = null,
    val peerDeviceName: String? = null,
    val batchId: Long = 0,
)
