
private data class TokenCursor(val tokens: List<Token>, val index: Int) {
  fun isEmpty(): Boolean = index + 1 >= tokens.size
  fun curr(): Token = tokens[index]
  fun skip(): TokenCursor = this.copy(index = index + 1)
  fun next(): Pair<TokenCursor, Token> {
    return skip() to curr()
  }
}

fun parse(tokens: List<Token>): AstFile {
  val cursor = TokenCursor(tokens, 0)

  return parseModule(cursor, listOf())
}

fun parseExpression(tokens: List<Token>): Expression {
  val cursor = TokenCursor(tokens, 0)

  return parseExpression(cursor).second
}

private tailrec fun parseModule(cursor: TokenCursor, declarations: List<Declaration>): AstFile {
  return if (cursor.isEmpty()) {
    AstFile(declarations)
  } else {
    val (nextCursor, token) = cursor.next()

    when (token) {
      is TokenWord -> {
        val (finalCursor, dec) = when (token.value) {
          "private" -> parseDeclaration(nextCursor, AccessModifier.Private)
          "internal" -> parseDeclaration(nextCursor, AccessModifier.Internal)
          "protected" -> parseDeclaration(nextCursor, AccessModifier.Protected)
          "public" -> parseDeclaration(nextCursor, AccessModifier.Public)
          else -> parseDeclaration(cursor, AccessModifier.Internal)
        }

        parseModule(finalCursor, declarations + dec)
      }
      else -> token.pos.fail("Expected declaration ")
    }
  }
}

private fun parseDeclaration(cursor: TokenCursor, access: AccessModifier): Pair<TokenCursor, Declaration> {
  val (nextCursor, curr) = cursor.next()
  val pos = curr.pos

  return if (curr is TokenWord) {
    when (curr.value) {
      "val" -> parseConstDeclare(nextCursor, access, pos)
      "atom" -> parseAtomDeclare(nextCursor, access, pos)
      "fun" -> parseFunctionDeclare(nextCursor, access, pos)
      "data" -> parseDataDeclare(nextCursor, access, pos)
      "enum" -> parseEnumDeclare(nextCursor, access, pos)
      "import" -> parseImportDeclare(nextCursor, pos)
      "protocol" -> TODO()
      "implement" -> parseImplDeclare(nextCursor, access, pos)
      else -> pos.fail("Expected declaration but found `${curr.value}` ")
    }
  } else {
    pos.fail("Expected declaration but found `${curr}` ")
  }
}

private fun parseConstDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, ConstantDeclare> {
  val (assignCursor, assign) = parseSingleAssignmentStatement(cursor, pos)

  val type = assign.declaredType

  if (type == UnknownType) {
    assign.pos.fail("Constants must declare their type")
  }

  return assignCursor to ConstantDeclare(assign, type, access, pos)
}

private fun parseAtomDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, AtomDeclare> {
  val (finalCursor, token) = cursor.next()

  if (token is TokenWord) {
    return finalCursor to AtomDeclare(token.value, access, pos)
  } else {
    token.pos.fail("Expected type")
  }
}

private fun parseEnumDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, EnumDeclare> {
  val (nameCursor, nameToken) = cursor.next()

  if ( nameToken !is TokenWord ) {
    nameToken.pos.fail("Expected name of type")
  }

  val name = nameToken.value

  val (equalsCursor, equals) = nameCursor.next()

  if (equals !is TokenSymbol || equals.value != "{") {
    equals.pos.fail("Expected type assignment")
  }

  fun parseEnumArguments(cursor: TokenCursor, params: List<String>): Pair<TokenCursor, List<String>> {
    val (nextCursor, next) = cursor.next();

    if (next !is TokenWord) {
      next.pos.fail("Expected enum name")
    }

    val withNext = params + next.value

    val (maybeCommaCursor, maybeComma) = nextCursor.next();

    if (maybeComma !is TokenSymbol) {
      maybeComma.pos.fail("Expected either a comma or the close of an enum")
    }

    return when (maybeComma.value) {
      "," -> {
      // ok it's a comma, there MIGHT be another value after this one
      val (maybeCloseCursor, maybeClose) = maybeCommaCursor.next();

      if (maybeClose is TokenSymbol && maybeClose.value == "}") {
        // it's closed
        (maybeCloseCursor to withNext)
      } else {
        parseEnumArguments(maybeCommaCursor, withNext)
      }
    }
      "}" -> (maybeCommaCursor to withNext)
      else -> maybeComma.pos.fail("Expected either a comma or the close of an enum")
    }
  }

  val (typeCursor, values) = parseEnumArguments(equalsCursor, listOf())

  return (typeCursor to EnumDeclare(name, values, access, pos))
}

