import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readLine

data class Ref(
    val hash: String,
    val name: String,
    val capabilities: List<String>,
    val isSymRefTo: String?,
) {
    companion object {
        internal suspend fun discoverRefs(url: Url): List<Ref> {
            val http = HttpClient(CIO)
            val response = http.get(url)
            val body = Buffer().apply { write(response.body()) }
            require(response.status == HttpStatusCode.Companion.OK)
            require(response.contentType().toString() == "application/x-git-upload-pack-advertisement")
            validateHeader(body)
            return parseRefs(body)
        }

        private fun validateHeader(buffer: Buffer) {
            val header = buffer.readByteArray(4).toString(Charsets.ISO_8859_1)
            require(header.all { it.isDigit() || it in 'a'..'f' }) { "header is: ${header}, expected each byte to be within 0..9 or a..f" }
            val firstPktLine = buffer.readLine()
            require(firstPktLine == "# service=git-upload-pack") { "first pkt line is $firstPktLine, expected: '# service=git-upload-pack'" }
            val flush = buffer.readByteArray(4).toString(Charsets.ISO_8859_1)
            require(flush == "0000") { "got $flush, expected flush packet: 0000" }
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
    }
}
