import Pack.Companion.ObjectType.entries
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
import kotlinx.io.readUByte
import java.util.*

data class Pack(val buffer: Buffer) {
    private val objects = mutableMapOf<String, Object>()

    internal fun parseObjects(): MutableCollection<Object> {
        val numObjects = buffer.readInt()
        repeat(numObjects) {
            val obj = parseObject(buffer)
            objects[obj.getSHA()] = obj
        }
        return objects.values
    }

    private fun parseObject(buffer: Buffer): Object {
        val byte = buffer.readByte().toInt()

        val type = ObjectType.from((byte ushr 4) and 0b0111)
        require(type in supportedTypes) { "got object of type $type, expected one of $supportedTypes" }

        var uncompressedLength = parseUncompressedLength(byte, 4, buffer)

        if (type == ObjectType.RefDelta)
            return parseRefDelta(uncompressedLength, buffer)
        else {
            val data = uncompressData(uncompressedLength, buffer, type)
            return Object.fromUncompressedBytes(
                "${
                    type.toString().lowercase(Locale.getDefault())
                } ${data.size}".toByteArray() + 0 + data
            )
        }
    }

    private fun parseUncompressedLength(initialByte: Int, initialShift: Int, buffer: Buffer): Int {
        var uncompressedLength = initialByte and 0b1111
        if (initialByte and 0b10000000 != 0) {
            var shiftBy = initialShift
            while (true) {
                val next = buffer.readByte().toInt()
                uncompressedLength = ((next and 0b1111111) shl shiftBy) or uncompressedLength
                shiftBy += 7
                if (next and 0b10000000 == 0) break
            }
        }
        return uncompressedLength
    }

    private fun uncompressData(uncompressedLength: Int, buffer: Buffer, type: ObjectType): ByteArray {
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
        return obj
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun parseRefDelta(uncompressedLength: Int, buffer: Buffer): Object {
        val shaOfBaseObj = buffer.readByteArray(20).toHexString()
        val instructions = Buffer().apply { write(uncompressData(uncompressedLength, buffer, ObjectType.RefDelta)) }
        val baseObjSize = parseUncompressedLength(0b10000000, 0, instructions)
        val newObjSize = parseUncompressedLength(0b10000000, 0, instructions)
        val baseObj = objects[shaOfBaseObj] ?: error("cannot find object with sha: $shaOfBaseObj")
        val baseObjBytes = baseObj.toByteArray()
        require(baseObjBytes.size == baseObjSize) { "expected base object to have size $baseObjSize, is ${baseObjBytes.size}" }

        val output = Buffer()
        while (!instructions.exhausted()) {
            val instr = instructions.readByte().toInt()
            if (instr and 0b10000000 == 0) {
                // add new data
                val size = instr and 0b01111111
                output.write(instructions.readByteArray(size))
            } else {
                // copy data
                var offset = 0
                val hasOffset1 = (instr and 0b00000001) != 0
                if (hasOffset1) offset = instructions.readUByte().toInt()

                val hasOffset2 = (instr and 0b00000010) != 0
                if (hasOffset2) {
                    offset = offset or (instructions.readUByte().toInt() shl 8)
                }

                val hasOffset3 = (instr and 0b00000100) != 0
                if (hasOffset3) {
                    offset = offset or (instructions.readUByte().toInt() shl 16)
                }

                val hasOffset4 = (instr and 0b00001000) != 0
                if (hasOffset4) {
                    offset = offset or (instructions.readUByte().toInt() shl 24)
                }

                var size = 0
                val hasCopySize1 = (instr and 0b00010000) != 0
                if (hasCopySize1) {
                    size = instructions.readUByte().toInt()
                }

                val hasCopySize2 = (instr and 0b00100000) != 0
                if (hasCopySize2) {
                    size = size or (instructions.readUByte().toInt() shl 8)
                }

                val hasCopySize3 = (instr and 0b01000000) != 0
                if (hasCopySize3) {
                    size = size or (instructions.readUByte().toInt() shl 16)
                }

                output.write(baseObjBytes.sliceArray(offset..<offset + size))
            }
        }
        val data = output.readByteArray()
        require(data.size == newObjSize) { "expected size after applying delta to be $newObjSize, is ${data.size}" }

        val type = when (baseObj) {
            is Blob -> "blob"
            is Tree -> "tree"
            is Commit -> "commit"
        }

        return Object.fromUncompressedBytes(
            "$type ${data.size}".toByteArray() + 0 + data
        )
    }


    companion object {
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

        val supportedTypes = setOf(ObjectType.Tree, ObjectType.Commit, ObjectType.Blob, ObjectType.RefDelta)

        suspend fun getPackForRef(url: Url, ref: Ref): Pack {
            val http = HttpClient(CIO)
            val response = http.post(url) {
                headers {
                    append("Content-Type", "application/x-git-upload-pack-request")
                }
                setBody("0032want ${ref.hash}\n00000008done")
            }
            require(response.status == HttpStatusCode.Companion.OK) { "got status: ${response.status}, expected: 200 OK" }
            require(response.contentType().toString() == "application/x-git-upload-pack-result")
            val buffer = Buffer().apply { write(response.body()) }
            validatePack(buffer)
            return Pack(buffer)
        }

        fun validatePack(buffer: Buffer) {
            require(buffer.readLine() == "0008NAK")
            require(buffer.readString(4) == "PACK")
            val version = buffer.readInt()
            require(version in 2..3)
        }
    }
}