private fun parseImportDeclare(cursor: TokenCursor, pos: Position): Pair<TokenCursor, ImportDeclare> {
  val (orgCursor, orgToken) = cursor.next()

  if (orgToken !is TokenWord) {
    orgToken.pos.fail("Expected import package name")
  }

  val orgName = orgToken.value

  val (slashCursor, slash) = orgCursor.next()

  if (slash !is TokenSymbol || slash.value != "/") {
    slash.pos.fail("Expected import package module delimiter '/'")
  }

  tailrec fun parseImportPath(cursor: TokenCursor, init: List<String> = emptyList()): Pair<TokenCursor, List<String>> {
    val (nextCursor, nextToken) = cursor.next()

    if (nextToken !is TokenWord) {
      nextToken.pos.fail("Expected module path in import")
    }

    val result = init + nextToken.value

    val maybeDot = nextCursor.curr()

    return if (maybeDot is TokenSymbol && maybeDot.value == ".") {
      parseImportPath(nextCursor.skip(), result)
    } else {
      nextCursor to result
    }
  }

  val (maybeModuleCursor, maybeModule) = slashCursor.next()

  if (maybeModule !is TokenWord) {
    maybeModule.pos.fail("Expected module name in import")
  }

  val (maybeSlashCursor, maybeSlash) = maybeModuleCursor.next()

  val (moduleName, finalCursor, path) = if (maybeSlash is TokenSymbol && maybeSlash.value == "/") {
    // this is a fully qualified import, nothing special to do
    val (finalCursor, path) = parseImportPath(maybeSlashCursor)
    Triple(maybeModule.value, finalCursor, path)
  } else {
    if (orgName == "this") {
      val (finalCursor, path) = parseImportPath(slashCursor)
      Triple("this", finalCursor, path)
    } else {
      maybeSlash.pos.fail("Only a 'this' import can skip the org name")
    }
  }

  return finalCursor to ImportDeclare(orgName, moduleName, path, pos)
}

private fun parseDataDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, DataDeclare> {
  val (nameCursor, nameToken) = cursor.next()

  if (nameToken !is TokenWord) {
    nameToken.pos.fail("Expected name of data structure")
  }

  val name = nameToken.value

  val (openCursor, open) = nameCursor.next()

  if (open !is TokenSymbol || open.value != "{") {
    nameToken.pos.fail("Expected data declaration open bracket")
  }

  val (finalCursor, fields) = parseDataField(openCursor)

  return finalCursor to DataDeclare(name, fields, access, pos)
}

private tailrec fun parseDataField(cursor: TokenCursor, init: Map<String, Type> = emptyMap()): Pair<TokenCursor, Map<String, Type>> {
  val (nameCursor, name) = cursor.next()

  if (name !is TokenWord) {
    name.pos.fail("Expected field name")
  }

  val (colonCursor, colon) = nameCursor.next()

  if (colon !is TokenSymbol || colon.value != ":") {
    colon.pos.fail("Expected data field type")
  }

  val (typeCursor, type) = parseType(colonCursor)

  val result = init + (name.value to type)

  val (endCursor, maybeEnd) = typeCursor.next()

  if (maybeEnd !is TokenSymbol) {
    maybeEnd.pos.fail("Expected end of data field declarations")
  }

  return when (maybeEnd.value) {
    "," -> parseDataField(endCursor, result)
    "}" -> endCursor to result
    else -> maybeEnd.pos.fail("Expected end of data field declarations")
  }
}

private fun parseImplDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, ImplDeclare> {
  val (baseCursor, baseToken) = cursor.next()

  if (baseToken !is TokenWord) {
    baseToken.pos.fail("Expected base type for implementation")
  }

  val base = NamedType(baseToken.value)

  val maybeFor = baseCursor.curr()

  val (forCursor, forType) = if (maybeFor is TokenWord && maybeFor.value == "for") {
    val (forCursor, forType) = baseCursor.skip().next()

    if (forType !is TokenWord) {
      forType.pos.fail("Expected for type of implementation")
    }

    forCursor to NamedType(forType.value)
  } else {
    baseCursor to null
  }

  val (openCursor, openBracket) = forCursor.next()

  if (openBracket !is TokenSymbol || openBracket.value != "{") {
    openBracket.pos.fail("Expected open bracket of implementation block")
  }

  val (finalCursor, funcs) = parseImplBody(openCursor, base)

  return finalCursor to ImplDeclare(base, forType, funcs, access, pos)
}

private tailrec fun parseImplBody(cursor: TokenCursor, base: Type, init: List<FunctionDeclare> = emptyList()): Pair<TokenCursor, List<FunctionDeclare>> {
  val (nextCursor, maybeAccess) = cursor.next()

  if (maybeAccess !is TokenWord) {
    maybeAccess.pos.fail("Expected function declaration")
  }

  val (startCursor, access) = when (maybeAccess.value) {
    "private" -> nextCursor to AccessModifier.Private
    "internal" -> nextCursor to AccessModifier.Internal
    "protected" -> nextCursor to AccessModifier.Protected
    "public" -> nextCursor to AccessModifier.Public
    "fun" -> cursor to AccessModifier.Internal
    else -> maybeAccess.pos.fail("Expected function declaration")
  }

  val (funCursor, funToken) = startCursor.next()

  if (funToken !is TokenWord || funToken.value != "fun") {
    funToken.pos.fail("Expected function declaration")
  }

  val (finalCursor, func) = parseFunctionDeclare(funCursor, access, funToken.pos)
  val result = init + func

  val maybeEnd = finalCursor.curr()

  return if (maybeEnd is TokenSymbol && maybeEnd.value == "}") {
    finalCursor.skip() to result
  } else {
    parseImplBody(finalCursor, base, result)
  }
}

