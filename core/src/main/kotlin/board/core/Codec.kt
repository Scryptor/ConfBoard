package board.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Бинарная сериализация элементов, операций и досок (сеть + файлы). */
object Codec {
    private const val BOARD_MAGIC = 0x4F42524D // "OBRM"
    private const val BOARD_VERSION = 1

    private const val E_STROKE = 1
    private const val E_SHAPE = 2
    private const val E_TEXT = 3

    private const val OP_UPSERT = 1
    private const val OP_APPEND = 2
    private const val OP_REMOVE = 3

    fun writeString(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    fun readString(inp: DataInputStream): String {
        val n = inp.readInt()
        if (n < 0 || n > 16 * 1024 * 1024) throw IOException("bad string length $n")
        val b = ByteArray(n)
        inp.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    fun writeFloats(out: DataOutputStream, a: FloatArray, len: Int = a.size) {
        out.writeInt(len)
        for (i in 0 until len) out.writeFloat(a[i])
    }

    fun readFloats(inp: DataInputStream): FloatArray {
        val n = inp.readInt()
        if (n < 0 || n > 64 * 1024 * 1024) throw IOException("bad float count $n")
        return FloatArray(n) { inp.readFloat() }
    }

    fun writeElement(out: DataOutputStream, e: Element) {
        when (e) {
            is Stroke -> {
                out.writeByte(E_STROKE)
                writeString(out, e.id); out.writeInt(e.color); out.writeFloat(e.width)
                writeFloats(out, e.data, e.size)
            }
            is Shape -> {
                out.writeByte(E_SHAPE)
                writeString(out, e.id); out.writeInt(e.color); out.writeFloat(e.width)
                out.writeByte(e.kind.ordinal)
                out.writeFloat(e.x1); out.writeFloat(e.y1); out.writeFloat(e.x2); out.writeFloat(e.y2)
            }
            is TextEl -> {
                out.writeByte(E_TEXT)
                writeString(out, e.id); out.writeInt(e.color); out.writeFloat(e.width)
                out.writeFloat(e.x); out.writeFloat(e.y)
                writeString(out, e.text)
            }
        }
    }

    fun readElement(inp: DataInputStream): Element {
        val type = inp.readUnsignedByte()
        val id = readString(inp)
        val color = inp.readInt()
        val width = inp.readFloat()
        return when (type) {
            E_STROKE -> Stroke(id, color, width, readFloats(inp))
            E_SHAPE -> {
                val kind = ShapeKind.entries[inp.readUnsignedByte()]
                Shape(id, color, width, kind, inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat())
            }
            E_TEXT -> TextEl(id, color, width, inp.readFloat(), inp.readFloat(), readString(inp))
            else -> throw IOException("unknown element type $type")
        }
    }

    fun writeOp(out: DataOutputStream, op: Op) {
        when (op) {
            is Op.Upsert -> { out.writeByte(OP_UPSERT); writeElement(out, op.element) }
            is Op.Append -> { out.writeByte(OP_APPEND); writeString(out, op.id); writeFloats(out, op.xy) }
            is Op.Remove -> {
                out.writeByte(OP_REMOVE)
                out.writeInt(op.ids.size)
                op.ids.forEach { writeString(out, it) }
            }
        }
    }

    fun readOp(inp: DataInputStream): Op = when (val t = inp.readUnsignedByte()) {
        OP_UPSERT -> Op.Upsert(readElement(inp))
        OP_APPEND -> Op.Append(readString(inp), readFloats(inp))
        OP_REMOVE -> {
            val n = inp.readInt()
            if (n < 0 || n > 10_000_000) throw IOException("bad remove count $n")
            Op.Remove(List(n) { readString(inp) })
        }
        else -> throw IOException("unknown op $t")
    }

    fun writeBoard(out: DataOutputStream, b: Board) {
        out.writeInt(BOARD_MAGIC)
        out.writeInt(BOARD_VERSION)
        // Заголовок (читается отдельно для списка досок)
        writeString(out, b.id)
        writeString(out, b.name)
        out.writeLong(b.createdAt)
        out.writeLong(b.updatedAt)
        out.writeInt(b.elements.size)
        // Тело
        for (e in b.elements.values) writeElement(out, e)
        out.writeInt(b.removed.size)
        for (r in b.removed) writeString(out, r)
    }

    fun readBoard(inp: DataInputStream): Board {
        val info = readBoardHeader(inp)
        val b = Board(info.id, info.name, info.createdAt, info.updatedAt)
        repeat(info.elementCount) {
            val e = readElement(inp)
            b.elements[e.id] = e
        }
        val nr = inp.readInt()
        repeat(nr) { b.removed.add(readString(inp)) }
        b.updatedAt = info.updatedAt
        return b
    }

    fun readBoardHeader(inp: DataInputStream): BoardInfo {
        if (inp.readInt() != BOARD_MAGIC) throw IOException("not a board")
        val ver = inp.readInt()
        if (ver != BOARD_VERSION) throw IOException("unsupported board version $ver")
        val id = readString(inp)
        val name = readString(inp)
        val created = inp.readLong()
        val updated = inp.readLong()
        val count = inp.readInt()
        return BoardInfo(id, name, created, updated, count)
    }

    inline fun encode(block: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use(block)
        return bos.toByteArray()
    }

    fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    fun encodeBoard(b: Board): ByteArray = encode { writeBoard(it, b) }
    fun decodeBoard(bytes: ByteArray): Board = input(bytes).use { readBoard(it) }
    fun encodeOp(op: Op): ByteArray = encode { writeOp(it, op) }
    fun decodeOp(bytes: ByteArray): Op = input(bytes).use { readOp(it) }
}
