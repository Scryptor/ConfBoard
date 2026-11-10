package board.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Типы кадров протокола. */
object Frame {
    const val HELLO = 1
    const val WELCOME = 2
    const val OP = 3
    const val MEMBERS = 4
    const val PING = 5
    const val BOARD_LOAD = 6
    const val REQ_NEW_BOARD = 7
    const val REQ_OPEN_BOARD = 8
    const val REQ_LIST_BOARDS = 9
    const val BOARD_LIST = 10
    const val REQ_DELETE_BOARD = 11
    const val REQ_PRESENT = 12      // клиент → хозяин: стать ведущим / перестать
    const val PRESENTER = 13        // хозяин → все: id ведущего ("" — нет)
    const val VIEWPORT = 14         // ведущий → все: видимая область [l, t, r, b]
    const val MEMBER_UPDATE = 15    // клиент → хозяин: изменились данные устройства (режим ТВ)
    const val BOARD_RENAME = 16     // хозяин → все: текущая доска переименована (id, name)
    const val REQ_RENAME_BOARD = 17 // клиент → хозяин: переименовать доску (id, name)

    const val MAX_SIZE = 128 * 1024 * 1024
}

/**
 * TCP-соединение с кадрами `int длина | byte тип | payload`.
 * Чтение и запись — в собственных потоках, поэтому send() никогда не блокирует.
 */
class Conn(
    private val socket: Socket,
    private val onFrame: (Conn, Int, ByteArray) -> Unit,
    private val onClosed: (Conn) -> Unit,
) {
    @Volatile var tag: Any? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    val remote: String = socket.remoteSocketAddress?.toString() ?: "?"

    fun start() {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        Thread({ readLoop() }, "conn-read $remote").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "conn-write $remote").apply { isDaemon = true }.start()
    }

    fun send(type: Int, payload: ByteArray = EMPTY) {
        if (closed.get()) return
        val frame = ByteArray(5 + payload.size)
        val len = payload.size + 1
        frame[0] = (len ushr 24).toByte(); frame[1] = (len ushr 16).toByte()
        frame[2] = (len ushr 8).toByte(); frame[3] = len.toByte()
        frame[4] = type.toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        queue.offer(frame)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            queue.offer(EMPTY) // разбудить writer
            onClosed(this)
        }
    }

    val isClosed get() = closed.get()

    private fun readLoop() {
        try {
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            while (!closed.get()) {
                val len = inp.readInt()
                if (len < 1 || len > Frame.MAX_SIZE) throw IOException("bad frame length $len")
                val type = inp.readUnsignedByte()
                val payload = ByteArray(len - 1)
                inp.readFully(payload)
                onFrame(this, type, payload)
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (!closed.get()) {
                var f = queue.take()
                if (closed.get()) break
                // Собираем всё, что накопилось, в одну отправку.
                while (true) {
                    if (f.isNotEmpty()) out.write(f)
                    f = queue.poll() ?: break
                }
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    companion object {
        val EMPTY = ByteArray(0)
        const val READ_TIMEOUT_MS = 8000
        const val PING_INTERVAL_MS = 2000L

        fun connect(host: InetAddress, port: Int, timeoutMs: Int = 3000): Socket {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return s
        }
    }
}

/** Маяк хозяина: «я здесь, подключайтесь». */
data class Beacon(
    val deviceId: String,
    val name: String,
    val port: Int,
    val boardId: String,
    val hostSince: Long,
    val address: InetAddress? = null,
) {
    fun encode(): ByteArray =
        listOf(PREFIX, deviceId, name.replace('|', ' '), port.toString(), boardId, hostSince.toString())
            .joinToString("|").toByteArray(Charsets.UTF_8)

    /** Побеждает ли этот хозяин другого при конфликте двух хозяев. */
    fun beats(otherId: String, otherSince: Long): Boolean =
        hostSince < otherSince || (hostSince == otherSince && deviceId < otherId)

    companion object {
        const val PREFIX = "OBRD1"

        fun decode(bytes: ByteArray, len: Int, from: InetAddress?): Beacon? {
            val p = String(bytes, 0, len, Charsets.UTF_8).split('|')
            if (p.size != 6 || p[0] != PREFIX) return null
            return runCatching {
                Beacon(p[1], p[2], p[3].toInt(), p[4], p[5].toLong(), from)
            }.getOrNull()
        }
    }
}

/** Канал маяков. В приложении — UDP broadcast, в тестах — общая шина в памяти. */
interface BeaconTransport {
    fun start(onBeacon: (Beacon) -> Unit)
    fun send(b: Beacon)
    fun stop()
}

/** Платформенные мелочи (на Android — блокировка multicast для приёма broadcast). */
interface NetPlatform {
    fun acquire() {}
    fun release() {}
}

class UdpBeaconTransport(
    private val port: Int = DEFAULT_PORT,
    private val platform: NetPlatform = object : NetPlatform {},
) : BeaconTransport {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private val warned = java.util.Collections.synchronizedSet(HashSet<InetAddress>())

    override fun start(onBeacon: (Beacon) -> Unit) {
        platform.acquire()
        running = true
        val s = DatagramSocket(null as java.net.SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        s.broadcast = true
        socket = s
        CoreLog.d("beacon socket bound on $port, broadcast to ${broadcastAddresses()}")
        Thread({
            val buf = ByteArray(1024)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    Beacon.decode(p.data, p.length, p.address)?.let(onBeacon)
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "beacon-rx").apply { isDaemon = true }.start()
    }

    override fun send(b: Beacon) {
        val s = socket ?: return
        val data = b.encode()
        for (addr in broadcastAddresses()) {
            try {
                s.send(DatagramPacket(data, data.size, addr, port))
            } catch (t: Exception) {
                if (!warned.contains(addr)) { warned.add(addr); CoreLog.e("beacon send to $addr failed", t) }
            }
        }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        platform.release()
    }

    companion object {
        const val DEFAULT_PORT = 45454

        fun broadcastAddresses(): List<InetAddress> {
            val result = LinkedHashSet<InetAddress>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        if (ia.address is Inet4Address) ia.broadcast?.let { result.add(it) }
                    }
                }
            }
            result.add(InetAddress.getByName("255.255.255.255"))
            return result.toList()
        }
    }
}

/** Шина маяков в памяти для тестов: все подписчики получают все маяки. */
class LocalBeaconBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Beacon) -> Unit>()

    fun transport(): BeaconTransport = object : BeaconTransport {
        private var l: ((Beacon) -> Unit)? = null
        override fun start(onBeacon: (Beacon) -> Unit) {
            l = onBeacon; listeners.add(onBeacon)
        }
        override fun send(b: Beacon) {
            val withAddr = b.copy(address = InetAddress.getLoopbackAddress())
            listeners.forEach { it(withAddr) }
        }
        override fun stop() {
            l?.let { listeners.remove(it) }
        }
    }
}