private fun parseFunctionDeclare(cursor: TokenCursor, access: AccessModifier, pos: Position): Pair<TokenCursor, FunctionDeclare> {
  val (finalCursor, functionStatement) = parseFunctionStatement(cursor, pos)

  return finalCursor to FunctionDeclare(functionStatement, access, pos)
}

private fun parseFunctionStatement(cursor: TokenCursor, pos: Position): Pair<TokenCursor, FunctionStatement> {
  val (nameCursor, nameToken) = cursor.next()

  if (nameToken is TokenWord) {
    val name = nameToken.value

    val maybeBracket = nameCursor.curr()

    val (typeParamCursor, typeParams) = if (maybeBracket is TokenSymbol && maybeBracket.value == "[") {
      parseFunctionTypeParameters(nameCursor.skip())
    } else {
      nameCursor to emptyList()
    }

    val (parensCursor, openParen) = typeParamCursor.next()

    if (openParen !is TokenSymbol || openParen.value != "(") {
      openParen.pos.fail("Expected open ( of function declaration")
    }

    val maybeClose = parensCursor.curr()

    val (closeCursor, parameters) = if (maybeClose is TokenSymbol && maybeClose.value == ")") {
      parensCursor.skip() to emptyList()
    } else {
      parseFunctionParameters(parensCursor)
    }

    val (colonCursor, colon) = closeCursor.next()

    if (colon !is TokenSymbol || colon.value != ":") {
      colon.pos.fail("Expected function result type declaration")
    }

    val (resultCursor, resultType) = parseType(colonCursor)
    val (equalsCursor, equals) = resultCursor.next()

    if (equals !is TokenSymbol || equals.value != "=") {
      equals.pos.fail("Expected function body assignment")
    }

    val (bodyCursor, body) = parseExpression(equalsCursor)

    val functionType = FunctionType(parameters.map { it.second }, resultType, typeParams)

    val lambdaExp = LambdaExp(parameters.map { it.first }, body, functionType, pos)

    return bodyCursor to FunctionStatement(name, lambdaExp, pos)
  } else {
    nameToken.pos.fail("Expected function name")
  }
}

private fun parseAssignmentStatement(cursor: TokenCursor, pos: Position): Pair<TokenCursor, Statement> {
  val maybeDestructure = cursor.curr()

  return when {
    maybeDestructure is TokenSymbol && maybeDestructure.value == "(" -> {
      val (patternCursor, patterns) = parseDestructureTuplePatterns(cursor.skip())

      val (equalsCursor, equals) = patternCursor.next()

      if (equals !is TokenSymbol || equals.value != "=") {
        equals.pos.fail("Expected assignment = operator")
      }

      val (bodyCursor, body) = parseExpression(equalsCursor)

      return bodyCursor to DeconstructTupleStatement(body, patterns, pos)
    }
    maybeDestructure is TokenSymbol && maybeDestructure.value == "{" -> {
      val (patternCursor, patterns) = parseDestructureDataPatterns(cursor.skip())

      val (equalsCursor, equals) = patternCursor.next()

      if (equals !is TokenSymbol || equals.value != "=") {
        equals.pos.fail("Expected assignment = operator")
      }

      val (bodyCursor, body) = parseExpression(equalsCursor)

      return bodyCursor to DeconstructDataStatement(body, patterns, pos)
    }
    maybeDestructure is TokenWord -> parseSingleAssignmentStatement(cursor, pos)
    else -> maybeDestructure.pos.fail("Expected assignment pattern")
  }
}

private fun parseSingleAssignmentStatement(cursor: TokenCursor, pos: Position): Pair<TokenCursor, AssignmentStatement> {
  val (nameCursor, nameToken) = cursor.next()

  if (nameToken !is TokenWord) {
    nameToken.pos.fail("Expected assignment identifier")
  }

  val name = nameToken.value

  val (maybeColonCursor, maybeColon) = nameCursor.next()

  val (typeCursor, type) = if (maybeColon is TokenSymbol && maybeColon.value == ":") {
    parseType(maybeColonCursor)
  } else {
    nameCursor to UnknownType
  }

  val (equalsCursor, equals) = typeCursor.next()

  if (equals !is TokenSymbol || equals.value != "=") {
    equals.pos.fail("Expected assignment = operator")
  }

  val (bodyCursor, body) = parseExpression(equalsCursor)

  return bodyCursor to AssignmentStatement(name, type, body, pos)
}

private tailrec fun parseDestructureTuplePatterns(cursor: TokenCursor, init: List<String> = emptyList()): Pair<TokenCursor, List<String>> {
  val (firstCursor, nameToken) = cursor.next()

  if (nameToken !is TokenWord) {
    nameToken.pos.fail("Expected identifier in data destructure assignment")
  }

  val name = nameToken.value

  val result = init + name

  val (endCursor, maybeEnd) = firstCursor.next()

  if (maybeEnd !is TokenSymbol) {
    maybeEnd.pos.fail("Expected end of destructure tuple pattern")
  }

  return when (maybeEnd.value) {
    "," -> parseDestructureTuplePatterns(endCursor, result)
    ")" -> endCursor to result
    else -> maybeEnd.pos.fail("Expected end of destructure tuple pattern")
  }
}

