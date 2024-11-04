import io.ktor.http.*
import java.io.File


const val infoRefs = "info/refs?service=git-upload-pack"
const val packUrl = "git-upload-pack"

suspend fun clone(url: String, outDir: String) {
    // prerequisites
    val infoRefs = Url("$url/$infoRefs")
    val packUrl = Url("$url/${packUrl}")

    val refs = Ref.discoverRefs(infoRefs)

    // we only support downloading HEAD atm
    val head = refs.find { it.name == "HEAD" } ?: error("HEAD not found")
    val pack = Pack.getPackForRef(packUrl, head)
    val objects = pack.parseObjects()

    // create directory
    File(outDir).mkdir()

    // create .git directory
    init(outDir)

    File(outDir, ".git/refs/heads/").mkdirs()
    File(outDir, ".git/refs/heads/master").writeText(head.hash)

    // write the objects
    for (obj in objects) {
        obj.write(outDir)
    }

    // generate the actual files
    val commit = Object.fromSHA(head.hash, outDir) as Commit
    val treeSha = commit.header.find { it.startsWith("tree ") }?.removePrefix("tree ") ?: error("tree sha not found")
    val tree = Object.fromSHA(treeSha, outDir) as Tree
    tree.entries.forEach { generateFile(outDir, outDir, it) }
}

private fun generateFile(outDir: String, fileDir: String, entry: Tree.Entry) {
    if (entry.mode == Tree.Mode.Directory) {
        File(fileDir, entry.name).mkdirs()
        val tree = Object.fromSHA(entry.sha, outDir) as Tree
        tree.entries.forEach { generateFile(outDir, "$fileDir/${entry.name}", it) }
    } else {
        val sha = entry.sha
        val blob = Object.fromSHA(sha, outDir)
        require(blob is Blob)
        File(fileDir, entry.name).writeBytes(blob.contents)
    }
}
