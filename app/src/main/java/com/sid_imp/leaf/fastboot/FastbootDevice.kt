package com.sid_imp.leaf.fastboot

import java.io.InputStream

interface FastbootDevice {
    val isConnected: Boolean
    val currentMode: FastbootMode
    val serialNumber: String

    suspend fun sendCommand(
        command: String,
        timeoutMs: Int = FastbootConstants.DEFAULT_TIMEOUT_MS,
        infoCallback: (String) -> Unit = {}
    ): FastbootResponse

    suspend fun downloadData(
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): FastbootResponse

    suspend fun flashPartition(
        partition: String,
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> },
        infoCallback: (String) -> Unit = {}
    ): FastbootResponse

    suspend fun flashPartitionChunked(
        partition: String,
        totalBytes: Long,
        openStream: () -> InputStream?,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> },
        infoCallback: (String) -> Unit = {}
    ): FastbootResponse

    suspend fun erasePartition(
        partition: String,
        infoCallback: (String) -> Unit = {}
    ): FastbootResponse

    suspend fun reboot(
        target: String = "",
        infoCallback: (String) -> Unit = {}
    ): FastbootResponse

    suspend fun setActiveSlot(slot: String): FastbootResponse

    suspend fun getVariable(name: String): String?

    suspend fun fetchDeviceInfo(): DeviceInfo

    fun close()
}
