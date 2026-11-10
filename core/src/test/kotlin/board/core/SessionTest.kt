package board.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Несколько сессий в одном процессе: реальный TCP по localhost, маяки — через шину в памяти. */
class SessionTest {
    private val bus = LocalBeaconBus()
    private val fast = Session.Timing(
        initialWaitMin = 300, initialWaitMax = 600,
        failoverBase = 500, failoverStep = 600,
        beaconInterval = 150, saveDelay = 100,
    )
    private val nodes = mutableListOf<Node>()

    inner class Node(name: String, val store: BoardStore = MemoryBoardStore(), timing: Session.Timing = fast) {
        val exec = Executors.newSingleThreadExecutor()
        val session = Session(Member(name, name, false), store, exec, bus.transport(), timing)

        fun <T> call(block: Session.() -> T): T = exec.submit(Callable { session.block() }).get()
        fun start() = apply { call { start() }; nodes.add(this) }
        fun stop() {
            call { stop() }
            exec.shutdown()
            nodes.remove(this)
        }
        val role get() = call { role }
        fun ids() = call { board.elements.keys.toSet() }
        fun boardId() = call { board.id }
    }

    @AfterTest
    fun tearDown() {
        nodes.toList().forEach { runCatching { it.stop() } }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(30)
        }
        fail("не дождались: $what")
    }

    private fun stroke(id: String) = Stroke(id, 0xFF000000.toInt(), 3f, floatArrayOf(0f, 0f, 10f, 10f))

    @Test
    fun clientsFindHostAndSyncLive() {
        val a = Node("A").start()
        waitUntil("A стал хозяином") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        val c = Node("C").start()
        waitUntil("B и C подключились") {
            b.role == Session.Role.CLIENT && c.role == Session.Role.CLIENT && a.call { members.size } == 3
        }
        assertEquals(a.boardId(), b.boardId())

        // Живой штрих: начало, затем дописывание точек
        b.call { submit(Op.Upsert(Stroke("live", 0, 3f, floatArrayOf(0f, 0f)))) }
        waitUntil("штрих появился у A и C") { "live" in a.ids() && "live" in c.ids() }
        b.call { submit(Op.Append("live", floatArrayOf(5f, 5f, 10f, 10f))) }
        waitUntil("точки дошли до C") { c.call { (board["live"] as? Stroke)?.pointCount } == 3 }

        // Хозяин тоже рисует, клиенты видят; удаление доходит до всех
        a.call { submit(Op.Upsert(stroke("fromA"))) }
        waitUntil("штрих хозяина у B и C") { "fromA" in b.ids() && "fromA" in c.ids() }
        c.call { submit(Op.Remove(listOf("live"))) }
        waitUntil("удаление у всех") { "live" !in a.ids() && "live" !in b.ids() }
    }

    @Test
    fun hostLeavesAndBoardSurvives() {
        val a = Node("A").start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        waitUntil("B подключился") { b.role == Session.Role.CLIENT }
        val c = Node("C").start()
        waitUntil("C подключился") { c.role == Session.Role.CLIENT && a.call { members.size } == 3 }
        b.call { submit(Op.Upsert(stroke("s1"))) }
        waitUntil("s1 у всех") { "s1" in c.ids() }
        val boardId = a.boardId()

        a.stop()
        // B подключился раньше C — он и должен стать новым хозяином.
        waitUntil("B стал хозяином, C переподключился", 10_000) {
            b.role == Session.Role.HOST && c.role == Session.Role.CLIENT && b.call { members.size } == 2
        }
        assertEquals(boardId, b.boardId())
        assertEquals(boardId, c.boardId())
        assertTrue("s1" in c.ids())

        c.call { submit(Op.Upsert(stroke("s2"))) }
        waitUntil("s2 у нового хозяина") { "s2" in b.ids() }
    }

    @Test
    fun twoSimultaneousHostsMerge() {
        // Оба «не слышат» друг друга при старте и становятся хозяевами.
        val slowBus = Session.Timing(initialWaitMin = 50, initialWaitMax = 60, beaconInterval = 200, saveDelay = 100)
        val a = Node("A", timing = slowBus)
        val b = Node("B", timing = slowBus)
        a.start(); b.start()
        waitUntil("остался один хозяин", 10_000) {
            val roles = listOf(a.role, b.role)
            roles.count { it == Session.Role.HOST } == 1 && roles.count { it == Session.Role.CLIENT } == 1
        }
        assertEquals(a.boardId(), b.boardId())
        a.call { submit(Op.Upsert(stroke("x"))) }
        waitUntil("x у B") { "x" in b.ids() }
    }

    @Test
    fun hostSavesAndRestoresBoard() {
        val store = MemoryBoardStore()
        val a = Node("A", store).start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        waitUntil("B подключился") { b.role == Session.Role.CLIENT }
        b.call { submit(Op.Upsert(stroke("keep"))) }
        waitUntil("хозяин сохранил доску") { store.lastBoardId?.let { store.load(it) }?.get("keep") != null }
        val boardId = a.boardId()
        b.stop(); a.stop()

        val a2 = Node("A", store).start()
        waitUntil("A снова хозяин") { a2.role == Session.Role.HOST }
        assertEquals(boardId, a2.boardId())
        assertTrue("keep" in a2.ids())
    }

    @Test
    fun clientCreatesAndSwitchesBoards() {
        val a = Node("A").start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        waitUntil("B подключился") { b.role == Session.Role.CLIENT }
        a.call { submit(Op.Upsert(stroke("old"))) }
        val first = a.boardId()

        b.call { newBoard() }
        waitUntil("все на новой доске") { a.boardId() != first && b.boardId() == a.boardId() }
        assertTrue(b.ids().isEmpty())

        var list: List<BoardInfo> = emptyList()
        b.call {
            listener = object : Session.Listener {
                override fun onBoardList(list2: List<BoardInfo>) { list = list2 }
            }
            requestBoardList()
        }
        waitUntil("список досок пришёл") { list.size == 2 }
        b.call { openBoard(first) }
        waitUntil("все вернулись на первую доску") { a.boardId() == first && b.boardId() == first }
        assertTrue("old" in b.ids())
    }

    @Test
    fun presenterDrivesTvView() {
        val a = Node("A").start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        val tv = Node("TV").start()
        waitUntil("все подключились") { a.call { members.size } == 3 && tv.role == Session.Role.CLIENT && b.role == Session.Role.CLIENT }

        // ТВ включает режим ТВ — остальные видят это в списке участников
        tv.call { updateMe(me.copy(isTv = true)) }
        waitUntil("B знает, что TV — телевизор") { b.call { members.any { it.deviceId == "TV" && it.isTv } } }

        // B становится ведущим и двигает вид
        b.call { setPresenting(true) }
        waitUntil("все знают ведущего B") { listOf(a, b, tv).all { n -> n.call { presenterId } == "B" } }
        b.call { sendViewport(floatArrayOf(10f, 20f, 110f, 220f)) }
        waitUntil("вид дошёл до ТВ") { tv.call { presenterViewport }?.toList() == listOf(10f, 20f, 110f, 220f) }

        // Не-ведущий не может подменить вид
        a.call { sendViewport(floatArrayOf(0f, 0f, 1f, 1f)) }
        Thread.sleep(200)
        assertEquals(listOf(10f, 20f, 110f, 220f), tv.call { presenterViewport }?.toList())

        // A перехватывает роль ведущего
        a.call { setPresenting(true) }
        waitUntil("ведущий теперь A") { tv.call { presenterId } == "A" && b.call { presenterId } == "A" }

        // Новый участник сразу получает ведущего и его вид
        a.call { sendViewport(floatArrayOf(1f, 2f, 3f, 4f)) }
        val late = Node("Late").start()
        waitUntil("опоздавший знает ведущего и вид") {
            late.call { presenterId } == "A" && late.call { presenterViewport }?.toList() == listOf(1f, 2f, 3f, 4f)
        }

        // A отпускает — ТВ снова показывают всю доску
        a.call { setPresenting(false) }
        waitUntil("ведущего нет") { tv.call { presenterId } == null }
    }

    @Test
    fun presenterLeavingResetsTv() {
        val a = Node("A").start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        val tv = Node("TV").start()
        waitUntil("все подключились") { a.call { members.size } == 3 }
        b.call { setPresenting(true) }
        waitUntil("ТВ знает ведущего") { tv.call { presenterId } == "B" }
        b.stop()
        waitUntil("после ухода ведущего ТВ показывает всю доску") { tv.call { presenterId } == null }
    }

    @Test
    fun deviceSwitchedToTvStopsPresenting() {
        val a = Node("A").start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        waitUntil("B подключился") { b.role == Session.Role.CLIENT }
        b.call { setPresenting(true) }
        waitUntil("B ведущий") { a.call { presenterId } == "B" }
        // B переключили в режим ТВ — он больше не ведущий и не может им стать
        b.call { updateMe(me.copy(isTv = true)) }
        waitUntil("ведущего нет") { a.call { presenterId } == null && b.call { presenterId } == null }
        b.call { setPresenting(true) }
        Thread.sleep(300)
        assertEquals(null, a.call { presenterId })
        // То же для хозяина
        a.call { setPresenting(true) }
        waitUntil("A ведущий") { b.call { presenterId } == "A" }
        a.call { updateMe(me.copy(isTv = true)) }
        waitUntil("хозяин в режиме ТВ — ведущего нет") { b.call { presenterId } == null }
    }

    @Test
    fun boardsNamedAndRenamed() {
        val store = MemoryBoardStore()
        val a = Node("A", store).start()
        waitUntil("A хозяин") { a.role == Session.Role.HOST }
        val b = Node("B").start()
        waitUntil("B подключился") { b.role == Session.Role.CLIENT }
        val first = a.boardId()

        b.call { newBoard("Урок математики") }
        waitUntil("новая доска с именем у всех") {
            a.call { board.name } == "Урок математики" && b.call { board.name } == "Урок математики"
        }
        val second = a.boardId()

        // Переименовать текущую — видят все, у хозяина сохраняется
        b.call { renameBoard(second, "Алгебра") }
        waitUntil("переименование дошло") { a.call { board.name } == "Алгебра" && b.call { board.name } == "Алгебра" }
        waitUntil("сохранено у хозяина") { store.load(second)?.name == "Алгебра" }

        // Переименовать сохранённую (не текущую)
        var list: List<BoardInfo> = emptyList()
        b.call {
            listener = object : Session.Listener { override fun onBoardList(list2: List<BoardInfo>) { list = list2 } }
            renameBoard(first, "Черновик")
        }
        waitUntil("список с новым именем") { list.any { it.id == first && it.name == "Черновик" } }
        assertEquals(second, list.first().id, "свежая доска — первая в списке")
    }
}
