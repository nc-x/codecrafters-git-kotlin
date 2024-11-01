import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: your_program.sh <command> [<args>]")
        exitProcess(1)
    }

    when (args[0]) {
        "init" -> {
            val gitDir = File(".git")
            gitDir.mkdir()
            File(gitDir, "objects").mkdir()
            File(gitDir, "refs").mkdir()
            File(gitDir, "HEAD").writeText("ref: refs/heads/master\n")
            println("Initialized git directory")
        }

        "cat-file" -> {
            assert(args[1] == "-p")
            val blobSha = args[2]
            val file = File(".git/objects/${blobSha.substring(0..1)}/${blobSha.substring(2)}").readBytes()
            val contents = file.zlibDecode().substringAfter(0.toChar())
            print(contents)
        }

        "hash-object" -> {
            assert(args[1] == "-w")
            val file = File(args[2]).readBytes()
            val contents = "blob ${file.size}".toByteArray() + 0 + file
            val blobSha = contents.getSHA1()
            val compressed = contents.zlibEncode()
            println(blobSha)
            File(".git/objects/${blobSha.substring(0..1)}").mkdir()
            File(".git/objects/${blobSha.substring(0..1)}/${blobSha.substring(2)}").writeBytes(compressed)
        }

        else -> {
            println("Unknown command: ${args[0]}")
            exitProcess(1)
        }
    }
}

fun ByteArray.zlibEncode(): ByteArray {
    val output = ByteArrayOutputStream()
    DeflaterOutputStream(output).use { it.write(this) }
    return output.toByteArray()
}

fun ByteArray.zlibDecode() =
    InflaterInputStream(this.inputStream()).bufferedReader().use { it.readText() }

@OptIn(ExperimentalStdlibApi::class)
fun ByteArray.getSHA1(): String =
    MessageDigest.getInstance("SHA-1").digest(this).toHexString()
