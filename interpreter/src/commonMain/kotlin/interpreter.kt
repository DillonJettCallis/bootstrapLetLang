private class Scope(val values: MutableMap<String, JValue>, val parent: Scope?) {

  operator fun get(key: String): JValue {
    return this.values.getOrElse(key) { if (parent != null) parent[key] else throw Exception("No such value $key in scope") }
  }

  operator fun set(key: String, value: JValue) {
    this.values[key] = value
  }

  fun child(): Scope {
    return Scope(HashMap(), this)
  }

}

private class ReturnException(val value: JValue): RuntimeException()

fun executePackage(pack: AstPackage, args: List<String>): Any? {
  val core = initCoreScope()

  pack.modules.forEach { (path, mod) ->
    core[path.joinToString(".")] = buildScope(mod, core).wrap()
  }

  pack.modules.forEach { (path, mod) ->
    val thisScope = core[path.joinToString(".")].unwrap<Scope>()

    mod.declarations.filterIsInstance<ImportDeclare>().forEach {
      val (packageName, modulePath) = it.import

      // TODO: For now we assume only internal imports. All core libs are auto imported

      val init = modulePath.dropLast(1).joinToString(".")
      val value = modulePath.last()

      thisScope[value] = core[init].unwrap<Scope>()[value]
    }
  }

  val mainFun = core["App"].unwrap<Scope>()["main"] as JFunction

  return executeMain(mainFun, args)
}

private fun buildScope(module: AstModule, core: Scope): Scope {
  val moduleScope = core.child()

  module.declarations.forEach {
    when (it) {
      is AtomDeclare -> moduleScope[it.name] = JAtom(it.name)
      is DataDeclare -> moduleScope[it.name] = JClass(it.name, emptyMap(), emptyMap())
      is TypeDeclare -> {}
      is ImportDeclare -> {}
      is ConstantDeclare -> moduleScope[it.assign.name] = interpret(it.assign.body, moduleScope)
      is ProtocolDeclare -> TODO()
      is FunctionDeclare -> moduleScope[it.func.name] = it.func.body.makeJFunction(moduleScope)
      is ImplDeclare -> {
        val dataName = (it.base as NamedType).name
        val dataObj = moduleScope[dataName] as JClass
        val methods = dataObj.instanceMethods + it.funcs.associate { fn -> fn.func.name to fn.func.body.makeJFunction(moduleScope) }
        moduleScope[dataName] = dataObj.copy(instanceMethods = methods)
      }
    }
  }

  return moduleScope
}

private fun executeMain(mainFun: JFunction, args: List<String>) {
  var tramp = mainFun.trampoline( listOf(args.map { it.wrap() }.wrap()) )

  while (true) {
    val result = tramp()

    if (result is JTrampoline) {
      tramp = result
    } else {
      return
    }
  }
}

