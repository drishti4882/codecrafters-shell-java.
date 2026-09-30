import java.util.Scanner;

public class Main {
  public static void main(String[] args) throws Exception {
    Scanner scanner = new Scanner(System.in);

    while (true) {
      System.out.print("$ ");
      System.out.flush();

      if (!scanner.hasNextLine()) break;   // stop on Ctrl+D / end of input
      String command = scanner.nextLine();

      System.out.println(command + ": command not found");
    }
  }
}