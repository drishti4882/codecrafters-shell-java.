import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    static final Set<String> BUILTINS =
            Set.of("exit", "echo", "type", "pwd", "cd", "declare");

    static Path cwd = Paths.get("").toAbsolutePath();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine();

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            String cmd = tokens.get(0);
            List<String> cmdArgs = tokens.subList(1, tokens.size());

            switch (cmd) {
                case "exit":
                    System.exit(0);
                    break;
                case "echo":
                    System.out.println(String.join(" ", cmdArgs));
                    break;
                case "pwd":
                    System.out.println(cwd);
                    break;
                case "cd":
                    handleCd(cmdArgs);
                    break;
                case "type":
                    handleType(cmdArgs);
                    break;
                case "declare":
                    handleDeclare(cmdArgs);
                    break;
                default:
                    runExternal(cmd, tokens);
            }
        }
    }

    // ---------- declare ----------
    static void handleDeclare(List<String> args) {
        if (args.size() >= 2 && args.get(0).equals("-p")) {
            String name = args.get(1);
            // Hardcoded for this stage: no variable store yet
            System.out.println("declare: " + name + ": not found");
        }
    }

    // ---------- cd ----------
    static void handleCd(List<String> args) {
        if (args.isEmpty()) return;
        String target = args.get(0);

        if (target.equals("~") || target.startsWith("~/")) {
            String home = System.getenv("HOME");
            target = home + target.substring(1);
        }

        Path newPath = cwd.resolve(target).normalize();
        File dir = newPath.toFile();
        if (dir.exists() && dir.isDirectory()) {
            cwd = newPath;
        } else {
            System.out.println("cd: " + args.get(0) + ": No such file or directory");
        }
    }

    // ---------- type ----------
    static void handleType(List<String> args) {
        for (String name : args) {
            if (BUILTINS.contains(name)) {
                System.out.println(name + " is a shell builtin");
            } else {
                String path = findExecutable(name);
                if (path != null) {
                    System.out.println(name + " is " + path);
                } else {
                    System.out.println(name + ": not found");
                }
            }
        }
    }

    static String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, name);
            if (f.exists() && f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    // ---------- external programs ----------
    static void runExternal(String cmd, List<String> tokens) throws Exception {
        String exe = findExecutable(cmd);
        if (exe == null) {
            System.out.println(cmd + ": command not found");
            return;
        }
        List<String> command = new ArrayList<>(tokens);
        command.set(0, cmd);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.inheritIO();
        pb.start().waitFor();
    }

    // ---------- tokenizer (single quotes, double quotes, backslash) ----------
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        boolean inSingle = false, inDouble = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingle) {
                if (c == '\'') inSingle = false;
                else cur.append(c);
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < line.length()
                        && "\"\\$`".indexOf(line.charAt(i + 1)) >= 0) {
                    cur.append(line.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                    inToken = true;
                } else if (c == '"') {
                    inDouble = true;
                    inToken = true;
                } else if (c == '\\' && i + 1 < line.length()) {
                    cur.append(line.charAt(++i));
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
        }
        if (inToken) tokens.add(cur.toString());
        return tokens;
    }
}