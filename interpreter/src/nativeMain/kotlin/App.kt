@file:Suppress("NOTHING_TO_INLINE")

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.*

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Invalid arguments. Expected just one source file for now.")
  } else {
    val fileName = args.head()
    val tokens = lex(fileName, File.fromPath(fileName).readText())
    println("Tokens: ${tokens}")
  }
}

private data class StringCursor(val raw: String, val index: Int, val pos: Position) {

  fun isEmpty(): Boolean = index + 1 >= raw.size
  fun isNotEmpty(): Boolean = !isEmpty()
  fun curr(): Char = raw.getCharAt(index)
  fun prev(): Char = raw.getCharAt(index - 1)
  fun peek(): Char = raw.getCharAt(index + 1)
  fun skip(): StringCursor = if (index == -1) this.copy(index = index + 1) else this.copy(index = index + 1, pos = pos.increment(curr()))
  fun next(): Pair<StringCursor, Char> {
    val next = skip()
    return next to next.curr()
  }
}

data class Position(val line: Int, val col: Int, val src: String) {
  fun increment(next: Char): Position = if (next == '\n') this.copy(line = line + 1, col = 0) else this.copy(col = col + 1)
}

sealed class Token

data class TokenWord(val value: String, val pos: Position): Token()
data class TokenSymbol(val value: String, val pos: Position): Token()
data class TokenString(val value: String, val pos: Position): Token()
data class TokenNumber(val value: String, val pos: Position): Token()

private const val lowerCase = "abcdefghijklmnopqrstuvwxyz"
private val upperCase = lowerCase.toUpperCase()
private val letters = lowerCase.append(upperCase)
private const val digitStart = "0123456789"
private val digitContinue = digitStart.append(".")
private val wordStart = letters
private val wordContinue = letters.append(digitStart)
private const val quoteChars = "'\"`"
private const val whitespace = " \t\r\n"
private const val singletonSymbols = "({[]})_,;" // these symbols are always alone. They can start but never continue
private const val mergedSymbols = "=<>!-+/*:.&|" // these symbols merge with one another to form compound symbols
private val symbolStart = mergedSymbols.append(singletonSymbols)
private const val symbolEnd = mergedSymbols

fun lex(src: String, raw: String): List<Token> {
  val cursor = StringCursor( raw = raw, index = -1, pos = Position(line = 1, col = 1, src = src))

  return muncher(cursor, listOf())
}

private tailrec fun muncher(src: StringCursor, result: List<Token>): List<Token> {
  if (src.isEmpty()) {
    return result
  }

  val (nextCursor, nextChar) = try {
    src.next()
  } catch (e: Exception) {
    val isEmpty = src.isEmpty()
    val isNotEmpty = src.isNotEmpty()
    val index = src.index
    val size = src.raw.size
    val posLine = src.pos.line
    throw e
  }

  when {
    // checking for comments, both block and line
    nextChar == "/".char && nextCursor.isNotEmpty() && "/*".contains(nextCursor.peek()) -> {
      return when (nextCursor.peek()) {
        "/".char -> muncher(munchLineComment(nextCursor), result)
        "*".char -> muncher(munchBlockComment(nextCursor), result)
        else -> throw Exception("Unexpected character $nextChar")
      }
    }
    // check for number. Do not bother with making sure there is at most one decimal point yet.
    digitStart.contains(nextChar) -> {
      val (finalCursor, value) = munchDigit(nextCursor, nextChar.toString())

      return muncher(finalCursor, result + TokenWord(value = value, pos = nextCursor.pos))
    }
    // lex any words
    wordStart.contains(nextChar) -> {
      val (finalCursor, value) = munchWord(nextCursor, nextChar.toString())

      return muncher(finalCursor, result + TokenNumber(value = value, pos = nextCursor.pos))
    }
    // lex strings of any quote type
    quoteChars.contains(nextChar) -> {
      val (finalCursor, value) = munchString(nextCursor, "", nextChar)

      return muncher(finalCursor, result + TokenString(value = value, pos = nextCursor.pos))
    }
    // lex symbols
    symbolStart.contains(nextChar) -> {
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

private fun munchWord(src: StringCursor, working: String): Pair<StringCursor, String> = munchContinuation(src, working, wordContinue)

private fun munchSymbol(src: StringCursor, working: String): Pair<StringCursor, String> = munchContinuation(src, working, symbolEnd)

private tailrec fun munchContinuation(src: StringCursor, working: String, continuation: String): Pair<StringCursor, String> {
  val (nextCursor, nextChar) = src.next()

  return if (continuation.contains(nextChar)) {
    // tail recursion
    munchContinuation(nextCursor, working.append(nextChar), continuation)
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
    "\\".char -> { // something is escaped. Don't look at it, just pass both along no matter what.
      val (final, escapedChar) = nextCursor.next()

      munchString(final, working.append(nextChar).append(escapedChar), openType)
    }
    else -> munchString(nextCursor, working.append(nextChar), openType)
  }
}

private fun munchLineComment(src: StringCursor): StringCursor {
  return doUntil(src, { cur -> cur.skip() } , { it -> it.curr() == "\n".char })
}

private fun munchBlockComment(src: StringCursor): StringCursor {
  return doUntil(src, { cur -> cur.skip() } , { it -> it.curr() == "*".char && it.isNotEmpty() && it.peek() == "/".char })
}

private tailrec fun <Item> doUntil(init: Item, action: (Item) -> Item, test: (Item) -> Boolean): Item =
  if (test(init)) init else doUntil(action(init), action, test)


// Kotlin utils to make the library look more like my language

inline fun <Item> Array<Item>.head() = this[0]

class File(private val path: String) {

  fun readText(): String {
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

  companion object {
    fun fromPath(path: String) = File(path)
  }

}

inline val String.size: Int
  get() = this.length

inline fun String.getCharAt(index: Int) = this[index]

inline fun String.append(other: String) = this + other
inline fun String.append(other: Char) = this + other

inline val String.char: Char
  get() = this[0]

inline fun range(start: Int, end: Int) = start until end
