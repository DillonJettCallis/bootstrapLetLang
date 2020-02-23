import java.io.File


actual fun readFile(path: String): String {
  return File(path).readText()
}
