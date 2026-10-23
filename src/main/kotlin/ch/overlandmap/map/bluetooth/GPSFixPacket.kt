package ch.overlandmap.map.bluetooth

import ch.overlandmap.map.tracking.LocationFix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Wire compatible with OverlandMap iOS GPSFixPacket v1; minimum ATT MTU payload. */
object BluetoothGPSProtocol {
    val service: UUID = UUID.fromString("9F3A7C10-5B2E-4C8D-A1F6-0D4E2B7A9C31")
    val characteristic: UUID = UUID.fromString("9F3A7C11-5B2E-4C8D-A1F6-0D4E2B7A9C31")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

data class GPSFixPacket(val fix: LocationFix, val simulated: Boolean = false, val sequence: Int = 0) {
    fun encoded(): ByteArray {
        fun scaled(v: Double, scale: Double, min: Double, max: Double) =
            kotlin.math.floor(kotlin.math.abs(v * scale) + 0.5).let { if (v < 0) -it else it }.coerceIn(min, max).toInt()
        fun u16(v: Double) = scaled(v, 1.0, 0.0, 65535.0).toShort()
        val flags = (if (fix.altitude != null) 1 else 0) or (if (fix.accuracyM >= 0) 2 else 0) or
            (if (fix.speedMps != null) 4 else 0) or (if (fix.courseDeg != null) 8 else 0) or (if (simulated) 16 else 0)
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1.toByte()); put(flags.toByte())
            putInt(scaled(fix.latitude, 1e7, Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()))
            putInt(scaled(fix.longitude, 1e7, Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()))
            putShort(scaled(fix.altitude ?: 0.0, 1.0, -32768.0, 32767.0).toShort())
            putShort(u16(fix.accuracyM.coerceAtLeast(0f) * 10.0))
            putShort(u16((fix.speedMps ?: 0f) * 100.0))
            val course = ((fix.courseDeg ?: 0f).toDouble() % 360 + 360) % 360
            putShort(scaled(course, 10.0, 0.0, 3599.0).toShort()); putShort(sequence.toShort())
        }.array()
    }
    companion object {
        fun decode(data: ByteArray, now: Long = System.currentTimeMillis()): GPSFixPacket? {
            if (data.size < 20 || data[0].toInt() != 1) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            b.get(); val flags = b.get().toInt()
            val lat = b.int / 1e7; val lon = b.int / 1e7
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val altitude = b.short.toDouble(); val accuracy = (b.short.toInt() and 65535) / 10f
            val speed = (b.short.toInt() and 65535) / 100f; val course = (b.short.toInt() and 65535) / 10f
            return GPSFixPacket(LocationFix(lat, lon, altitude.takeIf { flags and 1 != 0 }, now,
                if (flags and 2 != 0) accuracy else 5f, course.takeIf { flags and 8 != 0 },
                speed.takeIf { flags and 4 != 0 }), flags and 16 != 0, b.short.toInt() and 65535)
        }
    }
}
