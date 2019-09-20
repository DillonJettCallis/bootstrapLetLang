
pub struct Application {

}




pub enum Instruction {
  Duplicate,
  Pop,
  Swap,
  LoadConstNull,
  LoadConstTrue,
  LoadConstFalse,
  LoadConstString,
  LoadConstFunction,
  LoadConstFloat,
  LoadValue,
  StoreValue,
  CallStatic,
  CallDynamic,
  BuildClosure,
  BuildRecursiveFunction,
  Return,
  Branch,
  Jump,
  Debug,
  Error
}

