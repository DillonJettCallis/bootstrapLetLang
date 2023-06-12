private val escapes = mapOf(
  '\\' to '\\',
  't' to '\t',
  'r' to '\r',
  'n' to '\n',
  's' to ' ',
  '$' to '$',
  '\'' to '\'',
  '"' to '"',
  '`' to '`'
)

data class StringCursor(val raw: String, val index: Int, val pos: Position) {

  fun isEmpty(): Boolean = index >= raw.length
  fun isNotEmpty(): Boolean = !isEmpty()
  fun curr(): Char = raw[index]
  fun skip(): StringCursor = this.copy(index = index + 1, pos = pos.increment(curr()))
  fun next(): Pair<StringCursor, Char> {
    return skip() to curr()
  }
  fun nextEscaped(): Pair<StringCursor, Char> {
    val (nextCursor, firstChar) = this.next()

    if (firstChar == '\\') {
      if (nextCursor.isEmpty()) {
        this.pos.fail("Cannot end string with escape char")
      }

      val (finalCursor, nextChar) = nextCursor.next()

      val result = escapes[nextChar]

      if (result == null) {
        nextCursor.pos.fail("Invalid escape char")
      } else {
        return finalCursor to result
      }
    } else {
      return nextCursor to firstChar
    }
  }
}

data class Position(val line: Int, val col: Int, val src: String) {
  companion object {
    val native = Position(0, 0, "<Native>")
  }

  fun increment(next: Char): Position = if (next == '\n') this.copy(line = line + 1, col = 1) else this.copy(col = col + 1)
  fun fail(message: String): Nothing = throw Exception("$message at $line:$col in $src")
}

sealed class Token {
  abstract val pos: Position
}

data class TokenWord(val value: String, override val pos: Position): Token()
data class TokenSymbol(val value: String, override val pos: Position): Token()
data class TokenString(val value: String, override val pos: Position): Token()
data class TokenNumber(val value: String, override val pos: Position): Token()
data class TokenEOF(override val pos: Position): Token()

private const val lowerCase = "abcdefghijklmnopqrstuvwxyz"
private const val upperCase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val letters = lowerCase + upperCase + "_"
private const val digitStart = "0123456789"
private const val digitContinue = "$digitStart."
private const val wordStart = letters
private const val wordContinue = letters + digitStart
private const val quoteChars = "'\"`"
private const val whitespace = " \t\r\n"
private const val singletonSymbols = "({[]}),;" // these symbols are always alone. They can start but never continue
private const val mergedSymbols = "=<>!-+/*:.&|" // these symbols merge with one another to form compound symbols

fun lex(src: String, raw: String): List<Token> {
  val cursor = StringCursor( raw = raw, index = 0, pos = Position(line = 1, col = 1, src = src))

  return muncher(cursor, listOf())
}

fun lexFragment(raw: String, pos: Position): List<Token> {
  val cursor = StringCursor( raw = raw, index = 0, pos = pos)

  return muncher(cursor, listOf())
}

private tailrec fun muncher(src: StringCursor, result: List<Token>): List<Token> {
  if (src.isEmpty()) {
    return result + TokenEOF(src.pos)
  }

  val (nextCursor, nextChar) = src.next()

  when {
    // checking for comments, both block and line
    nextChar == '/' && nextCursor.isNotEmpty() && "/*".contains(nextCursor.curr()) -> {
      return when (nextCursor.curr()) {
        '/' -> muncher(munchLineComment(nextCursor), result)
        '*' -> muncher(munchBlockComment(nextCursor).skip(), result)
        else -> throw Exception("Unexpected character $nextChar")
      }
    }
    // check for number. Do not bother with making sure there is at most one decimal point yet.
    digitStart.contains(nextChar) -> {
      val (finalCursor, value) = munchDigit(nextCursor, nextChar.toString())

      return muncher(finalCursor, result + TokenNumber(value = value, pos = nextCursor.pos))
    }
    // lex any words
    wordStart.contains(nextChar) -> {
      val (finalCursor, value) = munchWord(nextCursor, nextChar.toString())

      return muncher(finalCursor, result + TokenWord(value = value, pos = nextCursor.pos))
    }
    // lex strings of any quote type
    quoteChars.contains(nextChar) -> {
      val (finalCursor, value) = munchString(nextCursor, "", nextChar)

      return muncher(finalCursor, result + TokenString(value = value, pos = nextCursor.pos))
    }
    // lex singleton symbols
    singletonSymbols.contains(nextChar) -> {
      return muncher(nextCursor, result + TokenSymbol(value = nextChar.toString(), pos = nextCursor.pos))
    }
    // lex symbols
    mergedSymbols.contains(nextChar) -> {
      val (finalCursor, value) = munchSymbol(nextCursor, nextChar.toString())

      return muncher(finalCursor, result + TokenSymbol(value = value, pos = nextCursor.pos))
    }
    // handle whitespace
    whitespace.contains(nextChar) -> {
      return muncher(nextCursor, result)
    }
    else -> throw Exception("Unexpected character $nextChar")
  }
}


private fun munchDigit(src: StringCursor, working: String): Pair<StringCursor, String> = munchContinuation(src, working, digitContinue)

fun munchWord(src: StringCursor, working: String): Pair<StringCursor, String> = munchContinuation(src, working, wordContinue)

private fun munchSymbol(src: StringCursor, working: String): Pair<StringCursor, String> = munchContinuation(src, working, mergedSymbols)

private tailrec fun munchContinuation(src: StringCursor, working: String, continuation: String): Pair<StringCursor, String> {
  if (src.isEmpty()) {
    return src to working
  }

  val (nextCursor, nextChar) = src.next()

  return if (continuation.contains(nextChar)) {
    // tail recursion
    munchContinuation(nextCursor, working + nextChar, continuation)
  } else {
    src to working
  }
}

/**
 * Munch the string, looking for the openType.
 * Do NOT process escapes or interpolation but DO allow escaped chars to pass through.
 */
private tailrec fun munchString(src: StringCursor, working: String, openType: Char): Pair<StringCursor, String> {
  val (nextCursor, nextChar) = src.next()

  // there is one close quote, maybe we are at the end
  return when (nextChar) {
    openType -> nextCursor to working
    '\\' -> { // something is escaped. Don't look at it, just pass both along no matter what.
      val (final, escapedChar) = nextCursor.next()

      munchString(final, working + nextChar + escapedChar, openType)
    }
    else -> munchString(nextCursor, working + nextChar, openType)
  }
}

private fun munchLineComment(src: StringCursor): StringCursor {
  return doUntil(src, { cur -> cur.skip() } , { it -> it.curr() == '\n' })
}

private fun munchBlockComment(src: StringCursor): StringCursor {
  return doUntil(src, { cur -> cur.skip() } , { it -> it.curr() == '*' && it.isNotEmpty() && it.skip().curr() == '/' }).skip()
}

private tailrec fun <Item> doUntil(init: Item, action: (Item) -> Item, test: (Item) -> Boolean): Item =
  if (test(init)) init else doUntil(action(init), action, test)
