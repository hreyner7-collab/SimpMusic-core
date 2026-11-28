package com.simpmusic.media_jvm.mpv

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class DisconnectedBtSpeaker(
    val mac: String,
    val endpointGuid: String,
    val name: String,
    val ksPath: String,
)

interface BluetoothAudioConnector {
    fun listDisconnectedSpeakers(): List<DisconnectedBtSpeaker>

    fun connectSpeaker(
        mac: String,
        endpointGuid: String,
    ): Boolean
}

/**
 * Native Windows Bluetooth A2DP Sink helper using JNA (no external binaries or PowerShell).
 *
 * Enumerates paired-but-unplugged Bluetooth speakers (`DeviceState == 8`) from Windows Core Audio
 * registry metadata, disables conflicting A2DP Source (`0000110A`) on dual-role devices like Amazon
 * Echo, and triggers `KSPROPERTY_ONESHOT_RECONNECT` on the A2DP Sink (`0000110B`) kernel path.
 */
object WindowsBluetoothAudioHelper : BluetoothAudioConnector {
    const val BT_CONNECT_PREFIX = "bt-connect:"

    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private const val HKEY_LOCAL_MACHINE = 0x80000002L
    private const val KEY_READ = 0x20019
    private const val REG_DWORD = 4
    private const val REG_SZ = 1
    private const val DEVICE_STATE_ACTIVE = 1
    private const val DEVICE_STATE_UNPLUGGED = 8
    private const val GENERIC_READ_WRITE = 0xC0000000.toInt()
    private const val FILE_SHARE_READ_WRITE = 3
    private const val OPEN_EXISTING = 3
    private const val IOCTL_KS_PROPERTY = 0x002F0003
    private const val KSPROPERTY_TYPE_GET = 1
    private const val KSPROPERTY_ONESHOT_RECONNECT = 0

    private const val MMDEVICES_RENDER_KEY =
        "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render"
    private const val PROP_BUS = "{a45c254e-df1c-4efd-8020-67d146a850e0},24"
    private const val PROP_FRIENDLY_NAME = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6"
    private const val PROP_KS_PATH = "{233164c8-1b2c-4c7d-bc68-b671687a2567},1"

    private val MAC_REGEX = Regex("([0-9a-fA-F]{12})_c00000000", RegexOption.IGNORE_CASE)
    private val KSPROPSETID_BT_AUDIO = UUID.fromString("7FA06C40-B8F6-4C7E-8556-E8C33A12E54D")
    private val GUID_A2DP_SOURCE_110A = UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb")

    private interface Advapi32Lib : Library {
        fun RegOpenKeyExW(
            hKey: Pointer,
            lpSubKey: WString,
            ulOptions: Int,
            samDesired: Int,
            phkResult: PointerByReference,
        ): Int

        fun RegEnumKeyExW(
            hKey: Pointer,
            dwIndex: Int,
            lpName: CharArray,
            lpcchName: IntByReference,
            lpReserved: Pointer?,
            lpClass: Pointer?,
            lpcchClass: Pointer?,
            lpftLastWriteTime: Pointer?,
        ): Int

        fun RegQueryValueExW(
            hKey: Pointer,
            lpValueName: WString,
            lpReserved: Pointer?,
            lpType: IntByReference?,
            lpData: Pointer?,
            lpcbData: IntByReference,
        ): Int

        fun RegCloseKey(hKey: Pointer): Int
    }

    private interface Kernel32Lib : Library {
        fun CreateFileW(
            lpFileName: WString,
            dwDesiredAccess: Int,
            dwShareMode: Int,
            lpSecurityAttributes: Pointer?,
            dwCreationDisposition: Int,
            dwFlagsAndAttributes: Int,
            hTemplateFile: Pointer?,
        ): Pointer

