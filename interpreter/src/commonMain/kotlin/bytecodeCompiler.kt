sealed interface Bytecode

object Pop : Bytecode
object Dup : Bytecode
object Swap : Bytecode

object Add : Bytecode
object Subtract : Bytecode
object Multiply : Bytecode
object Divide : Bytecode
object GreaterThan : Bytecode
object GreaterThanOrEqualTo : Bytecode
object LessThan : Bytecode
object LessThanOrEqualTo : Bytecode
object EqualTo : Bytecode
object NotEqualTo : Bytecode
object Is : Bytecode
object IsNot : Bytecode
object Negate : Bytecode
object Not : Bytecode

object Return : Bytecode
object Throw : Bytecode

object Const: Bytecode

object PushScope : Bytecode
object PopScope : Bytecode

object Yield: Bytecode
object Send: Bytecode
object Receive: Bytecode
object Spawn: Bytecode

object Debug : Bytecode

data class LoadLiteral(val value: JValue) : Bytecode
data class LoadLocal(val name: String, val pos: Position) : Bytecode
data class StoreLocal(val name: String) : Bytecode
data class Call(override val args: Int) : Bytecode, Callable
data class TailCall(override val args: Int) : Bytecode, Callable
data class Access(val name: String, val pos: Position) : Bytecode
data class ListAccess(val index: Int) : Bytecode
data class BytecodeFunction(val args: List<String>, val body: List<Bytecode>, val pos: Position): Bytecode
data class Impl(val base: String, val name: String, val static: Boolean, val func: BytecodeFunction, val pos: Position) : Bytecode

sealed interface Callable {
  val args: Int
}

// NOT a data class because we actually want to use object identity. Name is just for debugging.
class Label: Bytecode

data class IfLabeled(val label: Label): Bytecode
data class JumpLabeled(val label: Label): Bytecode

data class If(val jumpIfFalse: Int) : Bytecode
data class Jump(val jump: Int) : Bytecode

fun compileModule(module: AstModule): Map<List<String>, BytecodeFunction> {
  return module.files.mapValues { (_, file) -> compileFile(file) }
}

fun compileFile(file: AstFile): BytecodeFunction {
  val body = ArrayList<Bytecode>()

  file.declarations.forEach {
    when (it) {
      is AtomDeclare -> {
        body += LoadLiteral(JAtom(it.name))
        body += StoreLocal(it.name)
      }
      is DataDeclare -> {
        body += LoadLiteral(JClass(it.name, it.body.keys, emptyMap(), emptyMap()))
        body += StoreLocal(it.name)
      }
      is EnumDeclare -> {
        body += LoadLiteral(JClass(it.name, emptySet(), emptyMap(), emptyMap()))
        body += StoreLocal(it.name)
      }
      is ImportDeclare -> {}
      is ConstantDeclare -> {
        body += run {
          val code = ArrayList<Bytecode>()

          compilePartial(it.assign.body, code)
          code += Return
          assemble(body)

          BytecodeFunction(emptyList(), code, it.pos)
        }
        body += Const
        body += StoreLocal(it.assign.name)
      }
      is ProtocolDeclare -> TODO("Protocols are not yet implemented")
      is FunctionDeclare -> {
        body += compileFunction(it.func.body)
        body += StoreLocal(it.func.name)
      }
      is ImplDeclare -> {
        val dataName = (it.base as NamedType).name

        it.funcs.forEach { fn ->
          val bytecode = compileFunction(fn.func.body)
          val static = fn.func.body.args.firstOrNull() != "this"

          body += Impl(dataName, fn.func.name, static, bytecode, it.pos)
        }
      }
    }
  }

  body += LoadLiteral(JNull)
  body += Return

  return BytecodeFunction(emptyList(), body, file.declarations.first().pos)
}

fun compileFunction(ex: LambdaExp): BytecodeFunction {
  val body = ArrayList<Bytecode>()

  compilePartial(ex.body, body)
  if (body.last() !is Return) {
    body += Return
  }
  assemble(body)

  return BytecodeFunction(ex.args, body, ex.pos)
}

fun assemble(body: MutableList<Bytecode>) {
  dropUselessLoads(body)
  delabel(body)
}

/**
 * If a load is followed by a pop, it's useless. Remove both
 */
private fun dropUselessLoads(partial: MutableList<Bytecode>) {

  val iter = partial.listIterator(partial.size)

  while (iter.hasPrevious()) {
    val next = iter.previous()

    if (next is Pop) {
      val prev = iter.previous()

      if (prev is LoadLiteral || prev is LoadLocal) {
        iter.next()
        iter.remove()
        iter.next()
        iter.remove()
      }
    }
  }

}

