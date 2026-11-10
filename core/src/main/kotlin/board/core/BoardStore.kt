package board.core

import java.io.DataInputStream
import java.io.File

interface BoardStore {
    fun save(id: String, bytes: ByteArray)
    fun load(id: String): Board?
    fun list(): List<BoardInfo>
    fun delete(id: String)
    var lastBoardId: String?
}

/** Хранит каждую доску в отдельном файле. */
class FileBoardStore(private val dir: File) : BoardStore {
    init {
        dir.mkdirs()
    }

    private fun file(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() }}.board")
    private val lastFile = File(dir, "last.txt")

    @Synchronized
    override fun save(id: String, bytes: ByteArray) {
        val target = file(id)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    @Synchronized
    override fun load(id: String): Board? {
        val f = file(id)
        if (!f.exists()) return null
        return runCatching { Codec.decodeBoard(f.readBytes()) }.getOrNull()
    }

    @Synchronized
    override fun list(): List<BoardInfo> =
        (dir.listFiles { f -> f.name.endsWith(".board") } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    DataInputStream(f.inputStream().buffered()).use { Codec.readBoardHeader(it) }
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }

    @Synchronized
    override fun delete(id: String) {
        file(id).delete()
    }

    override var lastBoardId: String?
        @Synchronized get() = lastFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        @Synchronized set(v) {
            lastFile.writeText(v ?: "")
        }
}

class MemoryBoardStore : BoardStore {
    private val boards = LinkedHashMap<String, ByteArray>()
    override var lastBoardId: String? = null

    @Synchronized
    override fun save(id: String, bytes: ByteArray) {
        boards[id] = bytes
    }

    @Synchronized
    override fun load(id: String) = boards[id]?.let { Codec.decodeBoard(it) }

    @Synchronized
    override fun list() = boards.values.map { Codec.input(it).use { s -> Codec.readBoardHeader(s) } }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    override fun delete(id: String) {
        boards.remove(id)
    }
}
