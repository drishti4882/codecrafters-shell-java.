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

      System.out.println(command + ": command not found");
    }
  }
}