private fun delabel(partial: MutableList<Bytecode>) {
  val labels = HashMap<Label, Int>()

  // use iterator because we will modify as we go
  val backIter = partial.listIterator()

  while (backIter.hasNext()) {
    val item = backIter.next()

    if (item is Label) {
      labels[item] = backIter.previousIndex()
      backIter.remove()
    }
  }

  // now replace IfLabeled and JumpLabeled with If and Jump based on their target's positions

  val iter = partial.listIterator()

  while (iter.hasNext()) {
    when (val next = iter.next()) {
      is IfLabeled -> {
        val index = labels[next.label]
          ?: throw IllegalStateException("IfLabeled with label not found.")

        iter.remove()
        iter.add(If(index - iter.nextIndex() - 1))
      }
      is JumpLabeled -> {
        val index = labels[next.label]
          ?: throw IllegalStateException("IfLabeled with label not found.")

        iter.remove()
        iter.add(Jump(index - iter.nextIndex() - 1))
      }
      else -> {}
    }
  }

}

private fun compileIf(thenBlock: List<Bytecode>, elseBlock: List<Bytecode>, partial: MutableList<Bytecode>) {
  val elseLabel = Label()
  val endIfLabel = Label()

  partial += IfLabeled(elseLabel)
  partial += thenBlock

  if (elseBlock.isNotEmpty()) {
    partial += JumpLabeled(endIfLabel)
    partial += elseLabel
    partial += elseBlock
  } else {
    partial += elseLabel
  }

  partial += endIfLabel
}

