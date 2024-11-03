import ObjectType.entries
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readLine
import kotlinx.io.readString
import java.nio.file.Path
import java.util.*

data class Ref(
    val hash: String,
    val name: String,
    val capabilities: List<String>,
    val isSymRefTo: String?,
)

const val infoRefs = "info/refs?service=git-upload-pack"
const val packUrl = "git-upload-pack"

suspend fun clone(url: String, outDir: Path) {
    val infoRefs = Url("$url/$infoRefs")
    val refs = discoverRefs(infoRefs)
    for (ref in refs) {
        val packUrl = Url("$url/${packUrl}")
        if (ref.isSymRefTo != null) {
            val pack = getPack(packUrl, ref)
        }
    }
}

suspend fun getPack(url: Url, ref: Ref) {
    val http = HttpClient(CIO)
    val response = http.post(url) {
        headers {
            append("Content-Type", "application/x-git-upload-pack-request")
        }
        setBody("0032want ${ref.hash}\n00000008done")
    }
    require(response.status == HttpStatusCode.OK) { "got status: ${response.status}, expected: 200 OK" }
    require(response.contentType().toString() == "application/x-git-upload-pack-result")
    val buffer = Buffer().apply { write(response.body()) }
    validatePack(buffer)
    val numObjects = buffer.readInt()
    println("The number of objects contained in the pack file is: $numObjects")
    repeat(numObjects) {
        val obj = readObject(buffer)
        println("${obj.getSHA()} ${obj.prefix}")
        println(obj)
    }
}

enum class ObjectType(val value: Int) {
    Commit(1),
    Tree(2),
    Blob(3),
    Tag(4),
    OfsDelta(6),
    RefDelta(7);

    companion object {
        fun from(value: Int): ObjectType =
            entries.first { it.value == value }
    }
}

val supportedTypes = setOf(ObjectType.Tree, ObjectType.Commit, ObjectType.Blob)

fun readObject(buffer: Buffer): Object {
    val byte = buffer.readByte().toInt()

    val type = ObjectType.from((byte ushr 4) and 0b0111)
    require(type in supportedTypes) { "got object of type $type, expected one of $supportedTypes" }

    var uncompressedLength = byte and 0b1111
    if (byte and 0b10000000 != 0) {
        var shiftBy = 4
        while (true) {
            val next = buffer.readByte().toInt()
            uncompressedLength = ((next and 0b1111111) shl shiftBy) or uncompressedLength
            shiftBy += 7
            if (next and 0b10000000 == 0) break
        }
    }

    val copy = buffer.copy()
    val obj = copy.readByteArray().zlibDecode()
    require(obj.size == uncompressedLength) {
        "got $type object of size: ${obj.size}, expected: $uncompressedLength. the object is:\n\n\n ${
            obj.toString(
                Charsets.ISO_8859_1
            )
        }"
    }

    val compressedSize = obj.zlibEncode().size
    buffer.discard(compressedSize.toLong())

    return Object.fromUncompressedBytes(
        "${
            type.toString().lowercase(Locale.getDefault())
        } ${obj.size}".toByteArray() + 0 + obj
    )
}

fun validatePack(buffer: Buffer) {
    require(buffer.readLine() == "0008NAK")
    require(buffer.readString(4) == "PACK")
    val version = buffer.readInt()
    require(version in 2..3)
}

private suspend fun discoverRefs(url: Url): List<Ref> {
    val http = HttpClient(CIO)
    val response = http.get(url)
    val body = Buffer().apply { write(response.body()) }
    require(response.status == HttpStatusCode.OK)
    require(response.contentType().toString() == "application/x-git-upload-pack-advertisement")
    validateHeader(body)
    return parseRefs(body)
}

@OptIn(ExperimentalStdlibApi::class)
private fun parseRefs(buffer: Buffer): List<Ref> {
    val refs = mutableListOf<Ref>()
    while (true) {
        val payloadLen = buffer.readByteArray(4).toString(Charsets.ISO_8859_1).hexToInt()
        if (payloadLen == 0) break
        val line = buffer.readByteArray(payloadLen - 4)
        refs.add(parseRefLine(line))
    }
    return refs
}

private fun parseRefLine(line: ByteArray): Ref {
    val spaceIdx = line.indexOf(' '.code.toByte())
    val hash = line.sliceArray(0..<spaceIdx).toString(Charsets.ISO_8859_1)

    val nulIdx = line.indexOf(0).takeIf { it != -1 } ?: line.size
    val name = line.sliceArray(spaceIdx + 1..<nulIdx).toString(Charsets.ISO_8859_1)

    val capabilities = line.sliceArray(nulIdx + 1..<line.size).toString(Charsets.ISO_8859_1).split(" ")
    var isSymRefTo: String? = null
    for (capability in capabilities) {
        if (capability.startsWith("symref=")) isSymRefTo = capability.split(":")[1]
    }

    return Ref(hash, name, capabilities, isSymRefTo)
}

private fun validateHeader(buffer: Buffer) {
    val header = buffer.readByteArray(4).toString(Charsets.ISO_8859_1)
    require(header.all { it.isDigit() || it in 'a'..'f' }) { "header is: ${header}, expected each byte to be within 0..9 or a..f" }
    val firstPktLine = buffer.readLine()
    require(firstPktLine == "# service=git-upload-pack") { "first pkt line is $firstPktLine, expected: '# service=git-upload-pack'" }
    val flush = buffer.readByteArray(4).toString(Charsets.ISO_8859_1)
    require(flush == "0000") { "got $flush, expected flush packet: 0000" }
}
