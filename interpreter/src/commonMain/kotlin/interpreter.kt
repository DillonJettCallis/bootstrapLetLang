private data class Scope(val values: MutableMap<String, JObject?>, val parent: Scope?) {

  operator fun get(key: String): JObject? {
    return this.values.getOrElse(key) { if (parent != null) parent[key] else throw Exception("No such value $key in scope") }
  }

  operator fun set(key: String, value: JObject?) {
    this.values[key] = value
  }

  fun child(): Scope {
    return Scope(HashMap(), this)
  }

}

private class ReturnException(val value: JObject?): RuntimeException()

fun executeMain(module: AstModule, args: List<String>): Any? {
  val moduleScope = initCoreScope().child()

  module.declarations.forEach {
    when (it) {
      is AtomDeclare -> moduleScope[it.name] = JAtom(it.name).makeJAtom()
      is DataDeclare -> moduleScope[it.name] = JObject(mapOf())
      is TypeDeclare -> {}
      is ImportDeclare -> {}
      is ConstantDeclare -> moduleScope[it.assign.name] = interpret(it.assign.body, moduleScope)
      is ProtocolDeclare -> TODO()
      is FunctionDeclare -> moduleScope[it.func.name] = it.func.body.makeJFunction(moduleScope)
      is ImplDeclare -> {
        val dataName = (it.base as NamedType).name
        val dataObj = moduleScope[dataName]!!
        val newFields = dataObj.fields + it.funcs.associate { fn -> fn.func.name to fn.func.body.makeJFunction(moduleScope) }
        moduleScope[dataName] = JObject(newFields)
      }
    }
  }

  val mainFun = moduleScope["main"].unwrap<(List<JObject?>) -> JObject?>()

  var tramp = Trampoline { mainFun(args.map { it.makeJString() }) }

  while (true) {
    val result = tramp.func()

    if (result.isTrampoline()) {
      tramp = result.unwrap()
    } else {
      return result.unwrap<List<JObject>>().map {
        val value = it.fields["value"] as JObject
        value.unwrap<String>()
      }.joinToString(" ")
    }
  }
}

