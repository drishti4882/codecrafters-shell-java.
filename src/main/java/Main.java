import java.io.*;
import java.util.*;

public class Main {

    static class Job {
        final int number;
        final Process process;
        final String command; // without trailing '&'

        Job(int number, Process process, String command) {
            this.number = number;
            this.process = process;
            this.command = command;
        }
    }

    static final List<Job> jobs = new ArrayList<>();
    // CHANGED: added "complete"
    static final Set<String> BUILTINS =
            Set.of("exit", "echo", "type", "pwd", "cd", "jobs", "complete");
    // NEW: command -> completer script path
    static final Map<String, String> completers = new HashMap<>();
    static File cwd = new File(System.getProperty("user.dir"));

    // ---------- job table helpers ----------

    static char marker(int index, int size) {
        if (index == size - 1) return '+';
        if (index == size - 2) return '-';
        return ' ';
    }

    static int nextJobNumber() {
        int max = 0;
        for (Job j : jobs) max = Math.max(max, j.number);
        return max + 1;
    }

    // Runs before each prompt: print only "Done" lines, then remove them.
    static void reapJobs() {
        int n = jobs.size();
        List<Job> finished = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Job job = jobs.get(i);
            if (!job.process.isAlive()) {
                System.out.printf("[%d]%c  %-24s%s%n", job.number, marker(i, n), "Done", job.command);
                finished.add(job);
            }
        }
        jobs.removeAll(finished);
        System.out.flush();
    }

    // jobs builtin: list all jobs in table order, Done or Running.
    static void builtinJobs() {
        int n = jobs.size();
        List<Job> finished = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Job job = jobs.get(i);
            char m = marker(i, n);
            if (!job.process.isAlive()) {
                System.out.printf("[%d]%c  %-24s%s%n", job.number, m, "Done", job.command);
                finished.add(job);
            } else {
                System.out.printf("[%d]%c  %-24s%s &%n", job.number, m, "Running", job.command);
            }
        }
        jobs.removeAll(finished);
        System.out.flush();
    }

    // ---------- main loop ----------

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapJobs();
            System.out.print("$ ");
            System.out.flush();

            String line = in.readLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) continue;

            boolean background = false;
            String commandText = line;
            if (tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens.remove(tokens.size() - 1);
                commandText = line.substring(0, line.lastIndexOf('&')).trim();
                if (tokens.isEmpty()) continue;
            }

            String cmd = tokens.get(0);
            List<String> cmdArgs = tokens.subList(1, tokens.size());

            if (BUILTINS.contains(cmd)) {
                runBuiltin(cmd, cmdArgs);
            } else {
                runExternal(tokens, background, commandText);
            }
        }
    }

    // ---------- builtins ----------

    static void runBuiltin(String cmd, List<String> args) {
        switch (cmd) {
            case "exit":
                System.exit(args.isEmpty() ? 0 : Integer.parseInt(args.get(0)));
                break;
            case "echo":
                System.out.println(String.join(" ", args));
                break;
            case "pwd":
                System.out.println(cwd.getAbsolutePath());
                break;
            case "cd": {
                String target = args.isEmpty() ? System.getenv("HOME") : args.get(0);
                if (target.equals("~")) target = System.getenv("HOME");
                File dir = new File(target);
                if (!dir.isAbsolute()) dir = new File(cwd, target);
                try {
                    dir = dir.getCanonicalFile();
                } catch (IOException ignored) {}
                if (dir.isDirectory()) cwd = dir;
                else System.out.println("cd: " + target + ": No such file or directory");
                break;
            }
            case "type": {
                for (String name : args) {
                    if (BUILTINS.contains(name)) {
                        System.out.println(name + " is a shell builtin");
                    } else {
                        String path = findInPath(name);
                        if (path != null) System.out.println(name + " is " + path);
                        else System.out.println(name + ": not found");
                    }
                }
                break;
            }
            case "jobs":
                builtinJobs();
                break;
            case "complete": // NEW
                builtinComplete(args);
                break;
        }
        System.out.flush();
    }

    // NEW
    static void builtinComplete(List<String> args) {
        if (args.isEmpty()) return;
        String flag = args.get(0);

        switch (flag) {
            case "-C": // complete -C <script> <command>
                if (args.size() >= 3) {
                    completers.put(args.get(2), args.get(1));
                }
                break;

            case "-p": // complete -p <command>
                if (args.size() >= 2) {
                    String c = args.get(1);
                    String script = completers.get(c);
                    if (script != null) {
                        System.out.println("complete -C '" + script + "' " + c);
                    } else {
                        System.out.println("complete: " + c + ": no completion specification");
                    }
                }
                break;

            case "-r": // complete -r <command>
                if (args.size() >= 2) {
                    String c = args.get(1);
                    if (completers.remove(c) == null) {
                        System.out.println("complete: " + c + ": no completion specification");
                    }
                }
                break;
        }
    }

    // ---------- external commands ----------

    static void runExternal(List<String> tokens, boolean background, String commandText) {
        String path = findInPath(tokens.get(0));
        if (path == null) {
            System.out.println(tokens.get(0) + ": command not found");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.directory(cwd);
            pb.inheritIO();
            System.out.flush();
            Process p = pb.start();

            if (background) {
                int num = nextJobNumber();
                jobs.add(new Job(num, p, commandText));
                System.out.println("[" + num + "] " + p.pid());
                System.out.flush();
            } else {
                p.waitFor();
            }
        } catch (IOException | InterruptedException e) {
            System.out.println(tokens.get(0) + ": " + e.getMessage());
        }
    }

    static String findInPath(String name) {
        if (name.contains("/")) {
            File f = new File(name);
            return (f.isFile() && f.canExecute()) ? name : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(":")) {
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
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
                if (c == '\'') { inSingle = true; inToken = true; }
                else if (c == '"') { inDouble = true; inToken = true; }
                else if (c == '\\' && i + 1 < line.length()) {
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