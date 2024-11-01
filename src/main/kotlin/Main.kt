import java.io.File
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
            val file = File(".git/objects/${blobSha.substring(0..1)}/${blobSha.substring(2)}")
            val contents = zlibDecode(file.readBytes()).substringAfter(0.toChar())
            print(contents)
        }

        else -> {
            println("Unknown command: ${args[0]}")
            exitProcess(1)
        }
    }
}

fun zlibDecode(data: ByteArray) =
    InflaterInputStream(data.inputStream()).bufferedReader().readText()
