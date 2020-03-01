

sealed class StringTemplateResult

data class StringTemplateString(val raw: String): StringTemplateResult()
data class StringTemplateChar(val char: Char): StringTemplateResult()
data class StringTemplate(val strings: List<String>, val values: List<Expression>): StringTemplateResult()


fun parseStringTemplateWithPrefix(raw: String, pos: Position, prefix: String? = null): Expression {
  val template = when (prefix) {
    "raw" -> StringTemplateString(raw)
    "char" -> {
      val (escapedCursor, escaped) = StringCursor(raw, 0, pos).nextEscaped()

      if (escapedCursor.isNotEmpty()) {
        pos.fail("Expected char literal in char prefixed string")
      } else {
        StringTemplateChar(escaped)
      }
    }
    else -> munchStringBody(StringCursor(raw, 0, pos), listOf(""), emptyList())
  }

  return when (template) {
    is StringTemplateString -> StringLiteralExp(template.raw, pos)
    is StringTemplateChar -> CharLiteralExp(template.char, pos)
    is StringTemplate -> CallExp(
      func = IdentifierExp("@template", stringTemplateType, pos),
      arguments = listOf(
        ListLiteralExp(template.strings.map { StringLiteralExp(it, pos) }, listOfType(StringType), pos),
        ListLiteralExp(template.values, listOfType(AnyType), pos)
      ),
      type = StringType,
      pos = pos)
  }
}

private tailrec fun munchStringBody(cursor: StringCursor, initStrings: List<String>, initValues: List<Expression>): StringTemplateResult {
  if (cursor.isEmpty()) {
    return if (initValues.isEmpty()) {
      StringTemplateString(initStrings.last())
    } else {
      StringTemplate(initStrings, initValues)
    }
  }

  val maybeDollar = cursor.curr()

  return if (maybeDollar == '$') {
    val nextCursor = cursor.skip()

    if (nextCursor.isEmpty()) {
      cursor.pos.fail("Cannot end a string with $")
    }

    val maybeBracket = nextCursor.curr()

    val (finalCursor, ex) = if (maybeBracket == '{') {
      val (bodyCursor, body) = blockBody(nextCursor.skip(), init = "", inside = listOf('{'))

      bodyCursor to parseExpression(lexFragment(body, nextCursor.pos))
    } else {
      val (wordCursor, word) = munchWord(nextCursor, "")

      wordCursor to IdentifierExp(word, UnknownType, nextCursor.pos)
    }

    munchStringBody(finalCursor, initStrings + "", initValues + ex)
  } else {
    val (nextCursor, nextChar) = cursor.nextEscaped()

    munchStringBody(nextCursor, initStrings.dropLast(1) + (initStrings.last() + nextChar), initValues)
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
