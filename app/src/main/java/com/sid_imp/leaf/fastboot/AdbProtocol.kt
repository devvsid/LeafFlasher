package com.sid_imp.leaf.fastboot

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object AdbConstants {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 256 * 1024
    const val LEGACY_MAX_PAYLOAD = 4096
    const val SIDELOAD_BLOCK_SIZE = 65536
}

enum class AdbSideloadState {
    IDLE,
    SENDING,
    INSTALLING_ON_DEVICE,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AdbSideloadProgress(
    val state: AdbSideloadState = AdbSideloadState.IDLE,
    val fileName: String = "",
    val percent: Int = 0,
    val uniqueBytesSent: Long = 0L,
    val totalBytes: Long = 0L,
    val speedMbPerSec: Double = 0.0,
    val statusMessage: String = "Ready"
)

data class AdbBannerInfo(
    val mode: FastbootMode,
    val productName: String,
    val rawBanner: String
)

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCheck: Int,
    val magic: Int,
    val payload: ByteArray = ByteArray(0)
) {
    fun headerBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(dataLength)
        buffer.putInt(dataCheck)
        buffer.putInt(magic)
        return buffer.array()
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(dataLength)
        buffer.putInt(dataCheck)
        buffer.putInt(magic)
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    companion object {
        fun computeChecksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) {
                sum += (b.toInt() and 0xFF)
            }
            return sum
        }

        fun create(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): AdbMessage {
            val magic = command xor -0x1
            return AdbMessage(
                command = command,
                arg0 = arg0,
                arg1 = arg1,
                dataLength = payload.size,
                dataCheck = computeChecksum(payload),
                magic = magic,
                payload = payload
            )
        }

        fun parseHeader(bytes: ByteArray): AdbMessage? {
            if (bytes.size < 24) return null
            val buffer = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val dataLength = buffer.int
            val dataCheck = buffer.int
            val magic = buffer.int

            if (command xor -0x1 != magic || dataLength < 0 || dataLength > 16 * 1024 * 1024) return null
            return AdbMessage(command, arg0, arg1, dataLength, dataCheck, magic)
        }

        fun parseBanner(raw: String): AdbBannerInfo {
            val clean = raw.trimEnd('\u0000', '\r', '\n', ' ')
            val prefix = clean.substringBefore(':').lowercase()
            val mode = when (prefix) {
                "sideload" -> FastbootMode.ADB_SIDELOAD
                "recovery", "rescue" -> FastbootMode.ADB_RECOVERY
                else -> FastbootMode.ADB
            }
            val props = clean.substringAfter("::", "")
            var model: String? = null
            var name: String? = null
            var device: String? = null
            for (part in props.split(';')) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2) {
                    when (kv[0].trim()) {
                        "ro.product.model" -> model = kv[1].trim()
                        "ro.product.name" -> name = kv[1].trim()
                        "ro.product.device" -> device = kv[1].trim()
                    }
                }
            }
            val friendlyName = model?.takeIf { it.isNotBlank() }
                ?: device?.takeIf { it.isNotBlank() }
                ?: name?.takeIf { it.isNotBlank() }
                ?: when (mode) {
                    FastbootMode.ADB_SIDELOAD -> "Recovery Sideload"
                    FastbootMode.ADB_RECOVERY -> "ADB Recovery"
                    else -> "Android ADB"
                }
            return AdbBannerInfo(mode = mode, productName = friendlyName, rawBanner = clean)
        }
    }
}

object AdbRsaKeyManager {
    private const val RSA_KEY_WORDS = 64 // 2048-bit key = 64 x uint32
    private const val RSA_KEY_BYTES = RSA_KEY_WORDS * 4 // 256 bytes

