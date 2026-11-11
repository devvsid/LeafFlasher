package com.sid_imp.leaf.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

enum class FeatureInfoKey {
    BOOT_SELECTION,
    WIPE_DATA,
    ADVANCED_CUSTOM_PARTITIONS,
    VBMETA_VERITY,
    ADVANCED_REBOOT_AFTER_FLASH,
    ADVANCED_TARGET_SLOT,
    SINGLE_PARTITION,
    DISABLE_AVB,
    ADB_SIDELOAD,
    COMMAND_RUNNER,
    SET_ACTIVE_SLOT,
    FORMAT_DATA
}

data class FeatureInfoSpec(
    val key: FeatureInfoKey,
    val title: String,
    val icon: ImageVector,
    val whatItDoes: String,
    val whenToUse: String,
    val tip: String,
    val isCaution: Boolean = false
)

data class TutorStepSpec(
    val stepNumber: Int,
    val badge: String,
    val title: String,
    val tileFocus: String,
    val explanation: String,
    val proTip: String,
    val icon: ImageVector,
    val isBlueAccent: Boolean = false
)

object FeatureGuideCatalog {

    fun getFeatureInfo(key: FeatureInfoKey): FeatureInfoSpec = when (key) {
        FeatureInfoKey.BOOT_SELECTION -> FeatureInfoSpec(
            key = FeatureInfoKey.BOOT_SELECTION,
            title = "Boot Slot & Auto Reboot",
            icon = Icons.Default.PowerSettingsNew,
            whatItDoes = "Phones with A/B have two system sides (Slot A and Slot B). Here you pick which side gets the ROM and what the phone does after flashing ends.",
            whenToUse = "Keep Slot A and Restart Phone (System). Only change this if your ROM guide tells you to.",
            tip = "Not sure? Leave Slot A + Restart Phone selected."
        )

        FeatureInfoKey.WIPE_DATA -> FeatureInfoSpec(
            key = FeatureInfoKey.WIPE_DATA,
            title = "Erase Old Data (Wipe)",
            icon = Icons.Default.DeleteSweep,
            whatItDoes = "Deletes all apps, photos, accounts, and settings on the phone after the ROM finishes installing.",
            whenToUse = "Pick YES (Clean Flash) when installing a new ROM brand. Pick NO (Dirty Flash) when updating the same ROM you already have.",
            tip = "Copy your important photos somewhere safe before picking YES.",
            isCaution = true
        )

        FeatureInfoKey.ADVANCED_CUSTOM_PARTITIONS -> FeatureInfoSpec(
            key = FeatureInfoKey.ADVANCED_CUSTOM_PARTITIONS,
            title = "Add or Swap Extra Files",
            icon = Icons.AutoMirrored.Filled.NoteAdd,
            whatItDoes = "Lets you put in extra .img files—like super_empty.img, a rooted Magisk/KernelSU boot.img or init_boot.img, or custom recovery.",
            whenToUse = "Pick YES if your guide gave you super_empty.img, rooted boot, or another extra .img file. Pick NO to just use normal ROM files.",
            tip = "After adding a file, tap its green name chip if it needs a different slot target."
        )

        FeatureInfoKey.VBMETA_VERITY -> FeatureInfoSpec(
            key = FeatureInfoKey.VBMETA_VERITY,
            title = "Disable dm-verity Verification",
            icon = Icons.Default.VerifiedUser,
            whatItDoes = "Disables Android's strict dm-verity & vbmeta verification check so Port ROMs and GSIs are allowed to turn on.",
            whenToUse = "Pick Yes for Port ROMs or GSIs. Pick Keep Default for normal official or stock ROMs.",
            tip = "Many Port ROMs stay stuck on boot unless you choose Yes here.",
            isCaution = true
        )

        FeatureInfoKey.ADVANCED_REBOOT_AFTER_FLASH -> FeatureInfoSpec(
            key = FeatureInfoKey.ADVANCED_REBOOT_AFTER_FLASH,
            title = "What Happens After Flashing",
            icon = Icons.Default.PowerSettingsNew,
            whatItDoes = "Decides if the phone should turn right back on into Android, restart Fastboot, or just wait.",
            whenToUse = "Leave on Restart Phone (System) for normal installs.",
            tip = "Recommended for almost every ROM install."
        )

        FeatureInfoKey.ADVANCED_TARGET_SLOT -> FeatureInfoSpec(
            key = FeatureInfoKey.ADVANCED_TARGET_SLOT,
            title = "Which Slot to Flash (A / B)",
            icon = Icons.Default.SwapHoriz,
            whatItDoes = "Picks whether the ROM goes onto Slot A, Slot B, or firmware goes onto both sides.",
            whenToUse = "Leave on Slot A unless your ROM guide asks for Slot B or A + B.",
            tip = "The app switches your phone to the chosen slot automatically."
        )

        FeatureInfoKey.SINGLE_PARTITION -> FeatureInfoSpec(
            key = FeatureInfoKey.SINGLE_PARTITION,
            title = "Flash One File (.img)",
            icon = Icons.Default.Layers,
            whatItDoes = "Installs just one .img file—like boot.img for root or recovery.img—without touching the rest of your phone.",
            whenToUse = "Use this to root your phone, install a kernel, or restore stock boot.img.",
            tip = "The app reads the filename to guess the right place. Tap Change to pick another."
        )

        FeatureInfoKey.DISABLE_AVB -> FeatureInfoSpec(
            key = FeatureInfoKey.DISABLE_AVB,
            title = "Turn Off AVB Check",
            icon = Icons.Default.Security,
            whatItDoes = "Stops vbmeta.img from blocking custom or rooted system files when the phone starts.",
            whenToUse = "Turn ON only if your ROM or root instructions ask to disable verity / AVB.",
            tip = "Keep OFF for normal stock vbmeta files.",
            isCaution = true
        )

        FeatureInfoKey.ADB_SIDELOAD -> FeatureInfoSpec(
            key = FeatureInfoKey.ADB_SIDELOAD,
            title = "ADB Sideload (Recovery ZIP)",
            icon = Icons.Default.SystemUpdate,
            whatItDoes = "Sends a .zip add-on (like GApps, Magisk, or an OTA update) straight into your phone's Recovery.",
            whenToUse = "On the phone, enter Recovery -> Apply update -> Apply from ADB, pick the .zip here, then tap Start.",
            tip = "Wait until the phone screen says waiting for sideload before you tap Start."
        )

        FeatureInfoKey.COMMAND_RUNNER -> FeatureInfoSpec(
            key = FeatureInfoKey.COMMAND_RUNNER,
            title = "Run a Manual Command",
            icon = Icons.Default.Terminal,
            whatItDoes = "Sends one typed command straight to your connected phone and shows the answer in Leaves Log.",
            whenToUse = "Handy for checking phone info or typing a single command from a guide.",
            tip = "Do not type 'fastboot' or 'adb' first—type only the command itself."
        )

        FeatureInfoKey.SET_ACTIVE_SLOT -> FeatureInfoSpec(
            key = FeatureInfoKey.SET_ACTIVE_SLOT,
            title = "Switch Active Slot (A or B)",
            icon = Icons.Default.SwapHoriz,
            whatItDoes = "Tells your phone whether to start Android from Slot A or Slot B next time it boots.",
            whenToUse = "Use this if one slot fails to start after an update and you want to switch back to the working slot.",
            tip = "Works only on A/B phones in Fastboot mode.",
            isCaution = true
        )

        FeatureInfoKey.FORMAT_DATA -> FeatureInfoSpec(
            key = FeatureInfoKey.FORMAT_DATA,
            title = "Format Data (Full Erase)",
            icon = Icons.Default.DeleteForever,
            whatItDoes = "Wipes all personal files, apps, accounts, and phone encryption right away.",
            whenToUse = "Use when clearing encryption or doing a fresh reset after bootloader unlock.",
            tip = "Runs immediately when tapped and cannot be undone.",
            isCaution = true
        )
    }

