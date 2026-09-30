import java.util.Scanner;
import java.util.Set;

public class Main {
  static final Set<String> BUILTINS = Set.of("echo", "exit", "type");

  public static void main(String[] args) throws Exception {
    Scanner scanner = new Scanner(System.in);

    while (true) {
      System.out.print("$ ");
      System.out.flush();

      if (!scanner.hasNextLine()) break;
      String command = scanner.nextLine().trim();

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
          System.out.println(arg + ": not found");
        }
        continue;
      }

      System.out.println(command + ": command not found");
    }
  }
}