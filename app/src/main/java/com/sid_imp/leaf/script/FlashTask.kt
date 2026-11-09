package com.sid_imp.leaf.script

import android.net.Uri

enum class TaskType {
    FLASH,
    ERASE,
    REBOOT,
    REBOOT_FASTBOOT,
    REBOOT_BOOTLOADER,
    REBOOT_RECOVERY,
    SET_ACTIVE,
    DELETE_LOGICAL,
    CREATE_LOGICAL,
    RESIZE_LOGICAL,
    WIPE,
    GETVAR,
    RAW
}

enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

enum class FlashingPhase {
    FIRMWARE_BOOTLOADER,
    FASTBOOTD_TRANSITION,
    LOGICAL_DYNAMIC,
    FINALIZATION
}

data class FlashTask(
    val id: Int,
    val type: TaskType,
    val rawCommandLine: String,
    val partition: String = "",
    val fileName: String = "",
    val fileUri: Uri? = null,
    val fileSize: Long = 0L,
    val slot: String? = null, // "a", "b", or null
    val disableVerity: Boolean = false,
    val disableVerification: Boolean = false,
    val extraArgs: List<String> = emptyList(),

    // Classified Phase
    var phase: FlashingPhase = FlashingPhase.FIRMWARE_BOOTLOADER,

    // Dynamic runtime state
    var status: TaskStatus = TaskStatus.PENDING,
    var isFilePresent: Boolean = false,
    var progressBytes: Long = 0L,
    var progressPercent: Int = 0,
    var errorMessage: String? = null,
    var durationMs: Long = 0L
) {
    val displayName: String
        get() = when (type) {
            TaskType.FLASH -> "Flash $partition ($fileName)"
            TaskType.ERASE -> "Erase $partition"
            TaskType.REBOOT -> "Reboot to System"
            TaskType.REBOOT_FASTBOOT -> "Reboot to Fastbootd"
            TaskType.REBOOT_BOOTLOADER -> "Reboot to Bootloader"
            TaskType.REBOOT_RECOVERY -> "Reboot to Recovery"
            TaskType.SET_ACTIVE -> "Set Active Slot: $slot"
            TaskType.DELETE_LOGICAL -> "Delete Logical: $partition"
            TaskType.CREATE_LOGICAL -> "Create Logical: $partition"
            TaskType.RESIZE_LOGICAL -> "Resize Logical: $partition"
            TaskType.WIPE -> "Wipe Data (-w)"
            TaskType.GETVAR -> "Query: $partition"
            TaskType.RAW -> "Execute: $rawCommandLine"
        }

    val isRebootTask: Boolean
        get() = type in setOf(
            TaskType.REBOOT,
            TaskType.REBOOT_FASTBOOT,
            TaskType.REBOOT_BOOTLOADER,
            TaskType.REBOOT_RECOVERY
        )
}

data class ScriptAnalysis(
    val tasks: List<FlashTask>,
    val firmwareTasks: List<FlashTask>,
    val rebootTransitionTask: FlashTask?,
    val logicalTasks: List<FlashTask>,
    val finalTasks: List<FlashTask>,
    val totalPartitionsCount: Int,
    val presentPartitionsCount: Int,
    val uniquePartitionsCount: Int = totalPartitionsCount
)
