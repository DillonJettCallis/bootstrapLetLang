fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Invalid arguments. Expected just one source file for now.")
  } else {
    val fileName = args.first()
    val raw = readFile(fileName)
    val tokens = lex(fileName, raw)
    println("Tokens: $tokens")

    val module = parse(tokens)

    println("Module: $module")

    val exec = executeMain(module, listOf(fileName, raw))

    println("Exec: $exec")
  }
}
