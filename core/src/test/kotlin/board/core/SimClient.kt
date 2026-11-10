package board.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Симулятор планшета для проверки с компьютера: находит доску в локальной сети,
 * подключается и «живьём» рисует демо-картинку.
 *
 *   ./gradlew :core:simClient            — нарисовать демо
 *   ./gradlew :core:simClient --args=clear — стереть всё
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "draw"
    val exec = Executors.newSingleThreadExecutor()
    val session = Session(Member("simmac" + Ids.random(4), "Mac-симулятор", false), MemoryBoardStore(), exec, UdpBeaconTransport())
    fun <T> call(block: Session.() -> T): T = exec.submit(Callable { session.block() }).get()
    session.listener = object : Session.Listener {
        override fun onStatus(status: Session.Status) {
            println("статус: ${status.role} хозяин=${status.hostName} участники=${status.members.map { it.name }}")
        }
    }
    call { start() }

    val deadline = System.currentTimeMillis() + 15_000
    while (call { role } != Session.Role.CLIENT && System.currentTimeMillis() < deadline) Thread.sleep(100)
    if (call { role } != Session.Role.CLIENT) {
        println("ОШИБКА: хозяин не найден (роль ${call { role }})")
        call { stop() }; exec.shutdown(); return
    }
    println("подключено к ${call { hostName }}, элементов на доске: ${call { board.elements.size }}")

    if (mode == "clear") {
        call { submit(Op.Remove(board.elements.keys.toList())) }
    } else {
        // Живой штрих: синусоида, дописывается по кусочку
        val id = Ids.random()
        call { submit(Op.Upsert(Stroke(id, 0xFFE53935.toInt(), 6f, floatArrayOf(0f, 200f)))) }
        for (i in 1..120) {
            val x = i * 6f
            val y = 200f + 80f * sin(i / 10.0).toFloat()
            call { submit(Op.Append(id, floatArrayOf(x, y))) }
            Thread.sleep(16)
        }
        call { submit(Op.Upsert(board[id]!!)) }
        // Растущий прямоугольник
        val rid = Ids.random()
        for (i in 1..30) {
            call { submit(Op.Upsert(Shape(rid, 0xFF1E88E5.toInt(), 4f, ShapeKind.RECT, 40f, 340f, 40f + i * 10f, 340f + i * 5f))) }
            Thread.sleep(16)
        }
        call { submit(Op.Upsert(Shape(Ids.random(), 0xFF43A047.toInt(), 4f, ShapeKind.ARROW, 420f, 420f, 640f, 330f))) }
        call { submit(Op.Upsert(Shape(Ids.random(), 0xFF8E24AA.toInt(), 4f, ShapeKind.ELLIPSE, 460f, 60f, 700f, 160f))) }
        // Текст набирается по буквам
        val tid = Ids.random()
        val text = "Привет с Mac!"
        for (n in 1..text.length) {
            call { submit(Op.Upsert(TextEl(tid, 0xFF1F1F1F.toInt(), 40f, 40f, 20f, text.substring(0, n)))) }
            Thread.sleep(60)
        }
        // Спираль-точки
        val sid = Ids.random()
        call { submit(Op.Upsert(Stroke(sid, 0xFFFB8C00.toInt(), 4f, floatArrayOf(580f, 600f)))) }
        for (i in 1..200) {
            val a = i / 8.0
            val r = i * 0.6
            call { submit(Op.Append(sid, floatArrayOf((580 + r * cos(a)).toFloat(), (600 + r * sin(a)).toFloat()))) }
            Thread.sleep(8)
        }
    }
    Thread.sleep(1500)
    println("готово, элементов на доске: ${call { board.elements.size }}")
    call { stop() }
    exec.shutdown()
}
