fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Invalid arguments. Expected just one source file for now.")
  } else {
    val fileName = args.first()
    val tokens = lex(fileName, readFile(fileName))
    println("Tokens: $tokens")

    val module = parse(tokens)

    println("Module: $module")
  }
}