private tailrec fun parseDestructureDataPatterns(cursor: TokenCursor, init: List<Pair<String, String>> = emptyList()): Pair<TokenCursor, List<Pair<String, String>>> {
  val (firstCursor, nameToken) = cursor.next()

  if (nameToken !is TokenWord) {
    nameToken.pos.fail("Expected identifier in data destructure assignment")
  }

  val name = nameToken.value

  val maybeColon = firstCursor.curr()

  val (baseCursor, baseName) = if (maybeColon is TokenSymbol && maybeColon.value == ":") {
    val (baseCursor, baseToken) = firstCursor.skip().next()

    if (baseToken !is TokenWord) {
      baseToken.pos.fail("Expected identifier in data destructure assignment")
    }

    baseCursor to baseToken.value
  } else {
    firstCursor to name
  }

  val result = init + (name to baseName)

  val (endCursor, maybeEnd) = baseCursor.next()

  if (maybeEnd !is TokenSymbol) {
    maybeEnd.pos.fail("Expected end of destructure data pattern")
  }

  return when (maybeEnd.value) {
    "," -> parseDestructureDataPatterns(endCursor, result)
    "}" -> endCursor to result
    else -> maybeEnd.pos.fail("Expected end of destructure data pattern")
  }
}

private tailrec fun parseFunctionTypeParameters(cursor: TokenCursor, init: List<PlaceholderType> = emptyList()): Pair<TokenCursor, List<PlaceholderType>> {
  val (nextCursor, nextWord) = cursor.next()

  if (nextWord is TokenWord) {
    val result = init + PlaceholderType(nextWord.value)

    val (finalCursor, maybeEnd) = nextCursor.next()

    if (maybeEnd is TokenSymbol) {
      when (maybeEnd.value) {
        "]" -> return finalCursor to result
        "," -> return parseFunctionTypeParameters(finalCursor, result)
      }
    }
  }

  nextWord.pos.fail("Expected function type parameter declaration")
}

private tailrec fun parseFunctionParameters(cursor: TokenCursor, init: List<Pair<String, Type>> = emptyList()): Pair<TokenCursor, List<Pair<String, Type>>> {
  val (nextCursor, nextWord) = cursor.next()

  if (nextWord is TokenWord) {
    val name = nextWord.value

    val ( result, finalCursor, maybeEnd ) = if (name == "this") {
      // allow 'this' anytime, any where. Let the Typechecker enforce validity
      val result = init + ( name to NamedType(name) )
      val (finalCursor, maybeEnd) = nextCursor.next();
      Triple( result, finalCursor, maybeEnd )
    } else {
      val (colonCursor, colon) = nextCursor.next()

      if (colon !is TokenSymbol || colon.value != ":") {
        colon.pos.fail("Expected ':'")
      }

      val (typeCursor, type) = parseType(colonCursor)

      val result = init + (name to type)

      val (finalCursor, maybeEnd) = typeCursor.next()
      Triple( result, finalCursor, maybeEnd )
    }

    if (maybeEnd is TokenSymbol) {
      when (maybeEnd.value) {
        ")" -> return finalCursor to result
        "," -> return parseFunctionParameters(finalCursor, result)
      }
    }
  }

  nextWord.pos.fail("Expected function parameter declaration")
}


private fun parseExpression(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  return parseMatchExp(cursor)
}

private fun parseMatchExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeMatch = cursor.curr()

  return if (maybeMatch is TokenWord && maybeMatch.value == "match") {
    val (openParenCursor, openParen) = cursor.skip().next()

    if (openParen !is TokenSymbol || openParen.value != "(") {
      openParen.pos.fail("Expected open paren after match")
    }

    val (baseCursor, base) = parseExpression(openParenCursor)

    val (closeParenCursor, closeParen) = baseCursor.next()

    if (closeParen !is TokenSymbol || closeParen.value != ")") {
      closeParen.pos.fail("Expected close paren after match value")
    }

    val (openCursor, openBracket) = closeParenCursor.next()

    if (openBracket !is TokenSymbol || openBracket.value != "{") {
      openBracket.pos.fail("Expected open bracket of match expression")
    }

    val (patternCursor, patterns) = parseMatchPattern(openCursor)

    patternCursor to MatchExp(base, patterns, UnknownType, maybeMatch.pos)
  } else {
    parseIfExp(cursor)
  }
}

private tailrec fun parseMatchPattern(cursor: TokenCursor, init: List<MatchPattern> = emptyList()): Pair<TokenCursor, List<MatchPattern>> {
  val (baseCursor, base) = parseExpression(cursor)

  val maybeWhen = baseCursor.curr()

  val (guardCursor, guard) = if (maybeWhen is TokenWord && maybeWhen.value == "when") {
    parseExpression(baseCursor.skip())
  } else {
    baseCursor to null
  }

  val (arrowCursor, arrow) = guardCursor.next()

  if (arrow !is TokenSymbol || arrow.value != "=>") {
    arrow.pos.fail("Expected arrow of match pattern")
  }

  val (bodyCursor, body) = parseExpression(arrowCursor)

  val result = init + MatchPattern(base, guard, body, cursor.curr().pos)

  val maybeEnd = bodyCursor.curr()

  return if (maybeEnd is TokenSymbol && maybeEnd.value == "}") {
    bodyCursor.skip() to result
  } else {
    parseMatchPattern(bodyCursor, result)
  }
}

