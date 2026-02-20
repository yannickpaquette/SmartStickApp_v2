package com.smartstick.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages a Bluetooth Classic (SPP) serial connection in background threads.
 * Compatible with ESP32 Bluetooth Classic via the standard SPP UUID.
 */
class BluetoothSerialService(private val listener: Listener) {

    // Standard SPP (Serial Port Profile) UUID — used by HC-05, HC-06, ESP32 BT Classic
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile
    private var connected = false

    interface Listener {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onDataReceived(data: String)
        fun onError(message: String)
    }

    fun isConnected(): Boolean = connected

    fun connect(device: BluetoothDevice) {
        // Cancel any ongoing connection
        connectThread?.cancel()
        connectedThread?.cancel()
        connected = false

        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    fun disconnect() {
        connectThread?.cancel()
        connectedThread?.cancel()
        connected = false
        listener.onDisconnected()
    }

    fun send(data: String) {
        if (!connected) {
            listener.onError("Non connecté")
            return
        }
        connectedThread?.write(data)
    }

    // =========================================================
    // ConnectThread — establishes the BT socket connection
    // =========================================================
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {

        private val socket: BluetoothSocket? by lazy {
            try {
                @Suppress("MissingPermission")
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                listener.onError("Impossible de créer le socket: ${e.message}")
                null
            }
        }

        override fun run() {
            try {
                @Suppress("MissingPermission")
                socket?.connect()
            } catch (e: IOException) {
                try { socket?.close() } catch (_: IOException) {}
                listener.onError("Connexion échouée: ${e.message}")
                return
            }

            socket?.let { s ->
                connected = true
                @Suppress("MissingPermission")
                val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                listener.onConnected(name)
                connectedThread = ConnectedThread(s)
                connectedThread?.start()
            }
        }

        fun cancel() {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    // =========================================================
    // ConnectedThread — reads/writes data on an open socket
    // =========================================================
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {

        private val inputStream: InputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream
        private val buffer = ByteArray(1024)

        override fun run() {
            // Read loop
            while (connected) {
                try {
                    val bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)
                        listener.onDataReceived(data)
                    }
                } catch (e: IOException) {
                    if (connected) {
                        connected = false
                        listener.onDisconnected()
                    }
                    break
                }
            }
        }

        fun write(data: String) {
            try {
                // Append newline so ESP32 can delimit commands
                outputStream.write((data + "\n").toByteArray())
                outputStream.flush()
            } catch (e: IOException) {
                listener.onError("Erreur d'envoi: ${e.message}")
                connected = false
                listener.onDisconnected()
            }
        }

        fun cancel() {
            connected = false
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