    val tutorSteps: List<TutorStepSpec> = listOf(
        TutorStepSpec(
            stepNumber = 1,
            badge = "STEP 1 OF 4",
            title = "Connect Your Phone",
            tileFocus = "Top Status Card",
            explanation = "Plug the other phone into this phone using a USB OTG cable. Put it in Fastboot mode (or Recovery ADB Sideload for ZIP add-ons). The top card turns green or blue when ready.",
            proTip = "If nothing shows up, tap the Refresh button at the top right.",
            icon = Icons.Default.Usb
        ),
        TutorStepSpec(
            stepNumber = 2,
            badge = "STEP 2 OF 4",
            title = "Pick ROM & Answer 4 Questions",
            tileFocus = "Flasher Tab",
            explanation = "Tap Choose ROM to pick a .zip or ROM folder (from Phone Storage or SD Card). Then answer 4 simple questions: Boot Slot, Wipe Data, Extra Files (like super_empty or rooted boot), and dm-verity Verification for Port ROMs.",
            proTip = "Tap Continue after each choice. Nothing starts until you tap Start Flashing at the end.",
            icon = Icons.Default.Home
        ),
        TutorStepSpec(
            stepNumber = 3,
            badge = "STEP 3 OF 4",
            title = "One File or Sideload",
            tileFocus = "Partition • Sideload",
            explanation = "Want to flash just one file like rooted boot.img? Use the Partition tab. Want to send GApps or Magisk in Recovery? Use the Sideload tab.",
            proTip = "Tap any small (i) button anywhere in the app to read a simple explanation.",
            icon = Icons.Default.SystemUpdate,
            isBlueAccent = true
        ),
        TutorStepSpec(
            stepNumber = 4,
            badge = "STEP 4 OF 4",
            title = "Watch Progress & Tools",
            tileFocus = "Tools Tab • Leaves Log",
            explanation = "While flashing runs, everything unnecessary hides so you only see live progress bars and logs. Use Tools anytime to restart the phone or switch slots.",
            proTip = "Tap the ? button at the top right anytime to read this quick tour again.",
            icon = Icons.Default.Terminal
        )
    )
}