private fun parseIfExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeIf = cursor.curr()

  if (maybeIf is TokenWord && maybeIf.value == "if") {
    val (openParenCursor, openParen) = cursor.skip().next()

    if (openParen !is TokenSymbol || openParen.value != "(") {
      openParen.pos.fail("Expected open paren after if")
    }

    val (conditionCursor, condition) = parseExpression(openParenCursor)

    val (closeParenCursor, closeParen) = conditionCursor.next()

    if (closeParen !is TokenSymbol || closeParen.value != ")") {
      openParen.pos.fail("Expected close paren after if condition")
    }

    val (thenCursor, thenBlock) = parseExpression(closeParenCursor)

    val maybeElse = thenCursor.curr()

    val (elseCursor, elseBlock) = if (maybeElse is TokenWord && maybeElse.value == "else") {
      parseExpression(thenCursor.skip())
    } else {
      thenCursor to null
    }

    return elseCursor to IfExp(condition, thenBlock, elseBlock, UnknownType, maybeIf.pos)
  } else {
    return parseReturnExp(cursor)
  }
}

private fun parseReturnExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeReturn = cursor.curr()

  return if (maybeReturn is TokenWord && maybeReturn.value == "return") {
    val (finalCursor, ex) = parseExpression(cursor.skip())

    finalCursor to ReturnExp(ex, maybeReturn.pos)
  } else {
    parseThrowExp(cursor)
  }
}

private fun parseThrowExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeThrow = cursor.curr()

  return if (maybeThrow is TokenWord && maybeThrow.value == "throw") {
    val (finalCursor, ex) = parseExpression(cursor.skip())

    finalCursor to ThrowExp(ex, maybeThrow.pos)
  } else {
    parseBinaryExp(cursor)
  }
}

private fun parseBinaryExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val start = ::parseIsExp
  val prod = parseBinaryExpSet(setOf("*", "/"), start)
  val sum = parseBinaryExpSet(setOf("+", "-"), prod)
  val compare = parseBinaryExpSet(setOf(">", ">=", "<", "<="), sum)
  val equal = parseBinaryExpSet(setOf("==", "!="), compare)
  val and = parseBinaryExpSet(setOf("&&"), equal)
  val or = parseBinaryExpSet(setOf("||"), and)
  return or(cursor)
}

private fun parseBinaryExpSet(ops: Set<String>, next: (TokenCursor) -> Pair<TokenCursor, Expression>): (TokenCursor) -> Pair<TokenCursor, Expression> {
  tailrec fun rec(cursor: TokenCursor, left: Expression): Pair<TokenCursor, Expression> {
    val maybeOp = cursor.curr()

    return if (maybeOp is TokenSymbol && maybeOp.value in ops) {
      val (rightCursor, right) = next(cursor.skip())

      rec(rightCursor, BinaryOpExp(maybeOp.value, left, right, UnknownType, maybeOp.pos))
    } else {
      cursor to left
    }
  }

  return {
    val (leftCursor, left) = next(it)

    rec(leftCursor, left)
  }
}

private fun parseIsExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val (leftCursor, left) = parseAccessExp(cursor)

  val maybeIs = leftCursor.curr()

  return if (maybeIs is TokenWord && maybeIs.value in setOf("is", "isNot")) {
    val (rightCursor, right) = parseAccessExp(leftCursor.skip())

    rightCursor to BinaryOpExp(maybeIs.value, left, right, UnknownType, maybeIs.pos)
  } else {
    leftCursor to left
  }

}

// just like parseBinaryExpSet except that access must always have an identifier on the right side
private fun parseAccessExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  tailrec fun rec(leftCursor: TokenCursor, left: Expression): Pair<TokenCursor, Expression> {
    val maybeOp = leftCursor.curr()

    if (maybeOp is TokenSymbol && maybeOp.value == ".") {
      val (rightCursor, rightExp) = parseCall(leftCursor.skip())

      val newLeft = when (rightExp) {
        is IdentifierExp -> BinaryOpExp(maybeOp.value, left, rightExp, UnknownType, maybeOp.pos)
        is CallExp -> {
          val callRight = rightExp.func

          if (callRight !is IdentifierExp) {
            maybeOp.pos.fail("Expected identifier to right of access op")
          }

          val right = BinaryOpExp(maybeOp.value, left, callRight, UnknownType, maybeOp.pos)

          val newCall = rightExp.copy( func = right )

          newCall
        }
        is ConstructExp -> {
          val constructRight = rightExp.base

          if (constructRight !is IdentifierExp) {
            maybeOp.pos.fail("Expected identifier to right of access op")
          }

          val right = BinaryOpExp(maybeOp.value, left, constructRight, UnknownType, maybeOp.pos)

          val newCall = rightExp.copy( base = right )

          newCall
        }
        else -> maybeOp.pos.fail("Expected identifier to right of access op")
      }

      return rec(rightCursor, newLeft)
    } else {
      return leftCursor to left
    }
  }

  val (leftCursor, left) = parseCall(cursor)
  return rec(leftCursor, left)
}

