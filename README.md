[![progress-banner](https://backend.codecrafters.io/progress/shell/b02fa91d-6f37-4c12-aa3a-cef223a7fce5)](https://app.codecrafters.io/users/drishti4882?r=2qF)

This is a starting point for Java solutions to the
["Build Your Own Shell" Challenge](https://app.codecrafters.io/courses/shell/overview).

In this challenge, you'll build your own POSIX compliant shell that's capable of
interpreting shell commands, running external programs and builtin commands like
cd, pwd, echo and more. Along the way, you'll learn about shell command parsing,
REPLs, builtin commands, and more.

**Note**: If you're viewing this repo on GitHub, head over to
[codecrafters.io](https://codecrafters.io) to try the challenge.


# Build Your Own Shell (Java)

A small Unix-style shell written in Java, built as part of the [CodeCrafters "Build Your Own Shell" challenge](https://codecrafters.io/challenges/shell). It reads commands in a loop, runs built-ins itself, and launches external programs as child processes. Developed and tested on Ubuntu (WSL).

```
$ echo hello world
hello world
$ type echo
echo is a shell builtin
$ cd /tmp
$ pwd
/tmp
$ echo hello > test.txt
$ echo world >> test.txt
$ cat test.txt
hello
world
```

## Features

- **REPL loop:** prints a `$` prompt, reads a command, executes it, and repeats
- **Built-in commands:** `echo`, `pwd`, `cd`, `type`, `exit`
- **`cd` support:** absolute paths, plus `cd` with no argument to go to the home directory
- **External programs:** runs any executable found on `PATH` (for example `ls`, `ls -l`, `cat`) using `ProcessBuilder`
- **Output redirection:** `>` to overwrite and `>>` to append

## How it works

```
read input -> parse into command + arguments -> built-in?
                                                  |-- yes -> run inside the shell
                                                  |-- no  -> search PATH -> spawn child process -> print its output
```

- **Parsing:** the input line is split into a command name and its arguments.
- **Built-ins:** `cd` has to be a built-in because a child process cannot change the working directory of its parent. `type` reports whether a command is a built-in or an executable on `PATH`.
- **External commands:** the shell looks through each directory in `PATH`, and if the executable is found, starts it with `ProcessBuilder` and forwards its output.
- **Redirection:** the output stream is pointed at a file (truncate for `>`, append for `>>`) instead of the terminal.

## Getting started

### Prerequisites

- Java (JDK) installed, [your version here, e.g. 21]
- Maven
- A Linux environment (Ubuntu or WSL recommended)

### Run

```bash
git clone [your-repo-link]
cd codecrafters-shell-java
./your_program.sh
```

You should see the `$` prompt. Try a few commands:

```
$ echo hello
$ pwd
$ type ls
$ ls -l
```

Type `exit` to quit.

## Project structure

```
.
├── src/main/java/      # Shell source code
├── your_program.sh     # Script that builds and runs the shell
├── pom.xml             # Maven build file
└── README.md
```

## What I learned

- How a shell turns a line of text into a running process
- Why some commands must be built into the shell and others are separate programs
- How `PATH` lookup works
- How standard output is a stream that can be redirected to a file

## Roadmap

- [ ] Pipes (`|`)
- [ ] Quote and escape handling
- [ ] Command history
- [ ] Tab completion

## Acknowledgements

Built with the guided stages of the [CodeCrafters](https://codecrafters.io) Shell challenge.
