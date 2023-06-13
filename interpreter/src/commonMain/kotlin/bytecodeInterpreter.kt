
class Actor(var frame: StackFrame?, var isComplete: Boolean = false)
class StackFrame(var pc: Int, var stack: ArrayDeque<JValue>, var scope: Scope, var block: List<Bytecode>, var parent: StackFrame? = null) {

  companion object {
    fun call(func: JBytecodeFunction, args: List<JValue> = emptyList(), parent: StackFrame? = null): StackFrame {
      val scope = func.scope.child()

      scope.setAll(func.func.args, args)

      return StackFrame(0, ArrayDeque(), scope, func.func.body, parent)
    }
  }

}


sealed interface ExecutionStatus

object Suspended: ExecutionStatus
object Yielded: ExecutionStatus
data class Complete(val result: JValue): ExecutionStatus

class Runtime {

  private val actorQueue = ArrayDeque<Actor>()

  fun executeModule(pack: AstModule, args: List<String>) {
    val compiled = compileModule(pack)

    val core = initCoreScope()

    compiled.forEach { (path, mod) ->
      core[path.joinToString(".")] = buildScope(mod, core).wrap()
    }

    val dummyPos = Position(0, 0, "<root>")

    pack.files.forEach { (path, mod) ->
      val thisScope = core[path.joinToString("."), dummyPos].unwrap<Scope>()

      mod.declarations.filterIsInstance<ImportDeclare>().forEach {
        // TODO: For now we assume only internal imports. All core libs are auto imported

        val init = it.path.dropLast(1).joinToString(".")
        val value = it.path.last()

        thisScope[value] = core[init, it.pos].unwrap<Scope>()[value, it.pos]
      }
    }

    val mainFun = core["main", dummyPos].unwrap<Scope>()["main", dummyPos] as JBytecodeFunction

    executeMain(mainFun, args)
  }

  private fun buildScope(func: BytecodeFunction, core: Scope): Scope {
    val moduleScope = core.child()

    val initActor = Actor(
      StackFrame(0, ArrayDeque(), moduleScope, func.body)
    )

    val result = execute(initActor)

    if (result !is Complete) {
      throw IllegalStateException("Module initialization should never suspend!")
    }

    return moduleScope
  }

  private fun executeMain(main: JBytecodeFunction, args: List<String>) {
    val localScope = main.scope.child()

    localScope.setAll(main.func.args, listOf(args.map { it.wrap() }.wrap()))

    actorQueue += Actor(StackFrame(0, ArrayDeque(), localScope, main.func.body))

    run()
  }

  private fun run() {
    while (actorQueue.isNotEmpty()) {
      val next = actorQueue.removeFirst()
      execute(next)
    }
  }

  private fun execute(actor: Actor): ExecutionStatus {
    if (actor.isComplete) {
      throw IllegalStateException("Cannot run actor that is already completed")
    }

    var frame = actor.frame ?: throw IllegalStateException("Cannot run actor that has no frame")

    while (frame.pc < frame.block.size) {
      when (val next = frame.block[frame.pc++]) {
        Pop -> frame.stack.removeLast()
        Dup -> {
          val v = frame.stack.removeLast()
          frame.stack.addLast(v)
          frame.stack.addLast(v)
        }

        Swap -> {
          val first = frame.stack.removeLast()
          val second = frame.stack.removeLast()
          frame.stack.addLast(first)
          frame.stack.addLast(second)
        }

        Add -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() + right.unwrap<Int>()).wrap())
        }

