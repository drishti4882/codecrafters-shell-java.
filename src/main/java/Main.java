import java.io.File;
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

      String name = tokens.get(0);
      List<String> argv = tokens.subList(1, tokens.size());

      switch (name) {
        case "exit" -> System.exit(0);

        case "pwd" -> System.out.println(cwd);

        case "echo" -> System.out.println(String.join(" ", argv));

        case "cd" -> {
          String target = argv.isEmpty() ? "~" : argv.get(0);
          String expanded = target;
          if (target.equals("~") || target.startsWith("~/")) {
            String home = System.getenv("HOME");
            expanded = (home == null ? "" : home) + target.substring(1);
          }
          Path p = cwd.resolve(expanded).normalize();