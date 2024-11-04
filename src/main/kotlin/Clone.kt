import io.ktor.http.*
import java.io.File


const val infoRefs = "info/refs?service=git-upload-pack"
const val packUrl = "git-upload-pack"

suspend fun clone(url: String, outDir: String) {
    // prerequisites
    val infoRefs = Url("$url/$infoRefs")
    val packUrl = Url("$url/${packUrl}")

    val refs = Ref.discoverRefs(infoRefs)
    val objects = refs
        .filter { ref -> ref.isSymRefTo == null }
        .flatMap { ref ->
            val pack = Pack.getPackForRef(packUrl, ref)
            pack.parseObjects()
        }

    // create directory
    File(outDir).mkdir()

    // create .git directory
    init(outDir)

    File(outDir, ".git/refs/heads/").mkdirs()
    val headSha = refs.find { it.name == "HEAD" }?.hash ?: error("HEAD not found")
    File(outDir, ".git/refs/heads/master").writeText(headSha)

    // write the objects
    for (obj in objects) {
        obj.write(outDir)
    }
}