private fun interpret(ex: Expression, scope: Scope): JValue {
  return when (ex) {
    is NullLiteralExp -> JNull
    is BooleanLiteralExp -> ex.value.wrap()
    is NumberLiteralExp -> ex.value.toInt().wrap()
    is StringLiteralExp -> ex.value.wrap()
    is CharLiteralExp -> ex.value.wrap()
    is ListLiteralExp -> ex.args.map { interpret(it, scope) }.wrap()
    is IdentifierExp -> scope[ex.name]
    is BinaryOpExp -> {
      val left = interpret(ex.left, scope)

      if (ex.op == ".") {
        val key = (ex.right as IdentifierExp).name

        if (left is JObject) {
          when {
            left.fields.containsKey(key) -> return left.fields.getValue(key).wrap()
            left.jClass.instanceMethods.containsKey(key) -> return left.jClass.instanceMethods.getValue(key)
            else -> ex.pos.fail("Attempt to access property '$key' that does not exist on object '$left'")
          }
        }

        if (left is JClass) {
          if (left.staticMethods.containsKey(key)) {
            return left.staticMethods.getValue(key)
          } else {
            ex.pos.fail("Attempt to access static that does not exist")
          }
        }

        ex.pos.fail("Attempt to access when value was not an object or a class")
      }

      val right = interpret(ex.right, scope)

      when (ex.op) {
        "+" -> (left.unwrap<Int>() + right.unwrap<Int>()).wrap()
        "-" -> (left.unwrap<Int>() - right.unwrap<Int>()).wrap()
        "*" -> (left.unwrap<Int>() * right.unwrap<Int>()).wrap()
        "/" -> (left.unwrap<Int>() / right.unwrap<Int>()).wrap()
        ">" -> (left.unwrap<Int>() > right.unwrap<Int>()).wrap()
        ">=" -> (left.unwrap<Int>() >= right.unwrap<Int>()).wrap()
        "<" -> (left.unwrap<Int>() < right.unwrap<Int>()).wrap()
        "<=" -> (left.unwrap<Int>() <= right.unwrap<Int>()).wrap()
        "==" -> (left == right).wrap()
        "!=" -> (left != right).wrap()
        "&&" -> (left.unwrap<Boolean>() && right.unwrap<Boolean>()).wrap()
        "||" -> (left.unwrap<Boolean>() && right.unwrap<Boolean>()).wrap()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is UnaryOpExp -> {
      val body = interpret(ex.ex, scope)

      when (ex.op) {
        "-" -> (-body.unwrap<Int>()).wrap()
        "!" -> (!body.unwrap<Boolean>()).wrap()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is CallExp -> {
      val call = callCheck(ex)

      val func = interpret(call.func, scope) as JFunction
      val args = call.arguments.map { interpret(it, scope) }

      val result = func(args)

      if (result is JTrampoline) {
        result()
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
          JNull
        } else {
          interpret(ex.elseExp, scope)
        }
      }
    }
    is ReturnExp -> {
      val body = ex.ex

      if (body is CallExp) {
        val call = callCheck(body)

        val func = interpret(call.func, scope) as JFunction
        val args = call.arguments.map { interpret(it, scope) }

        throw ReturnException(func.trampoline(args))
      }

      throw ReturnException(interpret(ex.ex, scope))
    }
    is ThrowExp -> throw interpret(ex.ex, scope).unwrap<Throwable>()
    is ConstructExp -> {
      val base = interpret(ex.base, scope)
      val fields = ex.values.associate { (key, body) -> key to interpret(body, scope) }

      if (base is JClass) {
        return JObject(fields, base)
      }

      if (base is JObject) {
        return JObject(base.fields + fields, base.jClass)
      }

      ex.base.pos.fail("Construct on something that was neither a class nor an object")
    }
    is ConstructTupleExp -> ex.values.map { interpret(it, scope) }.wrap()
    is MatchExp -> {
      val base = interpret(ex.base, scope).unwrap<Any?>()

      ex.patterns.forEach { pattern ->
        val raw = interpret(pattern.base, scope)
        val patternBase = raw.unwrap<Any?>()

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
      var result: JValue = JNull

      ex.body.forEach { state ->
        result = when (state) {
          is ExpressionStatement -> interpret(state.ex, local)
          is AssignmentStatement -> {
            local[state.name] = interpret(state.body, local)
            JNull
          }
          is FunctionStatement -> {
            local[state.name] = state.body.makeJFunction(local)
            JNull
          }
          is TypeStatement, is ImportStatement -> JNull
          is DeconstructDataStatement -> {
            val values = interpret(state.base, local) as JObject

            state.values.forEach { (inside, outside) ->
              local[inside] = values.fields.getValue(outside).wrap()
            }

            JNull
          }
          is DeconstructTupleStatement -> {
            val values = interpret(state.base, local).unwrap<List<JValue>>()

            state.names.zip(values).forEach { (name, value) ->
              local[name] = value
            }

            JNull
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

sealed class JValue

object JNull: JValue() { override fun toString() = "null" }
object Wildcard: JValue() { override fun toString() = "_" }
data class JAtom(val name: String): JValue() { override fun toString() = name }
data class JTrampoline(val func: () -> JValue): JValue() {
  override fun toString() = "<Function>"
  operator fun invoke(): JValue = func()
}
data class JClass(val name: String, val instanceMethods: Map<String, JFunction>, val staticMethods: Map<String, JFunction>): JValue() {
  override fun toString() = name
}
data class JObject(val fields: Map<String, Any?>, val jClass: JClass): JValue() {
  override fun toString(): String {
    return if (jClass.instanceMethods.containsKey("toString")) {
      (jClass.instanceMethods["toString"] as JFunction)(listOf(this)).unwrap()
    } else {
      fields.entries.joinToString(prefix = "${jClass.name} {", postfix = "}") { "${it.key}: ${it.value}" }
    }
  }
}
data class JFunction(val func: (List<JValue>) -> JValue): JValue() {
  override fun toString() = "<Function>"
  operator fun invoke(args: List<JValue>): JValue = func(args)
  fun trampoline(args: List<JValue>) = JTrampoline { func(args) }
}


private inline fun <reified T> JValue.unwrap(): T {
  return when (this) {
    is JObject -> fields["@src"] as T
    is JNull -> null as T
    is Wildcard -> Wildcard as T
    else -> throw RuntimeException("Invalid cast")
  }
}

private val jAnyClass = JClass("Any", emptyMap(), emptyMap())

private fun Any?.wrap(): JValue {
  return if (this == null) {
    JNull
  } else {
    val clazz = when (this) {
      is JObject -> return this
      is String -> {
        return JObject(mapOf("@src" to this, "size" to this.length), jString)
      }
      is Char -> jChar
      is Exception -> jError
      is List<*> -> jList
      is Map<*, *> -> jMap
      is FileImpl -> jFile
      else -> jAnyClass
    }

    JObject(mapOf("@src" to this), clazz)
  }
}

private val jError = JClass("Error", emptyMap(), mapOf("new" to JFunction { Exception( it[1].unwrap<String>()).wrap() }))

private val jString = JClass("String", mapOf(
    "getCharAt" to JFunction {
      val (str, index) = it
      str.unwrap<String>()[index.unwrap<Int>()].wrap()
    },
    "toUpperCase" to JFunction {
      it[0].unwrap<String>().toUpperCase().wrap()
    },
    "append" to JFunction {
      val (left, right) = it
      (left.unwrap<String>() + right.unwrap<Any?>().toString()).wrap()
    },
    "contains" to JFunction {
      val (rawStr, rawChar) = it

      rawStr.unwrap<String>().contains(rawChar.unwrap<Char>()).wrap()
    },
  "toString" to JFunction { it[0] }
  ), mapOf()
)

private val jChar = JClass(
  name = "Char",
  instanceMethods = mapOf(
    "toString" to JFunction { it[0].unwrap<Char>().toString().wrap() }
  ),
  staticMethods = mapOf()
)

private val jList = JClass(
  name = "List",
  staticMethods =  mapOf(
    "of" to JFunction { emptyList<JValue>().wrap() }
  ),
  instanceMethods = mapOf(
    "add" to JFunction {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JValue>>()
      (list + rawNew).wrap()
    },
    "get" to JFunction {
      val (rawList, rawIndex) = it
      val list = rawList.unwrap<List<JValue>>()
      val index = rawIndex.unwrap<Int>()
      list[index]
    },
    "filter" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.filter { item ->
        func(listOf(item)).unwrap<Boolean>()
      }.wrap()
    },
    "map" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.map { item ->
        func(listOf(item))
      }.wrap()
    },
    "fold" to JFunction {
      val (rawList, rawInit, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.fold(rawInit) { sum, next -> func(listOf(sum, next)) }
    },
    "forEach" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.forEach { item ->
        func(listOf(item))
      }

      JNull
    }
  )
)

private val jMap = JClass(
  name = "Map",
  instanceMethods = mapOf(
    "of" to JFunction { emptyMap<JValue, JValue>().wrap() }
  ),
  staticMethods = mapOf(
    "set" to JFunction { args ->
      val (rawMap, rawKey, rawValue) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      (map + (rawKey to rawValue)).wrap()
    },
    "get" to JFunction { args ->
      val (rawMap, rawKey) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      map[rawKey] ?: JNull
    }
  )
)

private val jFile = JClass(
  name = "File",
  staticMethods = mapOf(
    "from" to JFunction {
      val path = it[1].unwrap<String>()
      FileImpl(path).wrap()
    }
  ),
  instanceMethods = mapOf(
    "walkFiles" to JFunction { args -> args[0].unwrap<FileImpl>().walkFiles().map { it.wrap() }.wrap() },
    "readText" to JFunction { it[0].unwrap<FileImpl>().readText().wrap() },
    "path" to JFunction { it[0].unwrap<FileImpl>().path.wrap() },
    "extension" to JFunction { it[0].unwrap<FileImpl>().extension().wrap() }
  )
)

private fun LambdaExp.makeJFunction(context: Scope): JFunction {
  return JFunction { args: List<JValue> ->
    val local = context.child()

    this.args.zip(args).forEach { (key, value) ->
      local[key] = value
    }

    var result: JValue = try {
      interpret(this.body, local)
    } catch (e: ReturnException) {
      e.value
    }

    while (result is JTrampoline) {
      result = result()
    }

    result
  }
}

val templateFun = JFunction { args ->
  val (rawStrings, rawValues) = args

  val strings = rawStrings.unwrap<List<JValue>>().map { it.unwrap<String>() }
  val values = rawValues.unwrap<List<JValue>>().map { it.toString() }

  val result = StringBuilder()

  for (i in values.indices) {
    result.append(strings[i])
    result.append(values[i])
  }

  result.append(strings.last())

  result.toString().wrap()
}

val jPrintln = JFunction { args ->
  println(args.joinToString(" "){ it.toString() })
  JNull
}

private fun initCoreScope(): Scope {
  return Scope(hashMapOf(
    "_" to Wildcard,
    "Error" to jError,
    "List" to jList,
    "Map" to jMap,
    "File" to jFile,
    "println" to jPrintln,
    "@template" to templateFun
  ), null)
}