        Subtract -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() - right.unwrap<Int>()).wrap())
        }

        Multiply -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() * right.unwrap<Int>()).wrap())
        }

        Divide -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() / right.unwrap<Int>()).wrap())
        }

        GreaterThan -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() > right.unwrap<Int>()).wrap())
        }

        GreaterThanOrEqualTo -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() >= right.unwrap<Int>()).wrap())
        }

        LessThan -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() < right.unwrap<Int>()).wrap())
        }

        LessThanOrEqualTo -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast((left.unwrap<Int>() <= right.unwrap<Int>()).wrap())
        }

        EqualTo -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()

          if (right is Wildcard || left is Wildcard) {
            frame.stack.addLast(true.wrap())
          } else {
            frame.stack.addLast((left == right).wrap())
          }
        }

        NotEqualTo -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()

          if (right is Wildcard || left is Wildcard) {
            frame.stack.addLast(false.wrap())
          } else {
            frame.stack.addLast((left != right).wrap())
          }
        }

        Is -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast(
            if (left == JNull) {
              false.wrap()
            } else if (right is JAtom) {
              (left == right).wrap()
            } else {
              ((left as JObject).jClass == (right as JClass)).wrap()
            }
          )
        }

        IsNot -> {
          val right = frame.stack.removeLast()
          val left = frame.stack.removeLast()
          frame.stack.addLast(
            if (right is JAtom) {
              (left != right).wrap()
            } else {
              ((left as JObject).jClass != (right as JClass)).wrap()
            }
          )
        }

        Negate -> {
          val v = frame.stack.removeLast().unwrap<Int>()
          frame.stack.addLast((-v).wrap())
        }

        Not -> {
          val v = frame.stack.removeLast().unwrap<Boolean>()
          frame.stack.addLast((!v).wrap())
        }

        Return -> {
          // take the last value off the stack
          val returnValue = frame.stack.removeLast()

          val parentFrame = frame.parent

          if (parentFrame == null) {
            // Actor is complete
            actor.isComplete = true
            actor.frame = null
            return Complete(returnValue)
          } else {
            // swap current frame with the parent frame
            frame = parentFrame
            actor.frame = parentFrame

            // add return value back to the return frame. Continue running that parent frame.
            frame.stack.addLast(returnValue)
          }
        }

        Throw -> {
          // for the moment all exceptions are fatal errors with no error handling
          throw frame.stack.removeLast().unwrap<Throwable>()
        }
        Const -> {
          val func = frame.stack.removeLast() as JBytecodeFunction
          frame.stack.addLast(JConst(func))
        }
        PushScope -> {
          // increment scope
          frame.scope = frame.scope.child()
        }

        PopScope -> {
          frame.scope = frame.scope.parent ?: throw IllegalStateException("Cannot pop root scope!")
        }
        Yield -> {
          // add yourself to the back of the queue
          actorQueue += actor
          return Yielded
        }
        Send -> {
          val item = frame.stack.removeLast()
          val chan = frame.stack.removeLast().unwrap<SendChannel>()

          val sent = chan.impl.send(item) {
            // only called if we had to wait
            actorQueue += actor
          }

          if (!sent) {
            return Suspended
          }
        }
        Receive -> {
          val chan = frame.stack.removeLast().unwrap<ReceiveChannel>()

          val receivedValue = chan.impl.receive {
            // this is called only if we had to wait
            frame.stack.addLast(it)
            actorQueue += actor
          }

          if (receivedValue == null) {
            // no value was returned, wait until it is
            return Suspended
          } else {
            // we got a value, continue
            frame.stack.addLast(receivedValue)
          }
        }
        Spawn -> {
          when (val func = frame.stack.removeLast() as JFunction) {
            is JNativeFunction -> {
              // just call it
              func(emptyList())
            }
            is JBytecodeFunction -> {
              actorQueue += Actor(
                StackFrame(0, ArrayDeque(), func.scope.child(), func.func.body)
              )
            }
          }
        }
        Debug -> {
          println("Debug point here")
        }

        is LoadLiteral -> {
          frame.stack.addLast(next.value)
        }

        is LoadLocal -> {
          val raw = frame.scope[next.name, next.pos]

          // deal with constants and their lazy evaluation
          val final = if (raw is JConst) {
            when (val temp = raw.value) {
              is JConst.Lazy -> {
                val constActor = Actor(StackFrame.call(temp.base))

                val result = execute(constActor)

                if (result !is Complete) {
                  throw IllegalStateException("A constant cannot suspend!")
                }

                raw.value = JConst.Actual(result.result)
                result.result
              }
              is JConst.Actual -> {
                temp.value
              }
            }
          } else {
            raw
          }

          frame.stack.addLast(final)
        }

        is StoreLocal -> {
          frame.scope[next.name] = frame.stack.removeLast()
        }

        is Callable -> {
          // store args from last to first
          val args = ArrayList<JValue>(next.args)

          for (arg in 1..next.args) {
            args += frame.stack.removeLast()
          }

          // reverse to that it's first to last again
          args.reverse()

          when (val func = frame.stack.removeLast()) {
            is JNativeFunction -> {
              // native function, nothing to do but call it
              val result = func(args)

              if (next is TailCall) {
                // oh boy, things are about to get complicated. We basically need to copy the logic from "Return" here
                // in order to replicate what a TailCall means: Call then Return the result
                // this would be easy except for the case of a tail call right from the root
                // (which I actually expect to be pretty common, so it can't just be ignored).

                val parentFrame = frame.parent

                if (parentFrame == null) {
                  // Actor is complete
                  actor.isComplete = true
                  actor.frame = null
                  return Complete(result)
                } else {
                  // swap current frame with the parent frame
                  frame = parentFrame
                  actor.frame = parentFrame
                }
              }

              // add the result onto the stack
              // if we were a tail call then this is the parent's stack, otherwise it's ourselves like normal
              frame.stack.addLast(result)
            }

            is JBytecodeFunction -> {
              // bytecode function: go down a layer of stack frame
              val newFrame = StackFrame.call(func, args, if (next is TailCall) frame.parent else frame)

              actor.frame = newFrame
              frame = newFrame
            }

            else -> {
              throw IllegalStateException("Attempt to call a non-function")
            }
          }
        }

        is Access -> {
          val key = next.name
          val left = frame.stack.removeLast()

          val result = if (left is JObject) {
            when {
              left.fields.containsKey(key) -> left.fields.getValue(key).wrap()
              left.jClass.instanceMethods.containsKey(key) -> left.jClass.instanceMethods.getValue(key)
              else -> next.pos.fail("Attempt to access property '$key' that does not exist on object '$left'")
            }
          } else if (left is JClass) {
            if (left.staticMethods.containsKey(key)) {
              left.staticMethods.getValue(key)
            } else {
              next.pos.fail("Attempt to access static that does not exist")
            }
          } else {
            next.pos.fail("Attempt to access when value was not an object or a class")
          }

          frame.stack.addLast(result)
        }

        is ListAccess -> {
          val list = frame.stack.removeLast().unwrap<List<JValue>>()
          val item = list[next.index]
          frame.stack.addLast(item)
        }

        is BytecodeFunction -> {
          frame.stack.addLast(JBytecodeFunction(next, frame.scope))
        }

        is Impl -> {
          val clazz = frame.scope[next.base, next.pos] as JClass

          val modified = if (next.static) {
            clazz.copy(
              staticMethods = clazz.staticMethods + mapOf(
                next.name to JBytecodeFunction(
                  next.func,
                  frame.scope
                )
              )
            )
          } else {
            clazz.copy(
              instanceMethods = clazz.instanceMethods + mapOf(
                next.name to JBytecodeFunction(
                  next.func,
                  frame.scope
                )
              )
            )
          }

          frame.scope[next.base] = modified
        }

        is Label, is IfLabeled, is JumpLabeled -> {
          throw IllegalStateException("Labels should have been removed before this point!")
        }

        is If -> {
          val test = frame.stack.removeLast().unwrap<Boolean>()

          if (!test) {
            frame.pc += next.jumpIfFalse
          }
        }

        is Jump -> {
          frame.pc += next.jump
        }
      }
    }

    throw IllegalStateException("Program Counter overflow! This is a compiler error!")
  }
}
