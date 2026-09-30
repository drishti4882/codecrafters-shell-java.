import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class Main {
  static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd");
  static Path cwd = Paths.get("").toAbsolutePath();

  // ---------- terminal helpers ----------
  // stty inherits our stdin, so it talks to the same terminal the tester gave us.
  static void stty(String... flags) {
    try {
      List<String> c = new ArrayList<>();
      c.add("stty");
      c.addAll(List.of(flags));
      new ProcessBuilder(c)
          .redirectInput(ProcessBuilder.Redirect.INHERIT)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start().waitFor();
    } catch (Exception ignored) {
    }
  }

  // Returns the typed line, or null at end of input.
  static String readLine() throws IOException {
    stty("-icanon", "-echo", "min", "1");
    try {
      StringBuilder buf = new StringBuilder();
      while (true) {
        int ch = System.in.read();
        if (ch == -1) return buf.length() == 0 ? null : buf.toString();

        if (ch == '\n' || ch == '\r') {
          System.out.print("\r\n");
          System.out.flush();
          return buf.toString();
        } else if (ch == 4) {                       // Ctrl+D
          if (buf.length() == 0) return null;
        } else if (ch == 127 || ch == 8) {          // Backspace
          if (buf.length() > 0) {
            buf.setLength(buf.length() - 1);
            System.out.print("\b \b");
          }
        } else if (ch == '\t') {
          complete(buf);
        } else {
          buf.append((char) ch);
          System.out.print((char) ch);
        }
        System.out.flush();
      }
    } finally {
      stty("icanon", "echo");
    }
  }

  static void complete(StringBuilder buf) {
    String prefix = buf.toString();
    if (prefix.contains(" ")) {                     // only complete the command name
      System.out.print("\u0007");
      return;
    }
    Set<String> matches = new TreeSet<>();
    for (String b : BUILTINS) {
      if (b.startsWith(prefix)) matches.add(b);
    }
    if (matches.size() == 1) {
      String full = matches.iterator().next();
      String rest = full.substring(prefix.length()) + " ";
      buf.append(rest);
      System.out.print(rest);
    } else {
      System.out.print("\u0007");                   // bell
    }
  }

  // ---------- shell ----------
  static String findInPath(String cmd) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      File f = new File(dir, cmd);
      if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }

  static List<String> parse(String s) {
    List<String> tokens = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inToken = false;
    boolean inSingle = false;
    boolean inDouble = false;

    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inSingle) {
        if (c == '\'') inSingle = false;
        else cur.append(c);
      } else if (inDouble) {
        if (c == '"') {
          inDouble = false;
        } else if (c == '\\' && i + 1 < s.length()
            && "\\\"$`".indexOf(s.charAt(i + 1)) >= 0) {
          cur.append(s.charAt(++i));
        } else {
          cur.append(c);
        }
      } else if (c == '\\') {
        if (i + 1 < s.length()) {
          cur.append(s.charAt(++i));
        }
        inToken = true;
      } else if (c == '\'') {
        inSingle = true;
        inToken = true;
      } else if (c == '"') {
        inDouble = true;
        inToken = true;
      } else if (Character.isWhitespace(c)) {
        if (inToken) {
          tokens.add(cur.toString());
          cur.setLength(0);
          inToken = false;
        }
      } else {
        cur.append(c);
        inToken = true;
      }
    }
    if (inToken) tokens.add(cur.toString());
    return tokens;
  }

  public static void main(String[] args) throws Exception {
    while (true) {
      System.out.print("$ ");
      System.out.flush();

      String line = readLine();
      if (line == null) break;
      List<String> tokens = parse(line);
      if (tokens.isEmpty()) continue;

      File outFile = null;
      File errFile = null;
      boolean outAppend = false;
      boolean errAppend = false;
      List<String> cmd = new ArrayList<>();
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        boolean hasNext = i + 1 < tokens.size();
        if (hasNext && (t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>"))) {
          outAppend = t.endsWith(">>");
          outFile = cwd.resolve(tokens.get(++i)).toFile();
        } else if (hasNext && (t.equals("2>") || t.equals("2>>"))) {
          errAppend = t.equals("2>>");
          errFile = cwd.resolve(tokens.get(++i)).toFile();
        } else {
          cmd.add(t);
        }
      }
      if (cmd.isEmpty()) continue;

      PrintStream out = System.out;
      PrintStream err = System.err;
      if (outFile != null) out = new PrintStream(new FileOutputStream(outFile, outAppend), true);
      if (errFile != null) err = new PrintStream(new FileOutputStream(errFile, errAppend), true);

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
            if (BUILTINS.contains(a)) {
              out.println(a + " is a shell builtin");
            } else {
              String found = findInPath(a);
              if (found != null) out.println(a + " is " + found);
              else out.println(a + ": not found");
            }
          }
        }

        default -> {
          if (findInPath(name) != null) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(outFile != null
                ? ProcessBuilder.Redirect.appendTo(outFile)
                : ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(errFile != null
                ? ProcessBuilder.Redirect.appendTo(errFile)
                : ProcessBuilder.Redirect.INHERIT);
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