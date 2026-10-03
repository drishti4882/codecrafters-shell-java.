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
    static final Set<String> BUILTINS =
            Set.of("exit", "echo", "type", "pwd", "cd", "jobs", "complete");
    static final Map<String, String> completers = new HashMap<>();
    static File cwd = new File(System.getProperty("user.dir"));
    static boolean rawOk = true;
    static final BufferedReader fallbackReader =
            new BufferedReader(new InputStreamReader(System.in));

    // ---------- terminal mode ----------

    static int stty(String args) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (Exception e) {
            return 1;
        }
    }

    static void setRaw() {
        if (rawOk && stty("-icanon -echo min 1") != 0) rawOk = false;
    }

    static void setCooked() {
        if (rawOk) stty("icanon echo");
    }

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

    // ---------- line input with Tab completion ----------

    static String readLine() throws IOException {
        // raw mode is already set by main() before the prompt was printed
        if (!rawOk) {
            return fallbackReader.readLine();
        }
        StringBuilder buf = new StringBuilder();
        int tabCount = 0;
        try {
            while (true) {
                int c = System.in.read();
                if (c == -1) return buf.length() == 0 ? null : buf.toString();

                if (c == '\n' || c == '\r') {
                    System.out.print("\n");
                    System.out.flush();
                    return buf.toString();
                } else if (c == 4) { // Ctrl-D
                    if (buf.length() == 0) return null;
                } else if (c == 127 || c == 8) {
                    if (buf.length() > 0) {
                        buf.setLength(buf.length() - 1);
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                    tabCount = 0;
                } else if (c == 9) {
                    tabCount++;
                    if (handleTab(buf, tabCount)) tabCount = 0;
                } else if (c >= 32) {
                    buf.append((char) c);
                    System.out.print((char) c);
                    System.out.flush();
                    tabCount = 0;
                }
            }
        } finally {
            setCooked(); // normal mode while the command runs
        }
    }

    // returns true if the tab made progress (so the tab counter resets)
    static boolean handleTab(StringBuilder buf, int tabCount) {
        String line = buf.toString();
        int lastSpace = line.lastIndexOf(' ');
        String word = line.substring(lastSpace + 1);

        TreeSet<String> candidates = new TreeSet<>();

        if (lastSpace < 0) {
            // completing the command name
            for (String b : BUILTINS) if (b.startsWith(word)) candidates.add(b);
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null && !word.isEmpty()) {
                for (String dir : pathEnv.split(":")) {
                    File[] files = new File(dir).listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        if (f.getName().startsWith(word) && f.isFile() && f.canExecute()) {
                            candidates.add(f.getName());
                        }
                    }
                }
            }
        } else {
            // completing an argument: use a registered completer if any
            String[] parts = line.trim().isEmpty() ? new String[0] : line.split(" +");
            String cmd = parts.length > 0 ? parts[0] : "";
            String script = completers.get(cmd);
            if (script != null) {
                String prev;
                if (word.isEmpty()) prev = parts[parts.length - 1];
                else prev = parts.length >= 2 ? parts[parts.length - 2] : cmd;
                candidates.addAll(runCompleter(script, cmd, word, prev, line));
            }
        }

        if (candidates.isEmpty()) {
            System.out.print("\u0007");
            System.out.flush();
            return false;
        }

        if (candidates.size() == 1) {
            String match = candidates.first();
            String rest = match.substring(word.length()) + " ";
            buf.append(rest);
            System.out.print(rest);
            System.out.flush();
            return true;
        }

        String lcp = candidates.first();
        for (String s : candidates) {
            int i = 0;
            while (i < lcp.length() && i < s.length() && lcp.charAt(i) == s.charAt(i)) i++;
            lcp = lcp.substring(0, i);
        }
        if (lcp.length() > word.length()) {
            String rest = lcp.substring(word.length());
            buf.append(rest);
            System.out.print(rest);
            System.out.flush();
            return true;
        }

        if (tabCount == 1) {
            System.out.print("\u0007");
        } else {
            System.out.print("\n" + String.join("  ", candidates) + "\n$ " + buf);
        }
        System.out.flush();
        return false;
    }

    static List<String> runCompleter(String script, String cmd, String word, String prev, String line) {
        List<String> out = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(script, cmd, word, prev);
            pb.directory(cwd);
            pb.environment().put("COMP_LINE", line);
            pb.environment().put("COMP_POINT", String.valueOf(line.length()));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isEmpty()) out.add(l);
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- main loop ----------

    public static void main(String[] args) throws Exception {
        while (true) {
            reapJobs();
            setRaw();                  // raw mode BEFORE the prompt is shown
            System.out.print("$ ");
            System.out.flush();

            String line = readLine();
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
            case "complete":
                builtinComplete(args);
                break;
        }
        System.out.flush();
    }

    static void builtinComplete(List<String> args) {
        if (args.isEmpty()) return;
        switch (args.get(0)) {
            case "-C":
                if (args.size() >= 3) completers.put(args.get(2), args.get(1));
                break;
            case "-p":
                if (args.size() >= 2) {
                    String c = args.get(1);
                    String script = completers.get(c);
                    if (script != null) System.out.println("complete -C '" + script + "' " + c);
                    else System.out.println("complete: " + c + ": no completion specification");
                }
                break;
            case "-r":
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