private fun compilePartial(ex: Expression, partial: MutableList<Bytecode>) {
  when (ex) {
    is NullLiteralExp -> partial += LoadLiteral(JNull)
    is BooleanLiteralExp -> partial += LoadLiteral(ex.value.wrap())
    is NumberLiteralExp -> partial += LoadLiteral(ex.value.toInt().wrap())
    is StringLiteralExp -> partial += LoadLiteral(ex.value.wrap())
    is CharLiteralExp -> partial += LoadLiteral(ex.value.wrap())
    is ListLiteralExp -> {
      // load up the function
      partial += LoadLocal("List", ex.pos)
      partial += Dup
      partial += Access("of", ex.pos)
      partial += Swap
      // put all args on the stack
      ex.args.forEach {
        compilePartial(it, partial)
      }
      // call it
      partial += Call(ex.args.size + 1)
    }
    is IdentifierExp -> partial += LoadLocal(ex.name, ex.pos)
    is BinaryOpExp -> {
      // always compile the left side
      compilePartial(ex.left, partial)

      when (ex.op) {
        "." -> {

          if (ex.right !is IdentifierExp) {
            println("uh oh")
          }

          val key = (ex.right as IdentifierExp).name

          partial += Access(key, ex.pos)
        }
        "&&" -> {
          val thenBlock = ArrayList<Bytecode>()

          compilePartial(ex.right, thenBlock)

          compileIf(
            thenBlock,
            listOf(LoadLiteral(false.wrap())),
            partial
          )
        }
        "||" -> {
          val elseBlock = ArrayList<Bytecode>()

          compilePartial(ex.right, elseBlock)

          compileIf(
            listOf(LoadLiteral(true.wrap())),
            elseBlock,
            partial
          )
        }
        else -> {
          // for the rest compile the right side as well
          compilePartial(ex.right, partial)

          when (ex.op) {
            "is" -> partial += Is
            "isNot" -> partial += IsNot
            "+" -> partial += Add
            "-" -> partial += Subtract
            "*" -> partial += Multiply
            "/" -> partial += Divide
            ">" -> partial += GreaterThan
            ">=" -> partial += GreaterThanOrEqualTo
            "<" -> partial += LessThan
            "<=" -> partial += LessThanOrEqualTo
            "==" -> partial += EqualTo
            "!=" -> partial += NotEqualTo
            else -> ex.pos.fail("Unknown binary operator ${ex.op}")
          }
        }
      }
    }
    is UnaryOpExp -> {
      compilePartial(ex.ex, partial)

      partial += when (ex.op) {
        "-" -> Negate
        "!" -> Not
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is CallExp -> {
      val func = ex.func

      if (func is BinaryOpExp && func.op == ".") {
        // a method
        val nameEx = func.right

        if (nameEx !is IdentifierExp) {
          nameEx.pos.fail("Expected identifier after .")
        }

        // load up object
        compilePartial(func.left, partial)

        // get the function, then swap it so the value is on top
        partial += Dup
        partial += Access(nameEx.name, nameEx.pos)
        partial += Swap

        // load up other args
        ex.arguments.forEach {
          compilePartial(it, partial)
        }

        // call with 1 extra arg, that's this
        partial += Call(ex.arguments.size + 1)
      } else {
        // a non method function

        // load up the function
        compilePartial(func, partial)

        // load up other args
        ex.arguments.forEach {
          compilePartial(it, partial)
        }

        // call
        partial += Call(ex.arguments.size)
      }

    }
    is LambdaExp -> {
      partial += compileFunction(ex)
    }
    is IfExp -> {
      // condition
      compilePartial(ex.condition, partial)

      val thenBlock = ArrayList<Bytecode>()
      compilePartial(ex.thenExp, thenBlock)

      val elseBlock = if (ex.elseExp == null) {
        listOf(LoadLiteral(JNull))
      } else {
        val code = ArrayList<Bytecode>()
        compilePartial(ex.elseExp, code)
        code
      }

      compileIf(thenBlock, elseBlock, partial)
    }
    is ReturnExp -> {
      compilePartial(ex.ex, partial)

      val lastCode = partial.last()

      // detect tail call
      if (lastCode is Call) {
        partial.removeLast()
        partial += TailCall(lastCode.args)
      } else {
        partial += Return
      }
    }
    is ThrowExp -> {
      compilePartial(ex.ex, partial)
      partial += Throw
    }
    is ConstructExp -> {
      // load the function
      partial += LoadLocal("@construct", ex.pos)

      // load the base
      compilePartial(ex.base, partial)

      // load the keys and values
      ex.values.forEach { (key, value) ->
        partial += LoadLiteral(key.wrap())
        compilePartial(value, partial)
      }

      // one for the base, then two for every key to value pair
      partial += Call(1 + (ex.values.size * 2))
    }
    is ConstructTupleExp -> {
      // tuples are actually lists, so just copy the list creation

      // load up the function
      partial += LoadLocal("List", ex.pos)
      partial += Dup
      partial += Access("of", ex.pos)
      partial += Swap

      // put all args on the stack
      ex.values.forEach {
        compilePartial(it, partial)
      }

      // call it
      partial += Call(ex.values.size + 1)
    }
    is MatchExp -> {
      // turn match into many nested ifs

      val endLabel = Label()

      // put the base on the stack
      compilePartial(ex.base, partial)

      // compile all patterns
      ex.patterns.forEach { pattern ->
        val endOfPatternLabel = Label()

        // dupe the base
        partial += Dup

        // add the pattern base
        compilePartial(pattern.base, partial)

        // are the equal? (equal to also handles wildcard)
        partial += EqualTo

        // if base check fails, skip this pattern
        partial += IfLabeled(endOfPatternLabel)

        // compile the guard if there is one
        if (pattern.guard != null) {
          // run the guard
          compilePartial(pattern.guard, partial)

          // if guard fails then skip to the end of pattern
          partial += IfLabeled(endOfPatternLabel)
        }

        // remove the base object, we found a match
        partial += Pop

        // compile body
        compilePartial(pattern.body, partial)

        // jump to the bottom of the match
        partial += JumpLabeled(endLabel)

        // pattern ends here
        partial += endOfPatternLabel
      }

      // no match was found. For now, just pop the base object and load a null instead
      partial += Pop
      partial += LoadLiteral(JNull)

      // this is where the match block ends
      partial += endLabel
    }
    is BlockExp -> {
      partial += PushScope

      ex.body.forEach { state ->
        when (state) {
          is ExpressionStatement -> compilePartial(state.ex, partial)
          is AssignmentStatement -> {
            compilePartial(state.body, partial)
            partial += StoreLocal(state.name)
            partial += LoadLiteral(JNull)
          }
          is FunctionStatement -> {
            compilePartial(state.body, partial)
            partial += StoreLocal(state.name)
            partial += LoadLiteral(JNull)
          }
          is DeconstructDataStatement -> {
            // load the object
            compilePartial(state.base, partial)

            // pluck out each value
            state.values.forEach { (inner, outer) ->
              // dup the base object
              partial += Dup
              // get the field
              partial += Access(inner, state.pos)
              // store it in a local
              partial += StoreLocal(outer)
            }

            // remove base object from stack, it is no longer needed
            partial += Pop
            partial += LoadLiteral(JNull)
          }
          is DeconstructTupleStatement -> {
            // load the tuple
            compilePartial(state.base, partial)

            // pluck out each value
            state.names.forEachIndexed { index, name ->
              // dup the base object
              partial += Dup
              // get the field
              partial += ListAccess(index)
              // store it in a local
              partial += StoreLocal(name)
            }

            // remove tuple from stack, it is no longer needed
            partial += Pop
            partial += LoadLiteral(JNull)
          }
          is DebuggerStatement -> {
            partial += Debug
            partial += LoadLiteral(JNull)
          }
        }

        // remove the stack item
        partial += Pop
      }

      // remove the last pop. The final value is the result of this block
      partial.removeLast()

      // pop the block scope
      partial += PopScope
    }
  }
}




