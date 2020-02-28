import kotlinx.cinterop.*
import platform.posix.*


actual class FileImpl actual constructor(actual val path: String) {

  actual fun extension(): String {
    return path.split("/".toRegex()).last().split(".".toRegex()).last()
  }

  actual fun walkFiles(): List<FileImpl> {
    fun listContents(path: String): List<String> {
      val result = mutableListOf<String>()
      val dir = opendir(path)

      if (dir != null) {
        try {
          var line = readdir(dir)

          while (line != null) {
            result += line.pointed.d_name.toKString()
            line = readdir(dir)
          }
        } finally {
          closedir(dir)
        }
      }

      return result
    }

    fun walk(path: String): List<String> {
      val contents = listContents(path)

      return if (contents.isEmpty()) {
        listOf(path)
      } else {
        contents.flatMap(::walk)
      }
    }

    return walk(path).map { FileImpl(it) }
  }

  actual fun readText(): String {
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


}




