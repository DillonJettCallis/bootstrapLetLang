fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Invalid arguments. Expected just one directory for now.")
  } else {
    val fileName = args.first()
    val baseFile = FileImpl(fileName)
    val files = baseFile.walkFiles()

    val astPackage: Map<List<String>, AstModule> = files.filter { it.extension() == "jett" }
      .fold(mapOf<List<String>, AstModule>()) { sum, next ->
        val raw = next.readText()
        val tokens = lex(next.path, raw)

        val module = parse(tokens)

        sum + (next.relativePath(baseFile) to module)
      }

    executePackage(AstPackage(astPackage), listOf(fileName))
  }
}
