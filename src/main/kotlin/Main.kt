import java.io.File
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: your_program.sh <command> [<args>]")
        exitProcess(1)
    }

    when (args[0]) {
        "init" -> {
            init()
            println("Initialized git directory")
        }

        "cat-file" -> {
            assert(args[1] == "-p")
            val sha = args[2]
            val obj = Object.fromSHA(sha)
            if (obj !is Blob) error("The provided SHA is for '${obj.prefix}'. Expected: 'blob'")
            print(obj.contents.toString(Charsets.ISO_8859_1))
        }

        "hash-object" -> {
            assert(args[1] == "-w")
            val file = File(args[2])
            val obj = Blob.fromFile(file)
            val sha = obj.write()
            println(sha)
        }

        "ls-tree" -> {
            assert(args[1] == "--name-only")
            val sha = args[2]
            val obj = Object.fromSHA(sha)
            if (obj !is Tree) error("The provided SHA is for '${obj.prefix}'. Expected: 'tree'")
            obj.getNames().forEach(::println)
        }

        "write-tree" -> {
            val tree = Tree.fromDir(File("."))
            val sha = tree.write()
            println(sha)
        }

        "commit-tree" -> {
            val treeSha = args[1]
            assert(args[2] == "-p")
            val parentSha = args[3]
            assert(args[4] == "-p")
            val message = args[5]
            val header = buildList<String> {
                add("tree $treeSha\n")
                add("parent $parentSha\n")
                val timestamp = System.currentTimeMillis() / 1000
                add("author ABC <abc@example.com> $timestamp +0000\n")
                add("committer ABC <abc@example.com> $timestamp +0000\n")
            }
            val commit = Commit(header, message)
            val sha = commit.write()
            println(sha)
        }

        "clone" -> {
            assert(args.size == 3)
            val url = args[1]
            val dir = args[2]
            clone(url, dir)
        }

        else -> {
            println("Unknown command: ${args[0]}")
            exitProcess(1)
        }
    }
}
