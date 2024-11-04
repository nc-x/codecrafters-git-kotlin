import java.io.File

fun init(dir: String = ".") {
    val gitDir = File(dir, ".git")
    gitDir.mkdir()
    File(gitDir, "objects").mkdir()
    File(gitDir, "refs").mkdir()
    File(gitDir, "HEAD").writeText("ref: refs/heads/master\n")
}
