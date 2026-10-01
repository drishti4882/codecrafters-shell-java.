import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
  static final List<String> BUILTINS = List.of("cd", "echo", "exit", "pwd", "type");
  static Path cwd = Paths.get("").toAbsolutePath();

  // ---------- terminal ----------
  static void stty(String flags) {
    try {
      new ProcessBuilder("sh", "-c", "stty " + flags + " < /dev/tty 2>/dev/null || stty " + flags)
          .redirectInput(ProcessBuilder.Redirect.INHERIT)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start().waitFor();
    } catch (Exception ignored) {}
  }

  // Terminal must already be in raw mode when this is called.
  static String readLine() throws IOException {
    StringBuilder buf = new StringBuilder();
    while (true) {
      int ch = System.in.read();
      if (ch == -1) return buf.length() == 0 ? null : buf.toString();

      if (ch == '\n' || ch == '\r') {
        System.out.print("\r\n");
        System.out.flush();
        return buf.toString();
      }
      if (ch == '\t') {
        String prefix = buf.toString();
        String match = null;
        if (!prefix.isEmpty() && !prefix.contains(" ")) {
          for (String b : BUILTINS) {
            if (b.startsWith(prefix)) { match = b; break; }
          }
        }
        if (match != null) {
          buf.setLength(0);
          buf.append(match).append(' ');
          System.out.print("\r\u001b[K$ " + buf);   // clear line, redraw prompt + completed text
        } else {
          System.out.print("\u0007");
        }
      } else if (ch == 127 || ch == 8) {
        if (buf.length() > 0) {
          buf.setLength(buf.length() - 1);
          System.out.print("\b \b");
        }
      } else if (ch == 4) {
        if (buf.length() == 0) return null;
      } else if (ch >= 32) {
        buf.append((char) ch);
        System.out.print((char) ch);
      }
      System.out.flush();
    }
  }

  // ---------- parsing ----------
  static List<String> parse(String s) {
    List<String> tokens = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inToken = false, inSingle = false, inDouble = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inSingle) {
        if (c == '\'') inSingle = false; else cur.append(c);
      } else if (inDouble) {
        if (c == '"') inDouble = false;
        else if (c == '\\' && i + 1 < s.length() && "\\\"$`".indexOf(s.charAt(i + 1)) >= 0)
          cur.append(s.charAt(++i));
        else cur.append(c);
      } else if (c == '\\') {
        if (i + 1 < s.length()) cur.append(s.charAt(++i));
        inToken = true;
      } else if (c == '\'') { inSingle = true; inToken = true; }
      else if (c == '"') { inDouble = true; inToken = true; }
      else if (Character.isWhitespace(c)) {
        if (inToken) { tokens.add(cur.toString()); cur.setLength(0); inToken = false; }
      } else { cur.append(c); inToken = true; }
    }
    if (inToken) tokens.add(cur.toString());
    return tokens;
  }

  static String findInPath(String cmd) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      File f = new File(dir, cmd);
      if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }

  // ---------- main loop ----------
  public static void main(String[] args) throws Exception {
    while (true) {
      stty("-icanon -echo min 1");     // raw mode BEFORE the prompt is shown
      System.out.print("$ ");
      System.out.flush();

      String line = readLine();
      stty("icanon echo");             // normal mode again before running the command
      if (line == null) break;
      List<String> tokens = parse(line);
      if (tokens.isEmpty()) continue;

      File outFile = null, errFile = null;
      boolean outApp = false, errApp = false;
      List<String> cmd = new ArrayList<>();
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        boolean hasNext = i + 1 < tokens.size();
        if (hasNext && (t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>"))) {
          outApp = t.endsWith(">>");
          outFile = cwd.resolve(tokens.get(++i)).toFile();
        } else if (hasNext && (t.equals("2>") || t.equals("2>>"))) {
          errApp = t.equals("2>>");
          errFile = cwd.resolve(tokens.get(++i)).toFile();
        } else {
          cmd.add(t);
        }
      }
      if (cmd.isEmpty()) continue;

      PrintStream out = outFile != null
          ? new PrintStream(new FileOutputStream(outFile, outApp), true) : System.out;
      PrintStream err = errFile != null
          ? new PrintStream(new FileOutputStream(errFile, errApp), true) : System.err;

      String name = cmd.get(0);
      List<String> argv = cmd.subList(1, cmd.size());

      switch (name) {
        case "exit" -> System.exit(0);
        case "pwd" -> out.println(cwd);
        case "echo" -> out.println(String.join(" ", argv));
        case "cd" -> {
          String target = argv.isEmpty() ? "~" : argv.get(0);
          String expanded = target;
          if (target.equals("~") || target.startsWith("~/")) {
            String home = System.getenv("HOME");
            expanded = (home == null ? "" : home) + target.substring(1);
          }
          Path p = cwd.resolve(expanded).normalize();
          if (Files.isDirectory(p)) cwd = p;
          else err.println("cd: " + target + ": No such file or directory");
        }
        case "type" -> {
          for (String a : argv) {
            if (BUILTINS.contains(a)) out.println(a + " is a shell builtin");
            else {
              String f = findInPath(a);
              out.println(f != null ? a + " is " + f : a + ": not found");
            }
          }
        }
        default -> {
          if (findInPath(name) != null) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(outFile != null
                ? ProcessBuilder.Redirect.appendTo(outFile) : ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(errFile != null
                ? ProcessBuilder.Redirect.appendTo(errFile) : ProcessBuilder.Redirect.INHERIT);
            pb.start().waitFor();
          } else {
            err.println(name + ": command not found");
          }
        }
      }
      out.flush();
      err.flush();
      if (outFile != null) out.close();
      if (errFile != null) err.close();
    }
  }
}