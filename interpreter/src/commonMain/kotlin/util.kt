expect class FileImpl(path: String) {

  val path: String

  fun extension(): String

  fun walkFiles(): List<FileImpl>

  fun readText(): String

}

fun FileImpl.relativePath(other: FileImpl): List<String> {
  return path.substringAfter(other.path).trimStart('/').substringBeforeLast(".").split("/")
}
