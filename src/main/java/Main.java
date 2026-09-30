import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
  static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd");
  static Path cwd = Paths.get("").toAbsolutePath();

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
    Scanner scanner = new Scanner(System.in);

    while (true) {
      System.out.print("$ ");
      System.out.flush();

      if (!scanner.hasNextLine()) break;
      List<String> tokens = parse(scanner.nextLine());
      if (tokens.isEmpty()) continue;

      // ---- pull out "> file", "1> file", "2> file" ----
      File outFile = null;
      File errFile = null;
      List<String> cmd = new ArrayList<>();
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        boolean hasNext = i + 1 < tokens.size();
        if (hasNext && (t.equals(">") || t.equals("1>"))) {
          outFile = cwd.resolve(tokens.get(++i)).toFile();
        } else if (hasNext && t.equals("2>")) {
          errFile = cwd.resolve(tokens.get(++i)).toFile();
        } else {
          cmd.add(t);
        }
      }
      if (cmd.isEmpty()) continue;

      PrintStream out = System.out;
      PrintStream err = System.err;
      if (outFile != null) out = new PrintStream(new FileOutputStream(outFile), true);
      if (errFile != null) err = new PrintStream(new FileOutputStream(errFile), true);

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
                ? ProcessBuilder.Redirect.appendTo(outFile)  // already truncated above
                : ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(errFile != null
                ? ProcessBuilder.Redirect.appendTo(errFile)  // already truncated above
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