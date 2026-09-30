import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
  static final Set<String> BUILTINS = Set.of("echo", "exit", "type");

  static String findInPath(String cmd) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      File f = new File(dir, cmd);
      if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    Scanner scanner = new Scanner(System.in);

    while (true) {
      System.out.print("$ ");
      System.out.flush();

      if (!scanner.hasNextLine()) break;
      String command = scanner.nextLine().trim();
      if (command.isEmpty()) continue;

      if (command.equals("exit") || command.startsWith("exit ")) {
        System.exit(0);
      }

      if (command.equals("echo")) {
        System.out.println();
        continue;
      }

      if (command.startsWith("echo ")) {
        System.out.println(command.substring(5));
        continue;
      }

      if (command.startsWith("type ")) {
        String arg = command.substring(5).trim();
        if (BUILTINS.contains(arg)) {
          System.out.println(arg + " is a shell builtin");
        } else {
          String found = findInPath(arg);
          if (found != null) {
            System.out.println(arg + " is " + found);
          } else {
            System.out.println(arg + ": not found");
          }
        }
        continue;
      }

      // ---- external program ----
      List<String> tokens = Arrays.asList(command.split("\\s+"));
      if (findInPath(tokens.get(0)) != null) {
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.inheritIO();
        pb.start().waitFor();
      } else {
        System.out.println(command + ": command not found");
      }
    }
  }
}