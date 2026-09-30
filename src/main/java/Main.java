import java.util.Scanner;

public class Main {
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

      System.out.println(command + ": command not found");
    }
  }
}