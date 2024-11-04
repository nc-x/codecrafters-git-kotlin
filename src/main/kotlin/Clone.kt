import io.ktor.http.*
import java.nio.file.Path


const val infoRefs = "info/refs?service=git-upload-pack"
const val packUrl = "git-upload-pack"

suspend fun clone(url: String, outDir: Path) {
    val infoRefs = Url("$url/$infoRefs")
    val refs = Ref.discoverRefs(infoRefs)
    for (ref in refs) {
        val packUrl = Url("$url/${packUrl}")
        if (ref.isSymRefTo != null) continue
        val pack = Pack.getPackForRef(packUrl, ref)
        val objects = pack.parseObjects()
        // for (obj in objects) {
        //     obj.write()
        // }
    }
}
