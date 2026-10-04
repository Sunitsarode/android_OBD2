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

    /**
     * The adapter did not finish a reply in time. Cheap clones drop a prompt now
     * and then, so this is a missed answer, not proof that the link is gone.
     */
    class ReplyTimeoutException(command: String) : IOException("No reply to " + command)

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val buffer = ByteArray(512)

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Tries the standard secure socket, then an insecure one (many clones pair
     * without authentication and connect more reliably that way), then RFCOMM
     * channel 1 directly, for adapters that advertise a broken service record.
     */
    fun connect(adapter: BluetoothAdapter?) {
        // Discovery starves the RFCOMM handshake on most radios.
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        val attempts = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { createChannelSocket() }
        )
        var lastError: Exception? = null
        for (create in attempts) {
            val candidate = try {
                create()
            } catch (e: Exception) {
                lastError = e
                null
            } ?: continue
            try {
                candidate.connect()
                socket = candidate
                input = candidate.inputStream
                output = candidate.outputStream
                return
            } catch (e: IOException) {
                lastError = e
                try {
                    candidate.close()
                } catch (_: IOException) {
                }
            }
        }
        throw IOException("Could not connect to the adapter: " + (lastError?.message ?: "no socket"), lastError)
    }

    private fun createChannelSocket(): BluetoothSocket? = try {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        method.invoke(device, 1) as BluetoothSocket
    } catch (_: Exception) {
        null
    }

    /**
     * Sends a command and reads until the ELM327 prompt character. Throws
     * [ReplyTimeoutException] if no prompt arrives in time, after resyncing so
     * the next command starts clean; any other IOException means the link failed.
     */
    fun request(command: String, timeoutMs: Long): String {
        val out = output ?: throw IOException("Not connected")
        val inp = input ?: throw IOException("Not connected")

        drain(inp)
        out.write((command + "\r").toByteArray())
        out.flush()

        val sb = StringBuilder()
        if (readUntilPrompt(inp, sb, timeoutMs)) return sb.toString()
        resync(out, inp)
        throw ReplyTimeoutException(command)
    }

    /** Reads into [sb] until the prompt; false on timeout. */
    private fun readUntilPrompt(inp: InputStream, sb: StringBuilder, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val available = inp.available()
            if (available > 0) {
                // Read whatever has arrived in one call; byte-at-a-time reads cost a JNI hop each.
                val count = inp.read(buffer, 0, minOf(available, buffer.size))
                if (count < 0) throw IOException("Stream closed")
                for (i in 0 until count) {
                    val c = (buffer[i].toInt() and 0xFF).toChar()
                    if (c == PROMPT) return true
                    if (c.code != 0) sb.append(c)
                }
            } else {
                Thread.sleep(1)
            }
        }
        return false
    }

    /**
     * Gets the adapter back to its prompt after a missed reply. Any character
     * interrupts a request still in progress ("STOPPED"); a space is used because
     * an idle adapter just holds it until the next command, where it is ignored.
     */
    private fun resync(out: OutputStream, inp: InputStream) {
        out.write(' '.code)
        out.flush()
        readUntilPrompt(inp, StringBuilder(), RESYNC_MS)
        drain(inp)
    }

    /** Discards anything left over from a previous command. */
    private fun drain(inp: InputStream) {
        try {
            while (inp.available() > 0) {
                if (inp.read(buffer) < 0) break
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
        private const val RESYNC_MS = 600L
    }
}