private fun parseCall(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val (callCursor, base) = parseConstruct(cursor)

  val maybeOpenBracket = callCursor.curr()

  val (genCursor, genArguments) = if (maybeOpenBracket is TokenSymbol && maybeOpenBracket.value == "[") {
    val (closeCursor, args) = parseTypeList(callCursor.skip())

    if (args.isEmpty()) {
      maybeOpenBracket.pos.fail("Expected type arguments")
    }

    val (finalCursor, closeBracket) = closeCursor.next()

    if (closeBracket is TokenSymbol && closeBracket.value == "]") {
      finalCursor to args
    } else {
      closeBracket.pos.fail("Expected end of type arguments")
    }
  } else {
    callCursor to emptyList()
  }

  val maybeOpen = genCursor.curr()

  return if (maybeOpen is TokenSymbol && maybeOpen.value == "(") {
    val openCursor = genCursor.skip()
    val maybeClose = openCursor.curr()

    val (closeCursor, args) = if (maybeClose is TokenSymbol && maybeClose.value == ")") {
      openCursor.skip() to emptyList()
    } else {
      parseCallArguments(openCursor)
    }

    closeCursor to CallExp(base, args, genArguments, UnknownType, base.pos)
  } else {
    callCursor to base
  }
}

private tailrec fun parseCallArguments(cursor: TokenCursor, init: List<Expression> = emptyList()): Pair<TokenCursor, List<Expression>> {
  val (nextCursor, next) = parseExpression(cursor)
  val result = init + next

  val (closeCursor, maybeClose) = nextCursor.next()

  return if (maybeClose is TokenSymbol) {
    when (maybeClose.value) {
      "," -> parseCallArguments(closeCursor, result)
      ")" -> closeCursor to result
      else -> maybeClose.pos.fail("Expected end of arguments to function call")
    }
  } else {
    maybeClose.pos.fail("Expected end of arguments to function call")
  }
}

private fun parseConstruct(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val (baseCursor, base) = parseUnaryExp(cursor)

  val (maybeBracketCursor, maybeBracket) = baseCursor.next()

  return if (maybeBracket is TokenSymbol && maybeBracket.value == "{") {
    val maybeClose = maybeBracketCursor.curr()

    val (closeCursor, args) = if (maybeClose is TokenSymbol && maybeClose.value == "}") {
      maybeBracketCursor.skip() to emptyList()
    } else {
      parseConstructArgs(maybeBracketCursor)
    }

    closeCursor to ConstructExp(base, args, UnknownType, base.pos)
  } else {
    baseCursor to base
  }
}

private fun parseConstructArgs(cursor: TokenCursor, init: List<Pair<String, Expression>> = emptyList()): Pair<TokenCursor, List<Pair<String, Expression>>> {
  val (nameCursor, nameToken) = cursor.next()

  if (nameToken !is TokenWord) {
    nameToken.pos.fail("Expected field name")
  }

  val name = nameToken.value

  val maybeColon = nameCursor.curr()

  val (bodyExp, body) = if (maybeColon is TokenSymbol && maybeColon.value == ":") {
    parseExpression(nameCursor.skip())
  } else {
    nameCursor to IdentifierExp(name, UnknownType, nameToken.pos)
  }

  val result = init + (name to body)

  val (endCursor, maybeEnd) = bodyExp.next()

  return if (maybeEnd is TokenSymbol) {
    when (maybeEnd.value) {
      "," -> parseConstructArgs(endCursor, result)
      "}" -> endCursor to result
      else -> maybeEnd.pos.fail("Expected end of construct")
    }
  } else {
    maybeEnd.pos.fail("Expected end of construct")
  }
}

private fun parseUnaryExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeOp = cursor.curr()

  return if (maybeOp is TokenSymbol && maybeOp.value in setOf("-", "!")) {
    val (bodyCursor, body) = parseExpression(cursor.skip())

    bodyCursor to UnaryOpExp(maybeOp.value, body, UnknownType, maybeOp.pos)
  } else {
    parseConstructTuple(cursor)
  }
}

private fun parseConstructTuple(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeParen = cursor.curr()

  return if (maybeParen is TokenSymbol && maybeParen.value == "(") {
    val openParenCursor = cursor.skip()
    val maybeClose = openParenCursor.curr()

    val (closeCursor, args) = if (maybeClose is TokenSymbol && maybeClose.value == ")") {
      openParenCursor.skip() to emptyList()
    } else {
      parseConstructTupleArgs(openParenCursor)
    }

    closeCursor to ConstructTupleExp(args, UnknownType, maybeParen.pos)
  } else {
    parseBlockExp(cursor)
  }
}

private fun parseConstructTupleArgs(cursor: TokenCursor, init: List<Expression> = emptyList()): Pair<TokenCursor, List<Expression>> {
  val (bodyExp, body) = parseExpression(cursor)

  val result = init + body

  val (endCursor, maybeEnd) = bodyExp.next()

  return if (maybeEnd is TokenSymbol) {
    when (maybeEnd.value) {
      "," -> parseConstructTupleArgs(endCursor, result)
      ")" -> endCursor to result
      else -> maybeEnd.pos.fail("Expected end of tuple construct")
    }
  } else {
    maybeEnd.pos.fail("Expected end of tuple construct")
  }
}


