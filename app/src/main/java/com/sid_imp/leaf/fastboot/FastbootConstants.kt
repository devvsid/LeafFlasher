package com.sid_imp.leaf.fastboot

object FastbootConstants {
    const val USB_CLASS_VENDOR_SPECIFIC = 255
    const val USB_SUBCLASS_FASTBOOT = 66
    const val USB_PROTOCOL_FASTBOOT = 3
    const val USB_PROTOCOL_ADB = 1

    const val RESPONSE_OKAY = "OKAY"
    const val RESPONSE_FAIL = "FAIL"
    const val RESPONSE_DATA = "DATA"
    const val RESPONSE_INFO = "INFO"
    const val RESPONSE_TEXT = "TEXT"

    const val CMD_GETVAR = "getvar:"
    const val CMD_DOWNLOAD = "download:"
    const val CMD_FLASH = "flash:"
    const val CMD_ERASE = "erase:"
    const val CMD_REBOOT = "reboot"
    const val CMD_REBOOT_BOOTLOADER = "reboot-bootloader"
    const val CMD_REBOOT_FASTBOOT = "reboot-fastboot"
    const val CMD_REBOOT_RECOVERY = "reboot-recovery"
    const val CMD_SET_ACTIVE = "set_active:"
    const val CMD_CONTINUE = "continue"

    const val DEFAULT_TIMEOUT_MS = 30000
    const val FAST_QUERY_TIMEOUT_MS = 3000
    const val BULK_WRITE_TIMEOUT_MS = 20000
    const val DOWNLOAD_TIMEOUT_MS = 45000
    const val FLASH_TIMEOUT_MS = 240000
    const val RECONNECT_TIMEOUT_MS = 120000L
    const val REBOOT_WAIT_TIME_SECONDS = 16

    // Linux kernel usbfs MAX_USBFS_BUFFER_SIZE is 16 KB (16384 B); avoids fastbootd OTG URB stalls
    const val BULK_CHUNK_SIZE = 16 * 1024
    const val STREAM_BUFFER_SIZE = 1024 * 1024
    const val BUFFER_SIZE = 4096
}

enum class FastbootMode {
    DISCONNECTED,
    ADB,
    ADB_RECOVERY,
    ADB_SIDELOAD,
    BOOTLOADER,
    FASTBOOTD,
    RECONNECTING;

    val isBootloaderOrFastbootd: Boolean
        get() = this == BOOTLOADER || this == FASTBOOTD

    val isAdbConnected: Boolean
        get() = this == ADB || this == ADB_RECOVERY || this == ADB_SIDELOAD
}

enum class ResponseType {
    OKAY,
    FAIL,
    DATA,
    INFO,
    TEXT,
    TIMEOUT,
    ERROR
}

data class FastbootResponse(
    val type: ResponseType,
    val payload: String = "",
    val dataLength: Long = 0L
) {
    val isSuccess: Boolean get() = type == ResponseType.OKAY
}

data class DeviceInfo(
    val productName: String = "Unknown",
    val bootloaderVersion: String = "Unknown",
    val currentSlot: String = "unknown",
    val slotCount: Int = 0,
    val isUserspace: Boolean = false,
    val isAdbMode: Boolean = false,
    val maxDownloadSize: Long = 0L,
    val serialNumber: String = "Unknown",
    val isVerifiedFastboot: Boolean = false,
    val adbSubMode: FastbootMode? = null,
    val isAdbAuthorized: Boolean = false
) {
    val mode: FastbootMode
        get() = when {
            isAdbMode -> if (!isVerifiedFastboot && adbSubMode != null) adbSubMode else FastbootMode.DISCONNECTED
            !isVerifiedFastboot -> FastbootMode.DISCONNECTED
            isUserspace -> FastbootMode.FASTBOOTD
            else -> FastbootMode.BOOTLOADER
        }
}