    // ASN.1 DER DigestInfo prefix for SHA-1 (15 bytes) expected by AOSP RSA_verify(NID_sha1, token_20B, ...)
    val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
        0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    )

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun loadOrCreateKeyPair(storageDir: File?): KeyPair {
        if (storageDir != null) {
            try {
                if (!storageDir.exists()) storageDir.mkdirs()
                val privFile = File(storageDir, "leaf_adb_private.pk8")
                val pubFile = File(storageDir, "leaf_adb_public.x509")
                if (privFile.exists() && pubFile.exists()) {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                    return KeyPair(pub, priv)
                }
            } catch (_: Exception) {}
        }

        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()

        if (storageDir != null) {
            try {
                if (!storageDir.exists()) storageDir.mkdirs()
                File(storageDir, "leaf_adb_private.pk8").writeBytes(kp.private.encoded)
                File(storageDir, "leaf_adb_public.x509").writeBytes(kp.public.encoded)
            } catch (_: Exception) {}
        }
        return kp
    }

    /**
     * Serializes a 2048-bit RSA public key into the AOSP 524-byte `RSAPublicKey` wire struct,
     * Base64-encoded with the host identifier comment and null terminator.
     */
    fun encodeAndroidPublicKeyPayload(
        publicKey: RSAPublicKey,
        comment: String = "sid_imp@leafflasher"
    ): ByteArray {
        val struct = encodeAndroidPublicKeyStruct(publicKey)
        val b64 = encodeBase64NoWrap(struct)
        return "$b64 $comment\u0000".toByteArray(Charsets.UTF_8)
    }

    fun encodeAndroidPublicKeyStruct(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent.toInt()
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulus.modInverse(r32)).toLong() and 0xFFFFFFFFL

        val r = BigInteger.ONE.shiftLeft(RSA_KEY_BYTES * 8) // 2^2048
        val rr = r.multiply(r).mod(modulus)

        val modLe = toLittleEndianWordsBytes(modulus, RSA_KEY_BYTES)
        val rrLe = toLittleEndianWordsBytes(rr, RSA_KEY_BYTES)

        val buf = ByteBuffer.allocate(4 + 4 + RSA_KEY_BYTES + RSA_KEY_BYTES + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(RSA_KEY_WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(modLe)
        buf.put(rrLe)
        buf.putInt(exponent)
        return buf.array()
    }

    fun signAuthToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val digestInfo = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token.size)
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.size)
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.size, token.size)

        return try {
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initSign(privateKey)
            sig.update(digestInfo)
            sig.sign()
        } catch (_: Exception) {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            cipher.doFinal(digestInfo)
        }
    }

    private fun toLittleEndianWordsBytes(value: BigInteger, targetLen: Int): ByteArray {
        val be = value.toByteArray()
        val fixedBe = ByteArray(targetLen)
        val srcStart = if (be.size > targetLen && be[0].toInt() == 0) be.size - targetLen else maxOf(0, be.size - targetLen)
        val copyLen = minOf(be.size - srcStart, targetLen)
        System.arraycopy(be, srcStart, fixedBe, targetLen - copyLen, copyLen)

        // Reversing full big-endian array converts MSW..LSW BE bytes into LSW..MSW LE uint32 words
        val le = ByteArray(targetLen)
        for (i in 0 until targetLen) {
            le[i] = fixedBe[targetLen - 1 - i]
        }
        return le
    }

    fun encodeBase64NoWrap(input: ByteArray): String {
        val out = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) BASE64_ALPHABET[triple and 0x3F] else '=')
        }
        return out.toString()
    }
}