private fun interpret(ex: Expression, scope: Scope): JObject? {
  return when (ex) {
    is NullLiteralExp -> null
    is BooleanLiteralExp -> ex.value.makeJBoolean()
    is NumberLiteralExp -> ex.value.toInt().makeJInt()
    is StringLiteralExp -> ex.value.makeJString()
    is CharLiteralExp -> ex.value.makeJChar()
    is ListLiteralExp -> ex.args.map { interpret(it, scope) }.makeJList()
    is IdentifierExp -> scope[ex.name]
    is BinaryOpExp -> {
      val left = interpret(ex.left, scope)

      if (ex.op == ".") {
        val key = (ex.right as IdentifierExp).name
        val maybe = left!!.fields[key]

        return maybe.makeJObject()
      }

      val right = interpret(ex.right, scope)

      when (ex.op) {
        "+" -> (left.unwrap<Int>() + right.unwrap<Int>()).makeJInt()
        "-" -> (left.unwrap<Int>() - right.unwrap<Int>()).makeJInt()
        "*" -> (left.unwrap<Int>() * right.unwrap<Int>()).makeJInt()
        "/" -> (left.unwrap<Int>() / right.unwrap<Int>()).makeJInt()
        ">" -> (left.unwrap<Int>() > right.unwrap<Int>()).makeJBoolean()
        ">=" -> (left.unwrap<Int>() >= right.unwrap<Int>()).makeJBoolean()
        "<" -> (left.unwrap<Int>() < right.unwrap<Int>()).makeJBoolean()
        "<=" -> (left.unwrap<Int>() <= right.unwrap<Int>()).makeJBoolean()
        "==" -> (left?.fields?.get("src") == right?.fields?.get("src")).makeJBoolean()
        "!=" -> (left?.fields?.get("src") != right?.fields?.get("src")).makeJBoolean()
        "&&" -> (left.unwrap<Boolean>() && right.unwrap<Boolean>()).makeJBoolean()
        "||" -> (left.unwrap<Boolean>() && right.unwrap<Boolean>()).makeJBoolean()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is UnaryOpExp -> {
      val body = interpret(ex.ex, scope)

      when (ex.op) {
        "-" -> (-body.unwrap<Int>()).makeJInt()
        "!" -> (!body.unwrap<Boolean>()).makeJBoolean()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is CallExp -> {
      val call = callCheck(ex)

      val func = interpret(call.func, scope).unwrap<(List<JObject?>) -> JObject?>()
      val args = call.arguments.map { interpret(it, scope) }

      val result = func(args)

      if (result.isTrampoline()) {
        result.unwrap<Trampoline>().func()
      } else {
        result
      }
    }
    is LambdaExp -> ex.makeJFunction(scope)
    is IfExp -> {
      val condition = interpret(ex.condition, scope).unwrap<Boolean>()

      if (condition) {
        interpret(ex.thenExp, scope)
      } else {
        if (ex.elseExp == null) {
          null
        } else {
          interpret(ex.elseExp, scope)
        }
      }
    }
    is ReturnExp -> {
      val body = ex.ex

      if (body is CallExp) {
        val call = callCheck(body)

        val func = interpret(call.func, scope).unwrap<(List<JObject?>) -> JObject?>()
        val args = call.arguments.map { interpret(it, scope) }

        throw ReturnException( Trampoline { func(args) }.makeJTrampoline() )
      }

      throw ReturnException(interpret(ex.ex, scope))
    }
    is ThrowExp -> throw interpret(ex.ex, scope).unwrap<Throwable>()
    is ConstructExp -> {
      val base = interpret(ex.base, scope)

      val values = ex.values.associate { (key, body) -> key to interpret(body, scope) }

      JObject(base!!.fields + values)
    }
    is ConstructTupleExp -> ex.values.map { interpret(it, scope) }.makeJList()
    is MatchExp -> {
      val base = interpret(ex.base, scope).unwrap<Any?>()

      ex.patterns.forEach { pattern ->
        val patternBase = interpret(pattern.base, scope).unwrap<Any?>()

        if (patternBase == Wildcard || patternBase == base) {
          if (pattern.guard == null) {
            return interpret(pattern.body, scope)
          } else {
            if (interpret(pattern.guard, scope).unwrap<Boolean>()) {
              return interpret(pattern.body, scope)
            }
          }
        }
      }

      ex.pos.fail("No pattern in match clause passed!")
    }
    is BlockExp -> {
      val local = scope.child()
      var result: JObject? = null

      ex.body.forEach { state ->
        result = when (state) {
          is ExpressionStatement -> interpret(state.ex, local)
          is AssignmentStatement -> {
            local[state.name] = interpret(state.body, local)
            null
          }
          is FunctionStatement -> {
            local[state.name] = state.body.makeJFunction(local)
            null
          }
          is TypeStatement, is ImportStatement -> null
          is DeconstructDataStatement -> {
            val values = interpret(state.base, local)

            state.values.forEach { (inside, outside) ->
              local[inside] = values?.fields?.get(outside).makeJObject()
            }

            null
          }
          is DeconstructTupleStatement -> {
            val values = interpret(state.base, local).unwrap<List<JObject?>>()

            state.names.zip(values).forEach { (name, value) ->
              local[name] = value
            }

            null
          }
        }
      }

      result
    }
  }
}

private fun callCheck(call: CallExp): CallExp {
  val fn = call.func

  return if (fn is BinaryOpExp && fn.op == ".") {
    call.copy(arguments = listOf(fn.left) + call.arguments)
  } else {
    call
  }
}

private object Wildcard

private data class JAtom(val name: String)
private data class Trampoline(val func: () -> JObject?)

data class JObject(val fields: Map<String, Any?>)

private inline fun <reified T> JObject?.unwrap(): T {
  return this!!.fields["src"] as T
}

private fun func(src: (List<JObject?>) -> JObject?) = src

private fun Any?.makeJObject(): JObject? {
  return when (this) {
    null -> null
    is JObject -> this
    else -> JObject(mapOf("src" to this))
  }
}

private val jError = JObject(mapOf(
  "new" to func { Exception( it[1].unwrap<String>()).makeJError() }
))

private fun String.makeJString(): JObject {
  return JObject(mapOf(
    "src" to this,
    "size" to this.length,
    "getCharAt" to func {
      val (str, index) = it
      str.unwrap<String>()[index.unwrap<Int>()].makeJChar()
    },
    "toUpperCase" to func {
      it[0].unwrap<String>().toUpperCase().makeJString()
    },
    "append" to func {
      val (left, right) = it
      (left.unwrap<String>() + right.unwrap<Any?>().toString()).makeJString()
    },
    "contains" to func {
      val (rawStr, rawChar) = it

      rawStr.unwrap<String>().contains(rawChar.unwrap<Char>()).makeJBoolean()
    }
  ))
}

private fun Exception.makeJError(): JObject {
  return JObject(mapOf(
    "src" to this
  ))
}

private fun Char.makeJChar(): JObject {
  return JObject(mapOf(
    "src" to this,
    "toString" to func {
      it[0].unwrap<Char>().toString().makeJString()
    }
  ))
}

private fun Boolean.makeJBoolean(): JObject {
  return JObject(mapOf("src" to this))
}

private fun Int.makeJInt(): JObject {
  return JObject(mapOf("src" to this))
}

private val jList = JObject(mapOf(
  "of" to func { emptyList<JObject>().makeJList() }
))

private fun List<JObject?>.makeJList(): JObject {
  return JObject(mapOf(
    "src" to this,
    "add" to func {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JObject?>>()
      (list + rawNew).makeJList()
    }
  ))
}

private fun Trampoline.makeJTrampoline(): JObject {
  return JObject(mapOf("src" to this, "@trampoline" to true))
}

fun JObject?.isTrampoline(): Boolean = this?.fields?.containsKey("@trampoline") ?: false

private fun LambdaExp.makeJFunction(context: Scope): JObject {
  val execute = { args: List<JObject?> ->
    val local = context.child()

    this.args.zip(args).forEach { (key, value) ->
      local[key] = value
    }

    try {
      interpret(this.body, local)
    } catch (e: ReturnException) {
      e.value
    }
  }

  return JObject(mapOf("src" to execute))
}

private fun JAtom.makeJAtom(): JObject {
  return JObject(mapOf("src" to this))
}

val templateFun = func {
  val (rawStrings, rawValues) = it

  val strings = rawStrings.unwrap<List<JObject?>>().map { it.unwrap<String>() }
  val values = rawValues.unwrap<List<JObject?>>().map { it.unwrap<Any?>().toString() }

  val result = strings.zip(values).flatMap { it.toList() }.joinToString("") + strings.last()

  result.makeJString()
}.makeJObject()

private fun initCoreScope(): Scope {
  return Scope(hashMapOf(
    "_" to Wildcard.makeJObject(),
    "Error" to jError,
    "List" to jList,
    "@template" to templateFun
  ), null)
}