        fun DeviceIoControl(
            hDevice: Pointer,
            dwIoControlCode: Int,
            lpInBuffer: ByteArray,
            nInBufferSize: Int,
            lpOutBuffer: Pointer?,
            nOutBufferSize: Int,
            lpBytesReturned: IntByReference,
            lpOverlapped: Pointer?,
        ): Boolean

        fun CloseHandle(hObject: Pointer): Boolean
    }

    private interface BluetoothApisLib : Library {
        fun BluetoothSetServiceState(
            hRadio: Pointer?,
            pbtdi: Memory,
            pGuidService: ByteArray,
            dwServiceFlags: Int,
        ): Int
    }

    private val advapi32: Advapi32Lib? by lazy {
        if (!isWindows) null else runCatching { Native.load("advapi32", Advapi32Lib::class.java) }.getOrNull()
    }
    private val kernel32: Kernel32Lib? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", Kernel32Lib::class.java) }.getOrNull()
    }
    private val btApis: BluetoothApisLib? by lazy {
        if (!isWindows) null else runCatching { Native.load("BluetoothApis", BluetoothApisLib::class.java) }.getOrNull()
    }

    override fun listDisconnectedSpeakers(): List<DisconnectedBtSpeaker> {
        val reg = advapi32 ?: return emptyList()
        val hklm = Pointer.createConstant(HKEY_LOCAL_MACHINE)
        val rootRef = PointerByReference()
        if (reg.RegOpenKeyExW(hklm, WString(MMDEVICES_RENDER_KEY), 0, KEY_READ, rootRef) != 0) {
            return emptyList()
        }
        val rootKey = rootRef.value ?: return emptyList()
        val result = mutableListOf<DisconnectedBtSpeaker>()
        try {
            var idx = 0
            val nameBuf = CharArray(256)
            while (true) {
                val len = IntByReference(nameBuf.size)
                val rc = reg.RegEnumKeyExW(rootKey, idx++, nameBuf, len, null, null, null, null)
                if (rc != 0) break
                val guid = String(nameBuf, 0, len.value)
                val state = readDeviceState(reg, hklm, guid)
                if (state != DEVICE_STATE_UNPLUGGED) continue

                val propsPath = "$MMDEVICES_RENDER_KEY\\$guid\\Properties"
                val bus = readRegString(reg, hklm, propsPath, PROP_BUS)
                if (!bus.equals("BTHENUM", ignoreCase = true)) continue

                val rawKs = readRegString(reg, hklm, propsPath, PROP_KS_PATH) ?: continue
                if (!rawKs.contains("0000110b", ignoreCase = true)) continue
                val ksPath = rawKs.substringAfter("{2}.", rawKs)
                val mac = MAC_REGEX.find(ksPath)?.groupValues?.get(1)?.uppercase() ?: continue
                val name = readRegString(reg, hklm, propsPath, PROP_FRIENDLY_NAME)?.takeIf { it.isNotBlank() } ?: mac

                result += DisconnectedBtSpeaker(
                    mac = mac,
                    endpointGuid = guid,
                    name = name,
                    ksPath = ksPath,
                )
            }
        } finally {
            reg.RegCloseKey(rootKey)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    override fun connectSpeaker(
        mac: String,
        endpointGuid: String,
    ): Boolean {
        val reg = advapi32 ?: return false
        val k32 = kernel32 ?: return false
        val hklm = Pointer.createConstant(HKEY_LOCAL_MACHINE)

        // Already active?
        if (readDeviceState(reg, hklm, endpointGuid) == DEVICE_STATE_ACTIVE) return true

        // Disable conflicting 0000110A (A2DP Source) on dual-role speakers like Amazon Echo.
        disableA2dpSourceProfile(mac)

        val propsPath = "$MMDEVICES_RENDER_KEY\\$endpointGuid\\Properties"
        val rawKs = readRegString(reg, hklm, propsPath, PROP_KS_PATH) ?: return false
        val ksPath = rawKs.substringAfter("{2}.", rawKs)

        val sent = sendOneshotReconnect(k32, ksPath)
        if (!sent) return false

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(250L)
            if (readDeviceState(reg, hklm, endpointGuid) == DEVICE_STATE_ACTIVE) {
                return true
            }
        }
        return readDeviceState(reg, hklm, endpointGuid) == DEVICE_STATE_ACTIVE
    }

    private fun disableA2dpSourceProfile(macHex: String) {
        val bt = btApis ?: return
        val addr = macHex.toLongOrNull(16) ?: return
        // BLUETOOTH_DEVICE_INFO is 560 bytes on x64 Windows:
        // offset 0: DWORD dwSize (560), offset 8: ULONGLONG Address
        val info = Memory(560L)
        info.clear()
        info.setInt(0L, 560)
        info.setLong(8L, addr)
        runCatching {
            bt.BluetoothSetServiceState(null, info, uuidToWinGuidBytes(GUID_A2DP_SOURCE_110A), 0)
        }
    }

    private fun sendOneshotReconnect(
        k32: Kernel32Lib,
        ksPath: String,
    ): Boolean {
        val handle =
            k32.CreateFileW(
                WString(ksPath),
                GENERIC_READ_WRITE,
                FILE_SHARE_READ_WRITE,
                null,
                OPEN_EXISTING,
                0,
                null,
            )
        if (Pointer.nativeValue(handle) == -1L || Pointer.nativeValue(handle) == 0L) return false
        return try {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(uuidToWinGuidBytes(KSPROPSETID_BT_AUDIO))
            buf.putInt(KSPROPERTY_ONESHOT_RECONNECT)
            buf.putInt(KSPROPERTY_TYPE_GET)
            val returned = IntByReference()
            k32.DeviceIoControl(handle, IOCTL_KS_PROPERTY, buf.array(), 24, null, 0, returned, null)
        } finally {
            k32.CloseHandle(handle)
        }
    }

    private fun readDeviceState(
        reg: Advapi32Lib,
        hklm: Pointer,
        endpointGuid: String,
    ): Int? {
        val keyRef = PointerByReference()
        if (reg.RegOpenKeyExW(hklm, WString("$MMDEVICES_RENDER_KEY\\$endpointGuid"), 0, KEY_READ, keyRef) != 0) {
            return null
        }
        val key = keyRef.value ?: return null
        return try {
            val mem = Memory(4L)
            val type = IntByReference()
            val size = IntByReference(4)
            val rc = reg.RegQueryValueExW(key, WString("DeviceState"), null, type, mem, size)
            if (rc == 0 && type.value == REG_DWORD) mem.getInt(0L) else null
        } finally {
            reg.RegCloseKey(key)
        }
    }

    private fun readRegString(
        reg: Advapi32Lib,
        hklm: Pointer,
        subKey: String,
        valueName: String,
    ): String? {
        val keyRef = PointerByReference()
        if (reg.RegOpenKeyExW(hklm, WString(subKey), 0, KEY_READ, keyRef) != 0) {
            return null
        }
        val key = keyRef.value ?: return null
        return try {
            val type = IntByReference()
            val size = IntByReference(0)
            if (reg.RegQueryValueExW(key, WString(valueName), null, type, null, size) != 0 || size.value <= 0) {
                return null
            }
            if (type.value != REG_SZ) return null
            val mem = Memory(size.value.toLong())
            if (reg.RegQueryValueExW(key, WString(valueName), null, type, mem, size) != 0) {
                return null
            }
            mem.getWideString(0L)
        } finally {
            reg.RegCloseKey(key)
        }
    }

    internal fun uuidToWinGuidBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt((msb ushr 32).toInt())
        buf.putShort(((msb ushr 16) and 0xFFFFL).toShort())
        buf.putShort((msb and 0xFFFFL).toShort())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putLong(lsb)
        return buf.array()
    }
}
