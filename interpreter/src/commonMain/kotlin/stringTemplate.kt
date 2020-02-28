

sealed class StringTemplateResult

data class StringTemplateString(val raw: String): StringTemplateResult()
data class StringTemplateChar(val char: Char): StringTemplateResult()
data class StringTemplate(val strings: List<String>, val values: List<Expression>): StringTemplateResult()


fun parseStringTemplate(raw: String, pos: Position, prefix: String? = null): StringTemplateResult {

  return when (prefix) {
    "raw" -> StringTemplateString(raw)
    "char" -> {
      val escaped = processEscapes(raw)

      if (escaped.length != 1) {
        pos.fail("Expected char literal in char prefixed string")
      } else {
        StringTemplateChar(escaped.first())
      }
    }
    else -> munchStringBody(StringCursor(raw, -1, pos), listOf(""), emptyList())
  }
}

private tailrec fun munchStringBody(cursor: StringCursor, initStrings: List<String>, initValues: List<Expression>): StringTemplateResult {
  if (cursor.isEmpty()) {
    return if (initValues.isEmpty()) {
      StringTemplateString(processEscapes(initStrings.last()))
    } else {
      StringTemplate(initStrings.map(::processEscapes), initValues)
    }
  }

  val (nextCursor, maybeDollar) = cursor.next()

  return if (maybeDollar == '$') {
    val maybeBracket = nextCursor.peek()

    val (finalCursor, ex) = if (maybeBracket == '{') {
      val (bodyCursor, body) = blockBody(nextCursor.skip(), init = "", inside = listOf('{'))

      bodyCursor to parseExpression(lexFragment(body, nextCursor.pos))
    } else {
      val (wordCursor, word) = munchWord(nextCursor, "")

      wordCursor to IdentifierExp(word, UnknownType, nextCursor.pos)
    }

    munchStringBody(finalCursor, initStrings + "", initValues + ex)
  } else {
    munchStringBody(nextCursor, initStrings.dropLast(1) + (initStrings.last() + maybeDollar), initValues)
  }
}

private const val quotes = "'\"`"

private tailrec fun blockBody(cursor: StringCursor, init: String, inside: List<Char>): Pair<StringCursor, String> {
  val (nextCursor, nextChar) = cursor.next()

  return when {
    nextChar == '}' && inside.last() == '{' && inside.size == 1 -> nextCursor to init
    nextChar == '}' && inside.last() == '{' && inside.size > 1 -> blockBody(nextCursor, init + nextChar, inside.dropLast(1))
    nextChar == '{' && inside.last() == '{' -> blockBody(nextCursor, init + nextChar, inside + nextChar)
    nextChar in quotes && inside.last() == nextChar -> blockBody(nextCursor, init + nextChar, inside.dropLast(1))
    nextChar in quotes && inside.last() != nextChar -> blockBody(nextCursor, init + nextChar, inside + nextChar)
    else -> blockBody(nextCursor, init + nextChar, inside)
  }
}

private val escapes = listOf(
  "\\t" to "\t",
  "\\r" to "\r",
  "\\n" to "\n",
  "\\s" to " ",
  "\\$" to "$",
  "\\\\" to "\\"
)

private fun processEscapes(raw: String): String {
  return escapes.fold(raw) { prev, (src, dest) -> prev.replace(src, dest) }
}
