import Tree.Mode.entries
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

sealed interface Object {
    val prefix: String

    fun toByteArray(): ByteArray
    fun toDiskFormat(): ByteArray
    fun getSHA(): String = toDiskFormat().getSHA1()

    fun write(): String {
        val contents = toDiskFormat()
        val sha = contents.getSHA1()
        val compressed = contents.zlibEncode()
        File(".git/objects/${sha.substring(0..1)}").mkdir()
        File(".git/objects/${sha.substring(0..1)}/${sha.substring(2)}").writeBytes(compressed)
        return sha
    }

    companion object {
        fun fromSHA(sha: String): Object {
            val dirName = sha.take(2)
            val fname = sha.drop(2)
            val file = File(".git/objects/${dirName}/${fname}")
            return fromUncompressedBytes(file.readBytes().zlibDecode())
        }

        fun fromUncompressedBytes(bytes: ByteArray): Object {
            val typeIndex = bytes.indexOf(' '.code.toByte())
            val type = bytes.sliceArray(0..<typeIndex).toString(Charsets.ISO_8859_1)
            val sizeIdx = bytes.indexOf(0)
            val size = bytes.sliceArray(typeIndex + 1..<sizeIdx).toString(Charsets.ISO_8859_1)
            val contents = bytes.sliceArray(sizeIdx + 1..<bytes.size)
            return when (type) {
                "blob" -> parseBlob(contents)
                "tree" -> parseTree(contents)
                "commit" -> parseCommit(contents)
                else -> error("unknown object of type $type")
            }
        }

        private fun parseBlob(contents: ByteArray) = Blob(contents)

        @OptIn(ExperimentalStdlibApi::class)
        private fun parseTree(contents: ByteArray): Tree {
            val entries = mutableListOf<Tree.Entry>()
            var i = 0
            while (i < contents.size) {
                val modeIdx = contents.indexOf(' '.code.toByte(), i)
                val mode = contents.sliceArray(i..<modeIdx).toString(Charsets.ISO_8859_1)
                i = modeIdx + 1

                val nameIdx = contents.indexOf(0, i)
                val name = contents.sliceArray(i..<nameIdx).toString(Charsets.ISO_8859_1)
                i = nameIdx + 1

                val sha = contents.sliceArray(i..<i + 20).toHexString()
                i += 20
                entries.add(Tree.Entry(Tree.Mode.from(mode.toInt()), name, sha))
            }
            return Tree(entries)
        }

        private fun parseCommit(bytes: ByteArray): Object {
            val commit = bytes.toString(Charsets.ISO_8859_1)
            val (header, message) = commit.split("\n\n", limit = 2)
            return Commit(header.split("\n"), message.trim())
        }
    }
}

data class Blob(val contents: ByteArray) : Object {
    override val prefix: String = "blob"

    override fun toByteArray(): ByteArray = contents

    override fun toDiskFormat(): ByteArray {
        val bytes = toByteArray()
        return "$prefix ${bytes.size}".toByteArray() + 0 + bytes
    }

    companion object {
        fun fromFile(file: File): Object {
            val contents = file.readBytes()
            return Blob(contents)
        }


    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Blob

        if (!contents.contentEquals(other.contents)) return false
        if (prefix != other.prefix) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contents.contentHashCode()
        result = 31 * result + prefix.hashCode()
        return result
    }
}

@OptIn(ExperimentalStdlibApi::class)
data class Tree(val entries: List<Entry>) : Object {
    enum class Mode(val value: Int) {
        RegularFile(100644),
        ExecutableFile(100755),
        SymbolicLink(120000),
        Directory(40000);

        companion object {
            fun from(value: Int): Mode =
                entries.first { it.value == value }
        }
    }

    data class Entry(
        val mode: Mode,
        val name: String,
        val sha: String,
    ) {
        fun toByteArray(): ByteArray {
            val buffer = Buffer()
            buffer.writeString(mode.value.toString())
            buffer.writeByte(' '.code.toByte())
            buffer.writeString(name)
            buffer.writeByte(0)
            buffer.write(sha.hexToByteArray())
            return buffer.readByteArray()
        }
    }

    fun getNames(): List<String> =
        entries.map { it.name }

    override val prefix: String = "tree"

    override fun toByteArray(): ByteArray {
        val buffer = Buffer()
        entries.forEach { buffer.write(it.toByteArray()) }
        return buffer.readByteArray()
    }

    override fun toDiskFormat(): ByteArray {
        val bytes = toByteArray()
        return "tree ${bytes.size}".toByteArray() + 0 + bytes
    }

    override fun write(): String {
        entries.forEach { entry ->
            if (entry.mode == Mode.Directory) fromDir(File(entry.name)).write()
        }
        return super.write()
    }

    companion object {
        val gitignored = setOf(".git")

        fun fromDir(dir: File): Tree {
            assert(dir.isDirectory())
            val entries = mutableListOf<Entry>()
            val files = dir.listFiles() ?: arrayOf<File>()
            for (file in files) {
                if (file.isDirectory() && file.name in gitignored) continue
                if (file.isDirectory()) {
                    val tree = fromDir(file)
                    val sha = tree.getSHA()
                    entries.add(Entry(Mode.Directory, file.name, sha))
                } else {
                    val blob = Blob.fromFile(file)
                    val sha = blob.getSHA()
                    val mode = when {
                        file.canExecute() -> Mode.ExecutableFile
                        Files.isSymbolicLink(file.toPath()) -> Mode.SymbolicLink
                        else -> Mode.RegularFile
                    }
                    entries.add(Entry(mode, file.name, sha))
                }
            }
            entries.sortBy(Entry::name)
            return Tree(entries)
        }
    }
}

data class Commit(val header: List<String>, val message: String) : Object {
    override val prefix = "commit"

    override fun toByteArray(): ByteArray {
        val commit = buildString {
            append(header.joinToString("\n"))
            append("\n\n")
            append(message)
            append("\n")
        }
        return commit.toByteArray()
    }

    override fun toDiskFormat(): ByteArray {
        val bytes = toByteArray()
        return "$prefix ${bytes.size}".toByteArray() + 0 + bytes
    }
}


fun ByteArray.zlibEncode(): ByteArray {
    val output = ByteArrayOutputStream()
    DeflaterOutputStream(output).use { it.write(this) }
    return output.toByteArray()
}

fun ByteArray.zlibDecode() =
    InflaterInputStream(this.inputStream()).use { it.readBytes() }

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.getSHA1(): String =
    MessageDigest.getInstance("SHA-1").digest(this).toHexString()

fun ByteArray.indexOf(element: Byte, startIdx: Int): Int {
    for (index in startIdx..<size) {
        if (element == this[index]) {
            return index
        }
    }
    return -1
}