private fun parseBlockExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val maybeBracket = cursor.curr()

  return if (maybeBracket is TokenSymbol && maybeBracket.value == "{") {
    if (checkIsLambda(cursor.skip())) {
      parseLambdaExp(cursor.skip(), maybeBracket.pos)
    } else {
      val (bodyCursor, body) = parseBlockStatement(cursor.skip())

      bodyCursor to BlockExp(body, UnknownType, maybeBracket.pos)
    }
  } else {
    parseStringTemplate(cursor)
  }
}

private fun parseLambdaExp(cursor: TokenCursor, pos: Position): Pair<TokenCursor, Expression> {
  val maybeArrow = cursor.curr()

  val (paramCursor, parameters) = if (maybeArrow is TokenSymbol && maybeArrow.value in setOf("->", "=>")) {
    cursor to emptyList()
  } else {
    parseLambdaParameters(cursor)
  }

  val maybeTypeArrow = paramCursor.curr()

  val (resultCursor, resultType) = if (maybeTypeArrow is TokenSymbol && maybeTypeArrow.value == "->") {
    parseType(paramCursor.skip())
  } else {
    paramCursor to UnknownType
  }

  val (arrowCursor, arrowToken) = resultCursor.next()

  if (arrowToken !is TokenSymbol || arrowToken.value != "=>") {
    arrowToken.pos.fail("Expected lambda expression arrow")
  }

  val (bodyCursor, body) = parseBlockStatement(arrowCursor)

  val functionType = FunctionType(parameters.map { it.second }, resultType)

  return bodyCursor to LambdaExp(parameters.map { it.first }, BlockExp(body, UnknownType, pos), functionType, pos)
}

private tailrec fun parseLambdaParameters(cursor: TokenCursor, init: List<Pair<String, Type>> = emptyList()): Pair<TokenCursor, List<Pair<String, Type>>> {
  val (wordCursor, wordToken) = cursor.next()

  if (wordToken is TokenWord) {
    val name = wordToken.value

    val maybeColon = wordCursor.curr()

    val (typeCursor, type) = if (maybeColon is TokenSymbol && maybeColon.value == ":") {
      parseType(wordCursor.skip())
    } else {
      wordCursor to UnknownType
    }

    val result = init + (name to type)

    val maybeEnd = typeCursor.curr()

    if (maybeEnd is TokenSymbol) {
      when (maybeEnd.value) {
        "->", "=>" -> return typeCursor to result
        "," -> return parseLambdaParameters(typeCursor.skip(), result)
      }
    }
  }

  wordToken.pos.fail("Expected lambda parameter")
}

private tailrec fun checkIsLambda(cursor: TokenCursor, depth: Int = 1): Boolean {
  val (nextCursor, nextToken) = cursor.next()

  val nextDepth = if (nextToken is TokenSymbol) {
    when (nextToken.value) {
      "{" -> depth + 1
      "}" -> if (depth - 1 == 0) return false else depth - 1
      "=>" -> if (depth == 1) return true else depth
      else -> depth
    }
  } else {
    depth
  }

  return checkIsLambda(nextCursor, nextDepth)
}

private tailrec fun parseBlockStatement(cursor: TokenCursor, init: List<Statement> = emptyList()): Pair<TokenCursor, List<Statement>> {
  val start = cursor.curr()

  if (start is TokenSymbol && start.value == ";") {
    return parseBlockStatement(cursor.skip(), init)
  }

  if (start is TokenSymbol && start.value == "}") {
    return cursor.skip() to init
  }

  val (finalCursor, statement) = if (start is TokenWord) {
    when (start.value) {
      "debugger" -> cursor.skip() to DebuggerStatement(start.pos)
      "val" -> parseAssignmentStatement(cursor.skip(), start.pos)
      "fun" -> parseFunctionStatement(cursor.skip(), start.pos)
      "return" -> {
        val (returnCursor, returnEx) = parseExpression(cursor.skip())
        returnCursor to ExpressionStatement(ReturnExp(returnEx, start.pos), start.pos)
      }
      "throw" -> {
        val (returnCursor, throwEx) = parseExpression(cursor.skip())
        returnCursor to ExpressionStatement(ThrowExp(throwEx, start.pos), start.pos)
      }
      else -> {
        val (exCursor, ex) = parseExpression(cursor)
        exCursor to ExpressionStatement(ex, start.pos)
      }
    }
  } else {
    val (exCursor, ex) = parseExpression(cursor)
    exCursor to ExpressionStatement(ex, start.pos)
  }

  val result = init + statement

  return parseBlockStatement(finalCursor, result)
}

private fun parseStringTemplate(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val (leftCursor, left) = parseTermExp(cursor)

  if (left is IdentifierExp) {
    val maybeString = leftCursor.curr()

    if (maybeString is TokenString) {
      return leftCursor.skip() to parseStringTemplateWithPrefix(maybeString.value, maybeString.pos, left.name)
    }
  }

  return leftCursor to left
}

