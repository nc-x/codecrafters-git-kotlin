import Tree.Mode.entries
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

sealed class Object(
    val prefix: String,
    val size: Int,
    val contents: ByteArray,
) {
    fun write(): String {
        val contents = "$prefix $size".toByteArray() + 0 + contents
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
            val decoded = file.readBytes().zlibDecode()
            val typeIndex = decoded.indexOf(' '.code.toByte())
            val type = decoded.sliceArray(0..<typeIndex).toString(Charsets.ISO_8859_1)
            val sizeIdx = decoded.indexOf(0)
            val size = decoded.sliceArray(typeIndex + 1..<sizeIdx).toString(Charsets.ISO_8859_1)
            val contents = decoded.sliceArray(sizeIdx + 1..<decoded.size)
            return when (type) {
                "blob" -> Blob(size.toInt(), contents)
                "tree" -> Tree(size.toInt(), contents)
                else -> error("unknown object of type $type")
            }
        }
    }
}

class Blob(size: Int, contents: ByteArray) : Object("blob", size, contents) {
    companion object {
        fun fromFile(file: File): Object {
            val contents = file.readBytes()
            return Blob(contents.size, contents)
        }
    }
}

@OptIn(ExperimentalStdlibApi::class)
class Tree(size: Int, contents: ByteArray) : Object("tree", size, contents) {
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
    )

    val entries = mutableListOf<Entry>()

    init {
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
            entries.add(Entry(Mode.from(mode.toInt()), name, sha))
        }
    }

    fun getNames(): List<String> =
        entries.map { it.name }
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
