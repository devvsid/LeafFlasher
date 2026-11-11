package com.sid_imp.leaf.script

object SampleScripts {

    val UNIVERSAL_FASTBOOT_SCRIPT_BAT = """
@echo off
:: Universal Android Fastboot Flash Script (.bat)
echo Initializing fastboot flash sequence...

fastboot --set-active=a

echo Flashing Bootloader & Firmware Partitions...
fastboot flash boot_a boot.img
fastboot flash boot_b boot.img
fastboot flash dtbo_a dtbo.img
fastboot flash dtbo_b dtbo.img
fastboot flash init_boot_a init_boot.img
fastboot flash init_boot_b init_boot.img
fastboot flash modem_a modem.img
fastboot flash modem_b modem.img
fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta.img
fastboot --disable-verity --disable-verification flash vbmeta_b vbmeta.img
fastboot flash vbmeta_system_a vbmeta_system.img
fastboot flash vbmeta_system_b vbmeta_system.img
fastboot flash vendor_boot_a vendor_boot.img
fastboot flash vendor_boot_b vendor_boot.img

echo Rebooting to Userspace Fastbootd...
fastboot reboot fastboot

echo Flashing Dynamic Logical Partitions in Fastbootd (Active Slot)...
fastboot flash system_a system.img
fastboot flash system_ext_a system_ext.img
fastboot flash product_a product.img
fastboot flash vendor_a vendor.img
fastboot flash odm_a odm.img

echo Erasing Userdata (Factory Reset)...
fastboot erase userdata
fastboot erase metadata

echo Flashing Complete! Rebooting to System...
fastboot reboot
pause
""".trimIndent()

    val UNIVERSAL_FASTBOOT_SCRIPT_SH = """
#!/bin/bash
# Universal Android Fastboot Flash Script (.sh)
echo "Starting universal fastboot flashing..."

fastboot --set-active=a
fastboot flash boot_a boot.img
fastboot flash boot_b boot.img
fastboot flash dtbo_a dtbo.img
fastboot flash dtbo_b dtbo.img
fastboot flash init_boot_a init_boot.img
fastboot flash init_boot_b init_boot.img
fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta.img
fastboot --disable-verity --disable-verification flash vbmeta_b vbmeta.img
fastboot flash vendor_boot_a vendor_boot.img
fastboot flash vendor_boot_b vendor_boot.img

echo "Rebooting into fastbootd..."
fastboot reboot-fastboot

echo "Flashing super/dynamic partitions..."
fastboot flash system_a system.img
fastboot flash system_ext_a system_ext.img
fastboot flash product_a product.img
fastboot flash vendor_a vendor.img

echo "Wiping userdata..."
fastboot erase userdata
fastboot reboot
""".trimIndent()

    // Universal script templates for testing and execution
    val UNIVERSAL_FASTBOOT_BAT = UNIVERSAL_FASTBOOT_SCRIPT_BAT
    val UNIVERSAL_FASTBOOT_SH = UNIVERSAL_FASTBOOT_SCRIPT_SH
    val CMF_PHONE_1_FLASH_SH = """
#!/bin/bash
FASTBOOT_CMD="termux-fastboot"
core_partitions="boot dtbo init_boot vendor_boot apusys ccu connsys_bt connsys_gnss connsys_wifi dpm gpueb gz lk logo mcf_ota mcupm modem pi_img scp spmfw sspm tee vcp"
vbmeta_partitions="vbmeta vbmeta_system vbmeta_vendor"
logical_partitions="odm_dlkm odm vendor_dlkm product vendor system_dlkm system_ext system"

${'$'}FASTBOOT_CMD --set-active=a
for i in ${'$'}core_partitions; do ${'$'}FASTBOOT_CMD flash "${'$'}{i}_a" "${'$'}{i}.img"; done
${'$'}FASTBOOT_CMD flash "preloader" "preloader_raw.img"
for i in ${'$'}vbmeta_partitions; do ${'$'}FASTBOOT_CMD flash "${'$'}{i}_a" "${'$'}{i}.img"; done
${'$'}FASTBOOT_CMD reboot fastboot
for i in ${'$'}logical_partitions; do ${'$'}FASTBOOT_CMD flash "${'$'}{i}_a" "${'$'}{i}.img"; done
${'$'}FASTBOOT_CMD --set-active=a
${'$'}FASTBOOT_CMD reboot
""".trimIndent()
    val NOTHING_PHONE_2_FASTBOOT_SCRIPT = UNIVERSAL_FASTBOOT_SCRIPT_BAT
    val NOTHING_PHONE_1_BASH_SCRIPT = UNIVERSAL_FASTBOOT_SCRIPT_SH
    val PIXEL_FACTORY_FLASH_BAT = UNIVERSAL_FASTBOOT_SCRIPT_BAT
}