private fun parseTermExp(cursor: TokenCursor): Pair<TokenCursor, Expression> {
  val (finalCursor, token) = cursor.next()

  return finalCursor to when(token) {
    is TokenNumber -> NumberLiteralExp(token.value, token.pos)
    is TokenString -> parseStringTemplateWithPrefix(token.value, token.pos)
    is TokenWord -> {
      when (token.value) {
        "null" -> NullLiteralExp(token.pos)
        "true" -> BooleanLiteralExp(true, token.pos)
        "false" -> BooleanLiteralExp(false, token.pos)
        else -> IdentifierExp(token.value, UnknownType, token.pos)
      }
    }
    else -> token.pos.fail("Expected expression")
  }
}



// for testing only
fun parseTypeTest(tokens: List<Token>): Type {
  return parseTypeTuple(TokenCursor(tokens, 0)).second
}


private fun parseType(cursor: TokenCursor): Pair<TokenCursor, Type> {
  return parseTypeTuple(cursor)
}

private fun parseTypeTuple(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val maybeParen = cursor.curr()

  return if (maybeParen is TokenSymbol && maybeParen.value == "(") {
    val (nextCursor, result) = parseTypeList(cursor.skip())
    val (finalCursor, closeParen) = nextCursor.next()

    if (closeParen is TokenSymbol && closeParen.value == ")") {
      finalCursor to TupleType(result)
    } else {
      closeParen.pos.fail("Expected end of Type parens")
    }
  } else {
    parseTypeUnion(cursor)
  }
}

private fun parseTypeUnion(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val (leftCursor, left) = parseTypeIntersection(cursor)
  val (maybeOrCursor, maybeOr) = leftCursor.next()

  return if (maybeOr is TokenSymbol && maybeOr.value == "|") {
    val (rightCursor, right) = parseTypeUnion(maybeOrCursor)
    rightCursor to UnionType.from(left, right)
  } else {
    leftCursor to left
  }
}

private fun parseTypeIntersection(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val (leftCursor, left) = parseTypeFunction(cursor)
  val (maybeOrCursor, maybeOr) = leftCursor.next()

  return if (maybeOr is TokenSymbol && maybeOr.value == "&") {
    val (rightCursor, right) = parseTypeIntersection(maybeOrCursor)
    rightCursor to IntersectionType.from(left, right)
  } else {
    leftCursor to left
  }
}


private fun parseTypeFunction(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val maybeCurly = cursor.curr()

  if (maybeCurly is TokenSymbol && maybeCurly.value == "{") {
    val firstCursor = cursor.skip()
    val maybeArrow = firstCursor.curr()

    if (maybeArrow is TokenSymbol && maybeArrow.value == "->") {
      return parseTypeFunctionResult(firstCursor.skip(), emptyList())
    }

    val (nextCursor, parameters) = parseTypeList(firstCursor)
    val (arrowCursor, arrow) = nextCursor.next()

    if (arrow is TokenSymbol && arrow.value == "->") {
      return parseTypeFunctionResult(arrowCursor, parameters)
    } else {
      arrow.pos.fail("Expected arrow in function type declaration")
    }
  } else {
    return parseTypeGeneric(cursor)
  }
}

private fun parseTypeFunctionResult(cursor: TokenCursor, parameters: List<Type>): Pair<TokenCursor, Type> {
  val (nextCursor, resultType) = parseType(cursor)
  val (finalCursor, closeBracket) = nextCursor.next()

  if (closeBracket is TokenSymbol && closeBracket.value == "}") {
    return finalCursor to FunctionType(parameters, resultType)
  } else {
    closeBracket.pos.fail("Expected close of function type")
  }
}

private fun parseTypeGeneric(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val (firstCursor, base) = parseTypeIdentifier(cursor)
  val maybeBracket = firstCursor.curr()

  return if (maybeBracket is TokenSymbol && maybeBracket.value == "[") {
    val (nextCursor, parameters) = parseTypeList(firstCursor.skip())

    if (parameters.isEmpty()) {
      maybeBracket.pos.fail("Expected generic parameters")
    }

    val (finalCursor, closeBracket) = nextCursor.next()

    if (closeBracket is TokenSymbol && closeBracket.value == "]") {
      finalCursor to GenericType(base, parameters)
    } else {
      closeBracket.pos.fail("Expected end of generic type declaration")
    }
  } else {
    firstCursor to base
  }
}

private fun parseTypeIdentifier(cursor: TokenCursor): Pair<TokenCursor, Type> {
  val (finalCursor, token) = cursor.next()

  if (token is TokenWord) {
    return finalCursor to NamedType(token.value)
  } else {
    token.pos.fail("Expected type")
  }
}

private fun parseTypeList(cursor: TokenCursor): Pair<TokenCursor, List<Type>> {
  val (nextCursor, head) = parseType(cursor)

  return parseTypeListContinue(nextCursor, listOf(head))
}

private tailrec fun parseTypeListContinue(cursor: TokenCursor, init: List<Type> = emptyList()): Pair<TokenCursor, List<Type>> {
  val maybeComma = cursor.curr()

  return if (maybeComma is TokenSymbol && maybeComma.value == ",") {
    val (nextCursor, nextType) = parseType(cursor.skip())
    parseTypeListContinue(nextCursor, init + nextType)
  } else {
    cursor to init
  }
}
