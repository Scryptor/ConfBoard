package board.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Сессия совместной доски: поиск хозяина, роль хозяина или клиента,
 * синхронизация операций, миграция хозяина, автосохранение.
 *
 * Всё состояние меняется только в [exec] (последовательный исполнитель;
 * на Android — главный поток). Методы публичного API вызывать из него же.
 */
class Session(
    me: Member,
    private val store: BoardStore,
    private val exec: Executor,
    private val beacons: BeaconTransport,
    private val timing: Timing = Timing(),
) {
    enum class Role { SEARCHING, CONNECTING, CLIENT, HOST }

    data class Status(val role: Role, val hostName: String?, val members: List<Member>)

    /** Тайминги вынесены, чтобы тесты могли их ускорить. */
    data class Timing(
        val initialWaitMin: Long = 1200,
        val initialWaitMax: Long = 2200,
        val failoverBase: Long = 1300,
        val failoverStep: Long = 1200,
        val beaconInterval: Long = 1000,
        val saveDelay: Long = 1500,
        val retryWait: Long = 3500,
    )

    interface Listener {
        /** Доска заменена целиком. sameBoard=true — это та же доска (например, после переподключения). */
        fun onBoardReplaced(sameBoard: Boolean) {}
        fun onBoardChanged() {}
        fun onStatus(status: Status) {}
        fun onBoardList(list: List<BoardInfo>) {}
        /** Сменился ведущий (чей экран повторяют ТВ); null — ТВ показывают всю доску. */
        fun onPresenter(presenterId: String?) {}
        /** Ведущий сдвинул или масштабировал вид: [left, top, right, bottom] в координатах доски. */
        fun onViewport(rect: FloatArray) {}
    }

    var listener: Listener? = null

    /** Это устройство. Можно менять на ходу (например, включили режим ТВ) через [updateMe]. */
    var me: Member = me
        private set

    /** Ведущий: его вид повторяют все ТВ. */
    var presenterId: String? = null
        private set
    var presenterViewport: FloatArray? = null
        private set

    lateinit var board: Board
        private set
    var role = Role.SEARCHING
        private set
    var hostName: String? = null
        private set
    var members: List<Member> = listOf(this.me)
        private set

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-timer").apply { isDaemon = true }
    }
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "session-io").apply { isDaemon = true } }

    /** Поколение роли: таймеры от прошлой роли игнорируются. */
    private var gen = 0
    @Volatile private var stopped = false

    // Хозяин
    private var server: ServerSocket? = null
    private var serverPort = 0
    private var hostSince = 0L
    private val conns = LinkedHashSet<Conn>()
    private val clients = LinkedHashMap<Conn, Member>()
    private var saveScheduled = false

    // Клиент
    private var hostConn: Conn? = null
    private var lastHostId: String? = null

    // ---------------------------------------------------------------- жизненный цикл

    fun start() {
        board = store.lastBoardId?.let { store.load(it) } ?: Board.create()
        // Сокет открываем не в главном потоке (на Android сеть в нём запрещена).
        io.execute {
            try {
                beacons.start { b -> post { onBeacon(b) } }
            } catch (t: Throwable) {
                CoreLog.e("beacon start failed", t)
            }
        }
        timer.scheduleWithFixedDelay({ post { pingAll() } }, Conn.PING_INTERVAL_MS, Conn.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        enterSearching(Random.nextLong(timing.initialWaitMin, timing.initialWaitMax + 1))
    }

    fun stop() {
        if (stopped) return
        if (role == Role.HOST) saveNow()
        stopped = true
        gen++
        hostConn?.let { hostConn = null; it.close() }
        closeServer()
        beacons.stop()
        timer.shutdownNow()
        io.shutdown()
    }

    // ---------------------------------------------------------------- публичные действия

    /** Локальное изменение: применить у себя и разослать. */
    fun submit(op: Op) {
        board.apply(op)
        val payload = Codec.encodeOp(op)
        when (role) {
            Role.HOST -> { broadcast(Frame.OP, payload, except = null); markDirty() }
            Role.CLIENT -> hostConn?.send(Frame.OP, payload)
            else -> {}
        }
        listener?.onBoardChanged()
    }

    /** Новая доска для всех. Пустое имя — имя по дате. */
    fun newBoard(name: String? = null) {
        val n = name?.trim().orEmpty()
        when (role) {
            Role.CLIENT -> hostConn?.send(Frame.REQ_NEW_BOARD, Codec.encode { Codec.writeString(it, n) })
            else -> loadBoard(if (n.isEmpty()) Board.create() else Board.create(n))
        }
    }

    /** Переименовать доску (текущую или сохранённую у хозяина). */
    fun renameBoard(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        when (role) {
            Role.CLIENT -> hostConn?.send(Frame.REQ_RENAME_BOARD, Codec.encode { Codec.writeString(it, id); Codec.writeString(it, n) })
            else -> {
                doRename(id, n)
                listener?.onBoardList(boardList())
            }
        }
    }

    fun openBoard(id: String) = when (role) {
        Role.CLIENT -> hostConn?.send(Frame.REQ_OPEN_BOARD, Codec.encode { Codec.writeString(it, id) }) ?: Unit
        else -> store.load(id)?.let { loadBoard(it) } ?: Unit
    }

    fun deleteBoard(id: String) {
        if (id == board.id) return
        when (role) {
            Role.CLIENT -> hostConn?.send(Frame.REQ_DELETE_BOARD, Codec.encode { Codec.writeString(it, id) })
            else -> { store.delete(id); requestBoardList() }
        }
    }

    fun requestBoardList() {
        when (role) {
            Role.CLIENT -> hostConn?.send(Frame.REQ_LIST_BOARDS)
            else -> listener?.onBoardList(boardList())
        }
    }

    fun status() = Status(role, hostName, members)

    /** Стать ведущим (ТВ показывают мой вид) или перестать. */
    fun setPresenting(on: Boolean) {
        if (on && me.isTv) return // устройство в режиме ТВ само показывает, а не ведёт
        CoreLog.d("setPresenting($on) role=$role")
        when (role) {
            Role.CLIENT -> hostConn?.send(Frame.REQ_PRESENT, Codec.encode { it.writeBoolean(on) })
            Role.HOST -> applyPresentRequest(me.deviceId, on)
            else -> {
                // Без сети — просто запоминаем; хозяин узнает при подключении.
                val next = if (on) me.deviceId else presenterId?.takeIf { it != me.deviceId }
                setPresenterLocal(next)
            }
        }
    }

    /** Ведущий сообщает свою видимую область. */
    fun sendViewport(rect: FloatArray) {
        if (presenterId != me.deviceId || rect.size != 4) return
        presenterViewport = rect.copyOf()
        val payload = encodeViewport(me.deviceId, rect)
        when (role) {
            Role.HOST -> broadcast(Frame.VIEWPORT, payload, except = null)
            Role.CLIENT -> hostConn?.send(Frame.VIEWPORT, payload)
            else -> {}
        }
    }

    /** Изменились данные этого устройства (например, включили режим ТВ). */
    fun updateMe(m: Member) {
        require(m.deviceId == me.deviceId)
        me = m
        when (role) {
            Role.HOST -> {
                if (m.isTv) applyPresentRequest(m.deviceId, false)
                members = listOf(me) + clients.values
                broadcastMembers()
                notifyStatus()
            }
            Role.CLIENT -> hostConn?.send(Frame.MEMBER_UPDATE, Codec.encode { writeMember(it, me) })
            else -> {
                members = listOf(me)
                notifyStatus()
            }
        }
    }

    // ---------------------------------------------------------------- поиск

    private fun enterSearching(waitMs: Long) {
        gen++
        role = Role.SEARCHING
        hostName = null
        members = listOf(me)
        notifyStatus()
        schedule(waitMs) { if (role == Role.SEARCHING) becomeHost() }
    }

    private fun onBeacon(b: Beacon) {
        if (b.deviceId == me.deviceId) return
        when (role) {
            Role.SEARCHING -> connectTo(b)
            Role.HOST -> if (b.beats(me.deviceId, hostSince)) {
                // Нас двое — уступаем более «старому» хозяину и сливаем доски.
                stopHosting()
                connectTo(b)
            }
            else -> {}
        }
    }

    private fun connectTo(b: Beacon) {
        val addr = b.address ?: return
        CoreLog.d("connecting to ${b.name} at $addr:${b.port}")
        gen++
        role = Role.CONNECTING
        hostName = b.name
        notifyStatus()
        val g = gen
        io.execute {
            try {
                val socket = Conn.connect(addr, b.port)
                post {
                    if (gen == g && role == Role.CONNECTING) onConnected(socket, b)
                    else runCatching { socket.close() }
                }
            } catch (t: Exception) {
                CoreLog.e("connect to ${b.name} failed", t)
                // Ждём дольше интервала маяка: если хозяин жив, следующий маяк даст новую попытку.
                post { if (gen == g) enterSearching(timing.retryWait) }
            }
        }
    }

    /** Были ведущим до переподключения — восстановим роль у нового хозяина. */
    private var wasPresenter = false

    private fun onConnected(socket: Socket, b: Beacon) {
        wasPresenter = presenterId == me.deviceId
        val c = Conn(socket, { conn, t, p -> post { onFromHost(conn, t, p) } }, { conn -> post { onHostLost(conn) } })
        hostConn = c
        lastHostId = b.deviceId
        c.start()
        val hello = Codec.encode { out ->
            writeMember(out, me)
            Codec.writeBoard(out, board)
        }
        c.send(Frame.HELLO, hello)
        schedule(8000) { if (role == Role.CONNECTING && hostConn === c) c.close() }
    }

    // ---------------------------------------------------------------- клиент

    private fun onFromHost(c: Conn, type: Int, payload: ByteArray) {
        if (c !== hostConn) return
        when (type) {
            Frame.WELCOME -> {
                Codec.input(payload).use { inp ->
                    replaceBoard(Codec.readBoard(inp))
                    members = readMembers(inp)
                }
                gen++
                role = Role.CLIENT
                hostName = members.firstOrNull()?.name ?: hostName
                notifyStatus()
            }
            Frame.OP -> if (board.apply(Codec.decodeOp(payload))) listener?.onBoardChanged()
            Frame.MEMBERS -> {
                members = Codec.input(payload).use { readMembers(it) }
                notifyStatus()
            }
            Frame.BOARD_LOAD -> replaceBoard(Codec.decodeBoard(payload))
            Frame.BOARD_RENAME -> {
                val (id, name) = Codec.input(payload).use { Codec.readString(it) to Codec.readString(it) }
                if (id == board.id) {
                    board.name = name
                    listener?.onBoardChanged()
                }
            }
            Frame.BOARD_LIST -> listener?.onBoardList(Codec.input(payload).use { readBoardList(it) })
            Frame.PRESENTER -> {
                val id = Codec.input(payload).use { Codec.readString(it) }.ifEmpty { null }
                setPresenterLocal(id)
                // Хозяин (например, новый после миграции) мог не знать, что мы ведущий.
                if (id == null && wasPresenter && !me.isTv) setPresenting(true)
                wasPresenter = false
            }
            Frame.VIEWPORT -> {
                val (id, rect) = decodeViewport(payload)
                if (id == presenterId) {
                    presenterViewport = rect
                    listener?.onViewport(rect)
                }
            }
        }
    }

    private fun onHostLost(c: Conn) {
        if (c !== hostConn) return
        hostConn = null
        // Новым хозяином станет следующий по порядку подключения участник.
        val candidates = members.filter { it.deviceId != lastHostId }
        val rank = candidates.indexOfFirst { it.deviceId == me.deviceId }.let { if (it < 0) candidates.size else it }
        enterSearching(timing.failoverBase + rank * timing.failoverStep)
    }

    private fun replaceBoard(b: Board) {
        val same = this::board.isInitialized && b.id == board.id
        board = b
        listener?.onBoardReplaced(same)
    }

    // ---------------------------------------------------------------- хозяин

    private fun becomeHost() {
        CoreLog.d("becoming host")
        gen++
        role = Role.HOST
        hostSince = System.currentTimeMillis()
        hostName = me.name
        members = listOf(me)
        notifyStatus()
        if (presenterId == lastHostId && presenterId != null) setPresenterLocal(null)
        // Ведущий не вернулся после смены хозяина — ТВ снова показывают всю доску.
        schedule(10_000) {
            val p = presenterId
            if (role == Role.HOST && p != null && members.none { it.deviceId == p }) applyPresentRequest(p, false)
        }
        val g = gen
        io.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(0))
                post {
                    if (gen != g || role != Role.HOST) { runCatching { ss.close() }; return@post }
                    server = ss
                    serverPort = ss.localPort
                    startAcceptLoop(ss)
                    sendBeacon()
                }
            } catch (_: Exception) {
                post { if (gen == g) enterSearching(1000) }
            }
        }
        repeating(timing.beaconInterval) { sendBeacon() }
        saveNow()
    }

    private fun startAcceptLoop(ss: ServerSocket) {
        Thread({
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    post { onAccepted(s) }
                } catch (_: Exception) {
                    break
                }
            }
        }, "accept").apply { isDaemon = true }.start()
    }

    private fun sendBeacon() {
        if (role != Role.HOST || serverPort == 0) return
        val b = Beacon(me.deviceId, me.name, serverPort, board.id, hostSince)
        io.execute { beacons.send(b) }
    }

    private fun onAccepted(s: Socket) {
        if (role != Role.HOST) { runCatching { s.close() }; return }
        val c = Conn(s, { conn, t, p -> post { onFromClient(conn, t, p) } }, { conn -> post { onClientGone(conn) } })
        conns.add(c)
        c.start()
    }

    private fun onFromClient(c: Conn, type: Int, payload: ByteArray) {
        if (role != Role.HOST || c !in conns) return
        when (type) {
            Frame.HELLO -> {
                val (member, incoming) = Codec.input(payload).use { readMember(it) to Codec.readBoard(it) }
                // То же устройство переподключилось — старое соединение больше не нужно.
                clients.entries.filter { it.value.deviceId == member.deviceId }.forEach { it.key.close(); clients.remove(it.key) }
                // Копия той же доски (например, после смены хозяина) — объединяем.
                if (incoming.id == board.id) {
                    val newlyRemoved = incoming.removed.filter { it in board.elements }
                    val added = board.mergeFrom(incoming)
                    added.forEach { broadcast(Frame.OP, Codec.encodeOp(Op.Upsert(it)), except = c) }
                    if (newlyRemoved.isNotEmpty()) broadcast(Frame.OP, Codec.encodeOp(Op.Remove(newlyRemoved)), except = c)
                    if (added.isNotEmpty() || newlyRemoved.isNotEmpty()) {
                        listener?.onBoardChanged()
                        markDirty()
                    }
                }
                clients[c] = member
                members = listOf(me) + clients.values
                c.send(Frame.WELCOME, Codec.encode { out ->
                    Codec.writeBoard(out, board)
                    writeMembers(out, members)
                })
                broadcastMembers()
                notifyStatus()
                c.send(Frame.PRESENTER, Codec.encode { Codec.writeString(it, presenterId ?: "") })
                val vp = presenterViewport
                if (presenterId != null && vp != null) c.send(Frame.VIEWPORT, encodeViewport(presenterId!!, vp))
            }
            Frame.OP -> {
                if (c !in clients) return
                if (board.apply(Codec.decodeOp(payload))) {
                    listener?.onBoardChanged()
                    markDirty()
                }
                broadcast(Frame.OP, payload, except = c)
            }
            Frame.REQ_NEW_BOARD -> newBoard(if (payload.isEmpty()) null else Codec.input(payload).use { Codec.readString(it) })
            Frame.REQ_RENAME_BOARD -> {
                val (id, name) = Codec.input(payload).use { Codec.readString(it) to Codec.readString(it) }
                if (name.isNotBlank()) doRename(id, name.trim())
                c.send(Frame.BOARD_LIST, Codec.encode { writeBoardList(it, boardList()) })
            }
            Frame.REQ_OPEN_BOARD -> openBoard(Codec.input(payload).use { Codec.readString(it) })
            Frame.REQ_LIST_BOARDS -> c.send(Frame.BOARD_LIST, Codec.encode { writeBoardList(it, boardList()) })
            Frame.REQ_PRESENT -> {
                val m = clients[c] ?: return CoreLog.d("REQ_PRESENT from unknown conn")
                CoreLog.d("REQ_PRESENT from ${m.name}")
                applyPresentRequest(m.deviceId, Codec.input(payload).use { it.readBoolean() })
            }
            Frame.VIEWPORT -> {
                val m = clients[c] ?: return
                val (id, rect) = decodeViewport(payload)
                if (id != m.deviceId || id != presenterId) return
                presenterViewport = rect
                broadcast(Frame.VIEWPORT, payload, except = c)
                listener?.onViewport(rect)
            }
            Frame.MEMBER_UPDATE -> {
                val old = clients[c] ?: return
                val m = Codec.input(payload).use { readMember(it) }
                if (m.deviceId != old.deviceId) return
                clients[c] = m
                members = listOf(me) + clients.values
                broadcastMembers()
                notifyStatus()
                if (m.isTv) applyPresentRequest(m.deviceId, false)
            }
            Frame.REQ_DELETE_BOARD -> {
                val id = Codec.input(payload).use { Codec.readString(it) }
                if (id != board.id) store.delete(id)
                c.send(Frame.BOARD_LIST, Codec.encode { writeBoardList(it, boardList()) })
            }
        }
    }

    private fun onClientGone(c: Conn) {
        conns.remove(c)
        val m = clients.remove(c)
        if (m != null && role == Role.HOST) {
            members = listOf(me) + clients.values
            broadcastMembers()
            notifyStatus()
            // Ушёл ведущий (и не переподключился тем же устройством) — ТВ возвращаются к полной доске.
            if (m.deviceId == presenterId && clients.values.none { it.deviceId == m.deviceId }) {
                applyPresentRequest(m.deviceId, false)
            }
        }
    }

    /** Хозяин: запрос «стать ведущим / перестать» от устройства [deviceId]. */
    private fun applyPresentRequest(deviceId: String, on: Boolean) {
        if (on && members.any { it.deviceId == deviceId && it.isTv }) return
        val next = when {
            on -> deviceId
            presenterId == deviceId -> null
            else -> return
        }
        if (next == presenterId) return
        setPresenterLocal(next)
        if (role == Role.HOST) broadcast(Frame.PRESENTER, Codec.encode { Codec.writeString(it, next ?: "") }, except = null)
    }

    private fun setPresenterLocal(id: String?) {
        CoreLog.d("presenter: $presenterId -> $id (role $role)")
        if (id == presenterId) return
        presenterId = id
        presenterViewport = null
        listener?.onPresenter(id)
    }

    /** Хозяин (или устройство без сети) переключает доску у всех. */
    private fun loadBoard(b: Board) {
        if (role == Role.HOST) saveNow()
        replaceBoard(b)
        if (role == Role.HOST) {
            broadcast(Frame.BOARD_LOAD, Codec.encodeBoard(b), except = null)
            saveNow()
        }
    }

    /** Все доски хозяина, свежие сверху (текущая — с актуальными данными). */
    private fun boardList(): List<BoardInfo> {
        val saved = store.list().filter { it.id != board.id }
        return (saved + board.info()).sortedByDescending { it.updatedAt }
    }

    private fun doRename(id: String, name: String) {
        if (id == board.id) {
            board.name = name
            listener?.onBoardChanged()
            if (role == Role.HOST) {
                broadcast(Frame.BOARD_RENAME, Codec.encode { Codec.writeString(it, id); Codec.writeString(it, name) }, except = null)
                saveNow()
            }
        } else {
            val b = store.load(id) ?: return
            b.name = name
            store.save(id, Codec.encodeBoard(b))
        }
    }

    private fun stopHosting() {
        saveNow()
        gen++
        closeServer()
        clients.clear()
        conns.toList().forEach { it.close() }
        conns.clear()
    }

    private fun closeServer() {
        server?.let { s -> io.execute { runCatching { s.close() } } }
        server = null
        serverPort = 0
    }

    private fun broadcast(type: Int, payload: ByteArray, except: Conn?) {
        for (c in clients.keys) if (c !== except) c.send(type, payload)
    }

    private fun broadcastMembers() {
        val p = Codec.encode { writeMembers(it, members) }
        broadcast(Frame.MEMBERS, p, except = null)
    }

    private fun markDirty() {
        if (role != Role.HOST || saveScheduled) return
        saveScheduled = true
        timer.schedule({ post { saveScheduled = false; if (role == Role.HOST) saveNow() } }, timing.saveDelay, TimeUnit.MILLISECONDS)
    }

    private fun saveNow() {
        val b = board
        val bytes = Codec.encodeBoard(b)
        val info = b.info()
        runCatching {
            io.execute {
                runCatching {
                    store.save(info.id, bytes)
                    store.lastBoardId = info.id
                }
            }
        }
    }

    private fun pingAll() {
        when (role) {
            Role.HOST -> conns.forEach { it.send(Frame.PING) }
            else -> hostConn?.send(Frame.PING)
        }
    }

    // ---------------------------------------------------------------- утилиты

    private fun notifyStatus() = listener?.onStatus(status())

    private fun post(block: () -> Unit) {
        if (stopped) return
        try {
            exec.execute { if (!stopped) block() }
        } catch (_: Exception) {
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val g = gen
        runCatching {
            timer.schedule({ post { if (gen == g) block() } }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun repeating(intervalMs: Long, block: () -> Unit) {
        val g = gen
        lateinit var task: java.util.concurrent.ScheduledFuture<*>
        task = timer.scheduleWithFixedDelay({
            post { if (gen == g) block() else task.cancel(false) }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        fun writeMember(out: DataOutputStream, m: Member) {
            Codec.writeString(out, m.deviceId); Codec.writeString(out, m.name); out.writeBoolean(m.isTv)
        }

        fun readMember(inp: DataInputStream) = Member(Codec.readString(inp), Codec.readString(inp), inp.readBoolean())

        fun writeMembers(out: DataOutputStream, list: List<Member>) {
            out.writeInt(list.size); list.forEach { writeMember(out, it) }
        }

        fun readMembers(inp: DataInputStream): List<Member> = List(inp.readInt()) { readMember(inp) }

        fun writeBoardList(out: DataOutputStream, list: List<BoardInfo>) {
            out.writeInt(list.size)
            for (b in list) {
                Codec.writeString(out, b.id); Codec.writeString(out, b.name)
                out.writeLong(b.createdAt); out.writeLong(b.updatedAt); out.writeInt(b.elementCount)
            }
        }

        fun encodeViewport(id: String, r: FloatArray): ByteArray = Codec.encode { out ->
            Codec.writeString(out, id)
            for (i in 0 until 4) out.writeFloat(r[i])
        }

        fun decodeViewport(payload: ByteArray): Pair<String, FloatArray> = Codec.input(payload).use { inp ->
            Codec.readString(inp) to FloatArray(4) { inp.readFloat() }
        }

        fun readBoardList(inp: DataInputStream): List<BoardInfo> = List(inp.readInt()) {
            BoardInfo(Codec.readString(inp), Codec.readString(inp), inp.readLong(), inp.readLong(), inp.readInt())
        }
    }
}
