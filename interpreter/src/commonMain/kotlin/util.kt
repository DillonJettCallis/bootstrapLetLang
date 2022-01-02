expect class FileImpl(path: String) {

  companion object {
    val systemFileSeparator: Char
  }

  val path: String

  fun extension(): String

  fun walkFiles(): List<FileImpl>

  fun readText(): String

}

fun FileImpl.relativePath(other: FileImpl): List<String> {
  return path.substringAfter(other.path).trimStart(FileImpl.systemFileSeparator).substringBeforeLast(".").split(FileImpl.systemFileSeparator)
}
