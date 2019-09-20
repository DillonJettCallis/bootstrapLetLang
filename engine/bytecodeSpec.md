
# Application

There are two types of bytecode, Applications and Libraries. To create an application
a main library and it's dependencies are linked together ahead of time to create 
the application.

## App file spec

App files have the extension ".japp" and are organized like so

`<magic number><bytecode version><index><data types><functions><constants><meta data>`

`magic number` is a unique number to denote this is a .japp application

`bytcode version` tells the interpreter the version the compiler is targeting.
An interpreter cannot accept bytecode with a version greater than itself and 
can limit what versions lesser than itself it can accept. 

`index` is a series of u64 that point to each of the following sections 
in turn, thus allowing the interpreter to skip to any section by looking
up a constant offset from the beginning of the file, then skipping exactly 
the known number of bytes forward from there. 

`data types` contains information on all data types, mainly for debugging and 
exceptions.

`functions` contains the actual bytecode for functions, as well as each one's 
individual meta-data and source maps. 

`constants` contains all the constants used throughout, including meta data 
for the app, data types and functions. Constant IDs start at 0 and are mapped
internally to offsets within the constants pool

`meta data` contains extra information about the app, like the version of the 
linker (which may be different than the bytecode version), the main function,
timestamp of linking phase, list of all libraries and their versions, and other 
miscellaneous information about the whole app. It is essentially a Map of string
keys to any other constants. 

## Bytecode



