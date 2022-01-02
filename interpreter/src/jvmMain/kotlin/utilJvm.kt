import java.io.File

actual class FileImpl(private val file: File) {

  actual constructor(path: String): this(File(path))

  actual val path: String
    get() = file.path

  actual fun extension(): String {
    return file.extension
  }

  actual fun walkFiles(): List<FileImpl> {
    return file.walk().filter { it.isFile }.map { FileImpl(it) }.toList()
  }

  actual fun readText(): String {
    return file.readText()
  }

  actual companion object {
    actual val systemFileSeparator: Char = File.separatorChar
  }

}