class AdbUsbDevice(
    val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val keyStorageDir: File? = null,
    private val isDevicePresent: () -> Boolean = { true }
) {
    private sealed class ReadOutcome {
        data class Packet(val message: AdbMessage) : ReadOutcome()
        object Timeout : ReadOutcome()
        object IoError : ReadOutcome()
    }

    private val ioMutex = Mutex()
    private val nextLocalStreamId = java.util.concurrent.atomic.AtomicInteger(1)

    @Volatile
    private var closed = false

    @Volatile
    var isAuthorized = false
        private set

    @Volatile
    var lastAuthRequestedByTarget = false
        private set

    @Volatile
    var detectedMode: FastbootMode = FastbootMode.ADB
        private set

    @Volatile
    var productName: String = "ADB Device"
        private set

    @Volatile
    var negotiatedMaxPayload: Int = AdbConstants.LEGACY_MAX_PAYLOAD
        private set

    private var outScratch: ByteArray? = null
    private val rxChunkBuf = ByteArray(maxOf(inEndpoint.maxPacketSize, 16 * 1024))
    private val rxStash = ByteArray(64 * 1024)
    private var rxStashPos = 0
    private var rxStashLimit = 0

    val isConnected: Boolean
        get() = !closed

    private fun clearRxStash() {
        rxStashPos = 0
        rxStashLimit = 0
    }

    private fun appendToRxStash(src: ByteArray, offset: Int, len: Int) {
        if (len <= 0) return
        val availableTail = rxStash.size - rxStashLimit
        if (availableTail < len) {
            val remaining = rxStashLimit - rxStashPos
            if (rxStashPos > 0) {
                System.arraycopy(rxStash, rxStashPos, rxStash, 0, remaining)
                rxStashPos = 0
                rxStashLimit = remaining
            }
            if (rxStash.size - rxStashLimit < len) {
                // Overflow safety: reset stash rather than corrupting framing
                rxStashPos = 0
                rxStashLimit = 0
            }
        }
        val toCopy = minOf(len, rxStash.size - rxStashLimit)
        System.arraycopy(src, offset, rxStash, rxStashLimit, toCopy)
        rxStashLimit += toCopy
    }

    private fun drainStaleInPackets() {
        clearRxStash()
        repeat(4) {
            val n = try {
                connection.bulkTransfer(inEndpoint, rxChunkBuf, rxChunkBuf.size, 35)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) return
        }
    }

    private fun writeRawOut(bytes: ByteArray, length: Int, timeoutMs: Int): Boolean {
        val maxTransfer = 16 * 1024
        var offset = 0
        while (offset < length && !closed) {
            val chunkLen = minOf(maxTransfer, length - offset)
            val written = if (offset == 0) {
                connection.bulkTransfer(outEndpoint, bytes, chunkLen, timeoutMs)
            } else {
                var scratch = outScratch
                if (scratch == null || scratch.size < chunkLen) {
                    scratch = ByteArray(maxTransfer)
                    outScratch = scratch
                }
                System.arraycopy(bytes, offset, scratch, 0, chunkLen)
                connection.bulkTransfer(outEndpoint, scratch, chunkLen, timeoutMs)
            }
            if (written <= 0) return false
            offset += written
        }
        return offset == length
    }

    /**
     * In AOSP ADB USB transport (`transport_usb.cpp`), header (24 bytes) and payload must be
     * submitted as distinct USB bulk transfers so FunctionFS gadgets never truncate payloads.
     */
    private fun sendPacket(msg: AdbMessage, timeoutMs: Int = 5000): Boolean {
        if (closed) return false
        val hdr = msg.headerBytes()
        if (!writeRawOut(hdr, hdr.size, timeoutMs)) return false
        if (msg.payload.isNotEmpty()) {
            if (!writeRawOut(msg.payload, msg.payload.size, timeoutMs)) return false
        }
        return true
    }

    private fun sendPacketSlice(
        command: Int,
        arg0: Int,
        arg1: Int,
        buffer: ByteArray,
        length: Int,
        timeoutMs: Int = 10000
    ): Boolean {
        if (closed) return false
        var sum = 0
        for (i in 0 until length) {
            sum += (buffer[i].toInt() and 0xFF)
        }
        val hdrBuf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        hdrBuf.putInt(command)
        hdrBuf.putInt(arg0)
        hdrBuf.putInt(arg1)
        hdrBuf.putInt(length)
        hdrBuf.putInt(sum)
        hdrBuf.putInt(command xor -0x1)
        if (!writeRawOut(hdrBuf.array(), 24, timeoutMs)) return false
        if (length > 0) {
            if (!writeRawOut(buffer, length, timeoutMs)) return false
        }
        return true
    }

    private fun readBytesExact(out: ByteArray, outOffset: Int, len: Int, timeoutMs: Int): ReadOutcome {
        var got = 0
        while (got < len && !closed) {
            val stashAvail = rxStashLimit - rxStashPos
            if (stashAvail > 0) {
                val take = minOf(len - got, stashAvail)
                System.arraycopy(rxStash, rxStashPos, out, outOffset + got, take)
                rxStashPos += take
                got += take
                if (rxStashPos == rxStashLimit) {
                    rxStashPos = 0
                    rxStashLimit = 0
                }
                continue
            }

            if (!isDevicePresent()) return ReadOutcome.IoError
            val startMs = System.currentTimeMillis()
            val n = try {
                connection.bulkTransfer(inEndpoint, rxChunkBuf, rxChunkBuf.size, timeoutMs)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) {
                val elapsed = System.currentTimeMillis() - startMs
                return if (!isDevicePresent() || (timeoutMs >= 300 && elapsed < 90L)) {
                    ReadOutcome.IoError
                } else {
                    ReadOutcome.Timeout
                }
            }
            if (n == 0) continue

            val take = minOf(len - got, n)
            System.arraycopy(rxChunkBuf, 0, out, outOffset + got, take)
            got += take
            if (n > take) {
                appendToRxStash(rxChunkBuf, take, n - take)
            }
        }
        return if (got == len) ReadOutcome.Packet(AdbMessage.create(0, 0, 0)) else ReadOutcome.IoError
    }

    private fun readPacketOutcome(timeoutMs: Int = 1500): ReadOutcome {
        if (closed) return ReadOutcome.IoError
        val hdrBytes = ByteArray(24)
        when (readBytesExact(hdrBytes, 0, 24, timeoutMs)) {
            is ReadOutcome.Timeout -> return ReadOutcome.Timeout
            is ReadOutcome.IoError -> return ReadOutcome.IoError
            is ReadOutcome.Packet -> Unit
        }

        val header = AdbMessage.parseHeader(hdrBytes)
        if (header == null) {
            // Re-sync stash if corrupt/half header arrived across reconnect boundary
            clearRxStash()
            return ReadOutcome.IoError
        }
        val len = header.dataLength
        if (len == 0) return ReadOutcome.Packet(header)

        val payload = ByteArray(len)
        val payloadDeadline = System.currentTimeMillis() + maxOf(timeoutMs.toLong() * 3L, 4500L)
        var got = 0
        while (got < len && !closed) {
            val stashAvail = rxStashLimit - rxStashPos
            if (stashAvail > 0) {
                val take = minOf(len - got, stashAvail)
                System.arraycopy(rxStash, rxStashPos, payload, got, take)
                rxStashPos += take
                got += take
                if (rxStashPos == rxStashLimit) {
                    rxStashPos = 0
                    rxStashLimit = 0
                }
                continue
            }
            if (System.currentTimeMillis() >= payloadDeadline) return ReadOutcome.Timeout
            if (!isDevicePresent()) return ReadOutcome.IoError
            val startMs = System.currentTimeMillis()
            val n = try {
                connection.bulkTransfer(inEndpoint, rxChunkBuf, rxChunkBuf.size, timeoutMs)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) {
                val elapsed = System.currentTimeMillis() - startMs
                if (!isDevicePresent() || (timeoutMs >= 300 && elapsed < 90L)) {
                    return ReadOutcome.IoError
                }
                continue
            }
            if (n == 0) continue
            val take = minOf(len - got, n)
            System.arraycopy(rxChunkBuf, 0, payload, got, take)
            got += take
            if (n > take) {
                appendToRxStash(rxChunkBuf, take, n - take)
            }
        }
        return if (got == len) ReadOutcome.Packet(header.copy(payload = payload)) else ReadOutcome.IoError
    }

    private fun readPacket(timeoutMs: Int = 1500): AdbMessage? {
        return when (val res = readPacketOutcome(timeoutMs)) {
            is ReadOutcome.Packet -> res.message
            else -> null
        }
    }

    private fun sendOkay(localId: Int, remoteId: Int) {
        sendPacket(AdbMessage.create(AdbConstants.A_OKAY, localId, remoteId), 3000)
    }

    private fun sendClose(localId: Int, remoteId: Int) {
        sendPacket(AdbMessage.create(AdbConstants.A_CLSE, localId, remoteId), 2000)
    }

    suspend fun connectHandshake(
        waitForUserAuthMs: Long = 8_000L,
        onWaitingForUserAuth: (Boolean) -> Unit = {}
    ): AdbBannerInfo? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (closed || !isDevicePresent()) return@withLock null
            lastAuthRequestedByTarget = false

            val keyPair = try {
                AdbRsaKeyManager.loadOrCreateKeyPair(keyStorageDir)
            } catch (_: Exception) {
                null
            }

            drainStaleInPackets()

            val primaryCnxn = AdbMessage.create(
                AdbConstants.A_CNXN,
                AdbConstants.A_VERSION,
                AdbConstants.MAX_PAYLOAD,
                "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
            )
            val compactCnxn = AdbMessage.create(
                AdbConstants.A_CNXN,
                AdbConstants.A_VERSION,
                AdbConstants.MAX_PAYLOAD,
                "host::\u0000".toByteArray(Charsets.UTF_8)
            )
            if (!sendPacket(primaryCnxn, 2000)) {
                if (!isDevicePresent() || closed) return@withLock null
                Thread.sleep(200)
                if (!sendPacket(primaryCnxn, 2000)) return@withLock null
            }

            var authStage = 0 // 0 = none sent, 1 = RSA signature sent, 2 = RSA public key sent
            var deadline = System.currentTimeMillis() + 3600L
            var cnxnRetryStage = 0
            var nextCnxnRetryAt = System.currentTimeMillis() + 1000L
            var connectedInfo: AdbBannerInfo? = null
            var consecIoErrors = 0

            while (System.currentTimeMillis() < deadline && !closed && isDevicePresent()) {
                if (authStage == 0 && cnxnRetryStage < 2 && System.currentTimeMillis() >= nextCnxnRetryAt) {
                    val pktToSend = if (cnxnRetryStage == 0) primaryCnxn else compactCnxn
                    cnxnRetryStage++
                    nextCnxnRetryAt = System.currentTimeMillis() + 1000L
                    if (!sendPacket(pktToSend, 1500)) break
                }

                when (val outcome = readPacketOutcome(550)) {
                    is ReadOutcome.Timeout -> {
                        consecIoErrors = 0
                        continue
                    }
                    is ReadOutcome.IoError -> {
                        consecIoErrors++
                        if (consecIoErrors >= 2 || authStage >= 1 || !isDevicePresent()) {
                            break
                        }
                        continue
                    }
                    is ReadOutcome.Packet -> {
                        consecIoErrors = 0
                        val pkt = outcome.message
                        when (pkt.command) {
                            AdbConstants.A_CNXN -> {
                                val remoteMax = if (pkt.arg1 > 0) pkt.arg1 else AdbConstants.LEGACY_MAX_PAYLOAD
                                negotiatedMaxPayload = minOf(remoteMax, AdbConstants.MAX_PAYLOAD).coerceAtLeast(4096)
                                val info = AdbMessage.parseBanner(String(pkt.payload, Charsets.UTF_8))
                                detectedMode = info.mode
                                productName = info.productName
                                isAuthorized = true
                                lastAuthRequestedByTarget = false
                                connectedInfo = info
                                break
                            }

                            AdbConstants.A_AUTH -> {
                                if (pkt.arg0 == AdbConstants.AUTH_TOKEN && keyPair != null) {
                                    when (authStage) {
                                        0 -> {
                                            authStage = 1
                                            val sig = AdbRsaKeyManager.signAuthToken(keyPair.private, pkt.payload)
                                            sendPacket(
                                                AdbMessage.create(AdbConstants.A_AUTH, AdbConstants.AUTH_SIGNATURE, 0, sig),
                                                2500
                                            )
                                            deadline = maxOf(deadline, System.currentTimeMillis() + 2500L)
                                        }
                                        1 -> {
                                            authStage = 2
                                            lastAuthRequestedByTarget = true
                                            val rsaPub = keyPair.public as? RSAPublicKey
                                            if (rsaPub != null) {
                                                val pubPayload = AdbRsaKeyManager.encodeAndroidPublicKeyPayload(rsaPub)
                                                sendPacket(
                                                    AdbMessage.create(AdbConstants.A_AUTH, AdbConstants.AUTH_RSAPUBLICKEY, 0, pubPayload),
                                                    2500
                                                )
                                                onWaitingForUserAuth(true)
                                                deadline = System.currentTimeMillis() + waitForUserAuthMs
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            onWaitingForUserAuth(false)
            connectedInfo
        }
    }

    suspend fun reboot(target: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAuthorized) {
            connectHandshake() ?: return@withContext false
        }
        ioMutex.withLock {
            if (closed || !isAuthorized) return@withContext false
            val localId = nextLocalStreamId.getAndIncrement()
            val cleanTarget = when (target.lowercase().trim()) {
                "system", "" -> ""
                "fastbootd" -> "fastboot"
                else -> target.lowercase().trim()
            }
            val dest = "reboot:$cleanTarget\u0000".toByteArray(Charsets.UTF_8)
            val sent = sendPacket(AdbMessage.create(AdbConstants.A_OPEN, localId, 0, dest), 3000)
            if (!sent) {
                close()
                return@withLock false
            }

            // Wait briefly for OKAY or CLSE; USB drop here means kernel initiated reboot
            try {
                readPacket(800)
            } catch (_: Exception) {}
            true
        }
    }

    suspend fun rebootToBootloader(): Boolean = reboot("bootloader")
    suspend fun rebootToFastbootd(): Boolean = reboot("fastboot")
    suspend fun rebootToRecovery(): Boolean = reboot("recovery")
    suspend fun rebootToSideload(): Boolean = reboot("sideload")
    suspend fun rebootToSideloadAutoReboot(): Boolean = reboot("sideload-auto-reboot")

    suspend fun executeCommand(cmd: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = cmd.trim().removePrefix("adb").trim()
        if (clean.startsWith("reboot")) {
            val target = clean.removePrefix("reboot").trim()
            val ok = reboot(target)
            val label = target.ifBlank { "system" }
            return@withContext Pair(ok, if (ok) "Rebooting device to $label..." else "Reboot failed")
        }
        if (clean == "devices") {
            return@withContext Pair(true, "$productName\t${detectedMode.name}")
        }
        if (detectedMode == FastbootMode.ADB_SIDELOAD) {
            return@withContext Pair(false, "Device is in ADB Sideload mode — select a ZIP on the Sideload tab")
        }
        if (!isAuthorized) {
            connectHandshake() ?: return@withContext Pair(false, "ADB unauthorized — check target phone prompt")
        }

        ioMutex.withLock {
            if (closed || !isAuthorized) return@withLock Pair(false, "ADB device not connected")
            val localId = nextLocalStreamId.getAndIncrement()
            val service = when {
                clean.startsWith("shell:") -> clean
                clean.startsWith("shell ") -> "shell:" + clean.removePrefix("shell ").trim()
                clean == "shell" -> "shell:"
                else -> "shell:$clean"
            }
            val openMsg = AdbMessage.create(AdbConstants.A_OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.UTF_8))
            if (!sendPacket(openMsg, 3000)) {
                close()
                return@withLock Pair(false, "Failed to send ADB command")
            }

            var remoteId = 0
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + 8000L

            while (System.currentTimeMillis() < deadline && !closed) {
                val pkt = readPacket(1500) ?: break
                when (pkt.command) {
                    AdbConstants.A_OKAY -> {
                        if (remoteId == 0) remoteId = pkt.arg0
                    }
                    AdbConstants.A_WRTE -> {
                        if (remoteId == 0) remoteId = pkt.arg0
                        if (pkt.payload.isNotEmpty()) {
                            out.append(String(pkt.payload, Charsets.UTF_8))
                        }
                        if (remoteId != 0) sendOkay(localId, remoteId)
                    }
                    AdbConstants.A_CLSE -> {
                        if (remoteId != 0) sendClose(localId, remoteId)
                        break
                    }
                }
            }
            Pair(true, out.toString().trim().ifEmpty { "Command completed" })
        }
    }

    /**
     * Executes AOSP ADB Sideload (`sideload-host:<size>:<block_size>` with fallback to legacy
     * `sideload:<size>`) over an active Recovery Sideload USB connection.
     */
    suspend fun sideloadZip(
        fileName: String,
        totalBytes: Long,
        openSeekableChannel: () -> Pair<Closeable?, FileChannel?>,
        isCancelled: () -> Boolean,
        onProgress: (AdbSideloadProgress) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (totalBytes <= 0L) {
            return@withContext Pair(false, "Selected ZIP file is empty or unreadable")
        }
        if (!isAuthorized) {
            connectHandshake() ?: return@withContext Pair(false, "ADB Sideload handshake failed")
        }

        ioMutex.withLock {
            if (closed) return@withLock Pair(false, "USB device disconnected")

            var channelOwner: Closeable? = null
            var channel: FileChannel? = null

            try {
                val opened = openSeekableChannel()
                channelOwner = opened.first
                channel = opened.second
                if (channel == null) {
                    return@withLock Pair(false, "Cannot open seekable stream for $fileName")
                }

                onProgress(
                    AdbSideloadProgress(
                        state = AdbSideloadState.SENDING,
                        fileName = fileName,
                        percent = 0,
                        uniqueBytesSent = 0L,
                        totalBytes = totalBytes,
                        speedMbPerSec = 0.0,
                        statusMessage = "Connecting to ADB Sideload..."
                    )
                )

                val blockSize = AdbConstants.SIDELOAD_BLOCK_SIZE
                val localId = nextLocalStreamId.getAndIncrement()
                val hostService = "sideload-host:$totalBytes:$blockSize\u0000".toByteArray(Charsets.UTF_8)
                if (!sendPacket(AdbMessage.create(AdbConstants.A_OPEN, localId, 0, hostService), 5000)) {
                    return@withLock Pair(false, "Failed to start sideload-host service")
                }

                // Wait for OKAY or CLSE (CLSE means pre-5.0 recovery only supports legacy `sideload:<size>`)
                var remoteId = 0
                var needLegacyFallback = false
                val initDeadline = System.currentTimeMillis() + 8000L
                while (System.currentTimeMillis() < initDeadline && !closed && !isCancelled()) {
                    val pkt = when (val outcome = readPacketOutcome(1500)) {
                        is ReadOutcome.Packet -> outcome.message
                        is ReadOutcome.Timeout -> continue
                        is ReadOutcome.IoError -> {
                            close()
                            return@withLock Pair(false, "USB connection lost starting sideload")
                        }
                    }
                    when (pkt.command) {
                        AdbConstants.A_OKAY -> {
                            remoteId = pkt.arg0
                            break
                        }
                        AdbConstants.A_CLSE -> {
                            needLegacyFallback = true
                            break
                        }
                    }
                }

                if (isCancelled()) {
                    if (remoteId != 0) sendClose(localId, remoteId)
                    return@withLock Pair(false, "Sideload cancelled")
                }

                if (needLegacyFallback) {
                    return@withLock runLegacySequentialSideload(
                        fileName = fileName,
                        totalBytes = totalBytes,
                        channel = channel,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }

                if (remoteId == 0) {
                    return@withLock Pair(false, "Recovery did not acknowledge sideload-host")
                }

                val totalBlocks = ((totalBytes + blockSize - 1L) / blockSize).toInt().coerceAtLeast(1)
                val trackSeenBlocks = totalBlocks <= 262_144 // Up to 16 GB in 64 KB blocks
                val seenBlocks = if (trackSeenBlocks) BooleanArray(totalBlocks) else null
                var uniqueBytes = 0L
                var totalBytesTransferred = 0L

                val maxChunk = minOf(negotiatedMaxPayload, blockSize).coerceAtLeast(4096)
                val blockBuf = ByteArray(blockSize)
                val packetBuf = ByteArray(maxChunk)

                var speedWindowStart = System.currentTimeMillis()
                var speedWindowBytes = 0L
                var currentSpeedMb = 0.0
                var lastUiUpdateMs = 0L
                var allBlocksServed = false
                var lastActivityMs = System.currentTimeMillis()

                // Track overall idle window (up to 15 minutes during heavy GAPPS / OTA script installs)
                var sideloadIoErrors = 0
                while (!closed && !isCancelled() && isDevicePresent()) {
                    if (System.currentTimeMillis() - lastActivityMs > 900_000L) {
                        sendClose(localId, remoteId)
                        return@withLock Pair(false, "Sideload timed out waiting for recovery")
                    }

                    val pkt = when (val outcome = readPacketOutcome(1500)) {
                        is ReadOutcome.Packet -> {
                            sideloadIoErrors = 0
                            outcome.message
                        }
                        is ReadOutcome.Timeout -> {
                            sideloadIoErrors = 0
                            continue
                        }
                        is ReadOutcome.IoError -> {
                            sideloadIoErrors++
                            if (!isDevicePresent() || sideloadIoErrors >= 3) break
                            continue
                        }
                    }
                    lastActivityMs = System.currentTimeMillis()

                    when (pkt.command) {
                        AdbConstants.A_CLSE -> {
                            sendClose(localId, remoteId)
                            return@withLock if (allBlocksServed) {
                                onProgress(
                                    AdbSideloadProgress(
                                        state = AdbSideloadState.COMPLETED,
                                        fileName = fileName,
                                        percent = 100,
                                        uniqueBytesSent = totalBytes,
                                        totalBytes = totalBytes,
                                        speedMbPerSec = currentSpeedMb,
                                        statusMessage = "Sideload completed"
                                    )
                                )
                                Pair(true, "ADB Sideload completed")
                            } else {
                                Pair(false, "Recovery closed sideload connection")
                            }
                        }

                        AdbConstants.A_WRTE -> {
                            if (pkt.arg0 != 0) remoteId = pkt.arg0
                            sendOkay(localId, remoteId)

                            val cmdStr = String(pkt.payload, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                            if (cmdStr == "DONEDONE") {
                                sendClose(localId, remoteId)
                                onProgress(
                                    AdbSideloadProgress(
                                        state = AdbSideloadState.COMPLETED,
                                        fileName = fileName,
                                        percent = 100,
                                        uniqueBytesSent = totalBytes,
                                        totalBytes = totalBytes,
                                        speedMbPerSec = currentSpeedMb,
                                        statusMessage = "Sideload verified & installed"
                                    )
                                )
                                return@withLock Pair(true, "ADB Sideload completed")
                            }
                            if (cmdStr == "FAILFAIL") {
                                sendClose(localId, remoteId)
                                return@withLock Pair(false, "Recovery aborted sideload (FAILFAIL)")
                            }

                            val blockIdx = cmdStr.toLongOrNull() ?: continue
                            val offset = blockIdx * blockSize.toLong()
                            if (offset < 0L || offset >= totalBytes) {
                                sendClose(localId, remoteId)
                                return@withLock Pair(false, "Invalid block request ($blockIdx) from recovery")
                            }

                            val toRead = minOf(blockSize.toLong(), totalBytes - offset).toInt()
                            channel.position(offset)
                            val bb = ByteBuffer.wrap(blockBuf, 0, toRead)
                            while (bb.hasRemaining()) {
                                val r = channel.read(bb)
                                if (r <= 0) break
                            }
                            val actualRead = bb.position()
                            if (actualRead <= 0) {
                                sendClose(localId, remoteId)
                                return@withLock Pair(false, "Unexpected EOF reading ZIP block $blockIdx")
                            }

                            // Send block in chunked A_WRTE packets respecting flow control (A_OKAY per write)
                            var sentInBlock = 0
                            while (sentInBlock < actualRead && !closed && !isCancelled()) {
                                val sliceLen = minOf(maxChunk, actualRead - sentInBlock)
                                System.arraycopy(blockBuf, sentInBlock, packetBuf, 0, sliceLen)
                                if (!sendPacketSlice(AdbConstants.A_WRTE, localId, remoteId, packetBuf, sliceLen, 10_000)) {
                                    return@withLock Pair(false, "USB write error during sideload")
                                }

                                // Wait for A_OKAY before sending next slice
                                var gotAck = false
                                val ackDeadline = System.currentTimeMillis() + 15_000L
                                while (System.currentTimeMillis() < ackDeadline && !closed && !isCancelled()) {
                                    val ack = when (val ackOut = readPacketOutcome(1500)) {
                                        is ReadOutcome.Packet -> ackOut.message
                                        is ReadOutcome.Timeout -> continue
                                        is ReadOutcome.IoError -> {
                                            close()
                                            return@withLock Pair(false, "USB connection lost during sideload")
                                        }
                                    }
                                    if (ack.command == AdbConstants.A_OKAY) {
                                        gotAck = true
                                        break
                                    } else if (ack.command == AdbConstants.A_CLSE) {
                                        sendClose(localId, remoteId)
                                        return@withLock Pair(false, "Recovery closed sideload during transfer")
                                    }
                                }
                                if (!gotAck && !isCancelled()) {
                                    return@withLock Pair(false, "Timed out waiting for sideload ACK")
                                }
                                sentInBlock += sliceLen
                            }

                            if (seenBlocks != null) {
                                val bIdxInt = blockIdx.toInt()
                                if (bIdxInt in seenBlocks.indices && !seenBlocks[bIdxInt]) {
                                    seenBlocks[bIdxInt] = true
                                    uniqueBytes = minOf(totalBytes, uniqueBytes + actualRead)
                                }
                            } else {
                                uniqueBytes = minOf(totalBytes, uniqueBytes + actualRead)
                            }
                            totalBytesTransferred += actualRead
                            speedWindowBytes += actualRead
                            if (uniqueBytes >= totalBytes) {
                                allBlocksServed = true
                            }

                            val now = System.currentTimeMillis()
                            val elapsedSec = (now - speedWindowStart) / 1000.0
                            if (elapsedSec >= 0.4) {
                                currentSpeedMb = (speedWindowBytes / (1024.0 * 1024.0)) / elapsedSec
                                speedWindowStart = now
                                speedWindowBytes = 0L
                            }

                            val pct = ((uniqueBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct >= 100 || now - lastUiUpdateMs >= 65L) {
                                lastUiUpdateMs = now
                                val state = if (allBlocksServed) AdbSideloadState.INSTALLING_ON_DEVICE else AdbSideloadState.SENDING
                                val msg = if (allBlocksServed) {
                                    "Installing $fileName on device..."
                                } else {
                                    "Sending $fileName..."
                                }
                                onProgress(
                                    AdbSideloadProgress(
                                        state = state,
                                        fileName = fileName,
                                        percent = pct,
                                        uniqueBytesSent = uniqueBytes,
                                        totalBytes = totalBytes,
                                        speedMbPerSec = currentSpeedMb,
                                        statusMessage = msg
                                    )
                                )
                            }
                        }
                    }
                }

                if (isCancelled()) {
                    sendClose(localId, remoteId)
                    return@withLock Pair(false, "Sideload cancelled by user")
                }
                Pair(false, "USB connection lost during sideload")
            } finally {
                try { channel?.close() } catch (_: Exception) {}
                try { channelOwner?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun runLegacySequentialSideload(
        fileName: String,
        totalBytes: Long,
        channel: FileChannel,
        isCancelled: () -> Boolean,
        onProgress: (AdbSideloadProgress) -> Unit
    ): Pair<Boolean, String> {
        val localId = nextLocalStreamId.getAndIncrement()
        val legacySvc = "sideload:$totalBytes\u0000".toByteArray(Charsets.UTF_8)
        if (!sendPacket(AdbMessage.create(AdbConstants.A_OPEN, localId, 0, legacySvc), 5000)) {
            return Pair(false, "Failed to open legacy ADB sideload")
        }

        var remoteId = 0
        val openDeadline = System.currentTimeMillis() + 8000L
        while (System.currentTimeMillis() < openDeadline && !closed && !isCancelled()) {
            when (val outcome = readPacketOutcome(1500)) {
                is ReadOutcome.Timeout -> continue
                is ReadOutcome.IoError -> return Pair(false, "USB connection lost during sideload open")
                is ReadOutcome.Packet -> {
                    val pkt = outcome.message
                    if (pkt.command == AdbConstants.A_OKAY) {
                        remoteId = pkt.arg0
                        break
                    } else if (pkt.command == AdbConstants.A_CLSE) {
                        return Pair(false, "Recovery rejected sideload service")
                    }
                }
            }
        }
        if (remoteId == 0) return Pair(false, "Recovery timed out on sideload open")

        channel.position(0L)
        val maxChunk = minOf(negotiatedMaxPayload, AdbConstants.SIDELOAD_BLOCK_SIZE).coerceAtLeast(4096)
        val buf = ByteArray(maxChunk)
        val bb = ByteBuffer.wrap(buf)
        var sentTotal = 0L
        var lastUiMs = 0L
        var speedStart = System.currentTimeMillis()
        var speedBytes = 0L
        var speedMb = 0.0

        while (sentTotal < totalBytes && !closed && !isCancelled()) {
            bb.clear()
            val n = channel.read(bb)
            if (n <= 0) break
            if (!sendPacketSlice(AdbConstants.A_WRTE, localId, remoteId, buf, n, 10_000)) {
                return Pair(false, "USB write failed during sideload")
            }
            var gotAck = false
            val ackDeadline = System.currentTimeMillis() + 15_000L
            while (System.currentTimeMillis() < ackDeadline && !closed && !isCancelled()) {
                when (val outcome = readPacketOutcome(1500)) {
                    is ReadOutcome.Timeout -> continue
                    is ReadOutcome.IoError -> return Pair(false, "USB connection lost waiting for sideload ACK")
                    is ReadOutcome.Packet -> {
                        val ack = outcome.message
                        if (ack.command == AdbConstants.A_OKAY) {
                            gotAck = true
                            break
                        } else if (ack.command == AdbConstants.A_CLSE) {
                            return Pair(false, "Recovery aborted sideload")
                        }
                    }
                }
            }
            if (!gotAck) return Pair(false, "Sideload ACK timeout")

            sentTotal += n
            speedBytes += n
            val now = System.currentTimeMillis()
            val dt = (now - speedStart) / 1000.0
            if (dt >= 0.4) {
                speedMb = (speedBytes / (1024.0 * 1024.0)) / dt
                speedStart = now
                speedBytes = 0L
            }
            val pct = ((sentTotal * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (pct >= 100 || now - lastUiMs >= 65L) {
                lastUiMs = now
                onProgress(
                    AdbSideloadProgress(
                        state = if (sentTotal >= totalBytes) AdbSideloadState.INSTALLING_ON_DEVICE else AdbSideloadState.SENDING,
                        fileName = fileName,
                        percent = pct,
                        uniqueBytesSent = sentTotal,
                        totalBytes = totalBytes,
                        speedMbPerSec = speedMb,
                        statusMessage = if (sentTotal >= totalBytes) "Installing $fileName on device..." else "Sending $fileName..."
                    )
                )
            }
        }

        sendClose(localId, remoteId)
        return if (sentTotal >= totalBytes) {
            onProgress(
                AdbSideloadProgress(
                    state = AdbSideloadState.COMPLETED,
                    fileName = fileName,
                    percent = 100,
                    uniqueBytesSent = totalBytes,
                    totalBytes = totalBytes,
                    speedMbPerSec = speedMb,
                    statusMessage = "Sideload sent to recovery"
                )
            )
            Pair(true, "ADB Sideload completed")
        } else {
            Pair(false, "Sideload stopped prematurely")
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            isAuthorized = false
            try {
                connection.releaseInterface(usbInterface)
                connection.close()
            } catch (_: Exception) {}
        }
    }
}
