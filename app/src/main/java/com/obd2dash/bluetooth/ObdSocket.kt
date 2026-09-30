package com.obd2dash.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP) transport to an ELM327 adapter.
 *
 * All calls block and must run off the main thread.
 */
@SuppressLint("MissingPermission")
class ObdSocket(private val device: BluetoothDevice) {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val buffer = ByteArray(512)

    val isConnected: Boolean get() = socket?.isConnected == true

    fun connect(adapter: BluetoothAdapter?) {
        // Discovery starves the RFCOMM handshake on most radios.
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        var s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (first: IOException) {
            // Many clone adapters advertise a broken SDP record. Falling back to a
            // direct channel-1 RFCOMM socket is the long-standing workaround.
            try {
                s.close()
            } catch (_: IOException) {
            }
            s = createFallbackSocket()
                ?: throw IOException("Connect failed: " + (first.message ?: "unknown"), first)
            s.connect()
        }

        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    private fun createFallbackSocket(): BluetoothSocket? = try {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        method.invoke(device, 1) as BluetoothSocket
    } catch (_: Exception) {
        null
    }

    /**
     * Sends a command and reads until the ELM327 prompt character.
     * Returns the raw reply text with the prompt stripped.
     */
    fun request(command: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("Not connected")
        val inp = input ?: throw IOException("Not connected")

        drain(inp)
        out.write((command + "\r").toByteArray())
        out.flush()

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val available = inp.available()
            if (available > 0) {
                // Read whatever has arrived in one call; byte-at-a-time reads cost a JNI hop each.
                val count = inp.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0) throw IOException("Stream closed")
                for (i in 0 until count) {
                    val c = (buffer[i].toInt() and 0xFF).toChar()
                    if (c == PROMPT) return sb.toString()
                    if (c.code != 0) sb.append(c)
                }
            } else {
                Thread.sleep(1)
            }
        }
        throw IOException("Timeout waiting for reply to " + command)
    }

    /** Discards anything left over from a previous command. */
    private fun drain(inp: InputStream) {
        try {
            while (inp.available() > 0) {
                if (inp.read() < 0) break
            }
        } catch (_: IOException) {
        }
    }

    fun close() {
        try {
            input?.close()
        } catch (_: IOException) {
        }
        try {
            output?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        input = null
        output = null
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PROMPT = '>'
    }
}
