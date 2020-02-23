import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.*

actual fun readFile(path: String): String {
  require(access(path, F_OK) != -1) { "File $path not found!" }
  val file = fopen(path, "r")

  try {
    memScoped {
      val builder = StringBuilder()

      val bufferSize = 64 * 1024
      val buffer = allocArray<ByteVar>(bufferSize)

      while (true) {
        // reads one line at a time. Newline is kept in nextLine
        val nextLine = fgets(buffer, bufferSize, file)?.toKString()

        if (nextLine.isNullOrEmpty()) {
          break
        }

        builder.append(nextLine)
      }

      return builder.toString()
    }
  } finally {
    fclose(file)
  }
}
