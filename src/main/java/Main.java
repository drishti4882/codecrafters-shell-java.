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
    static final List<String> history = new ArrayList<>();
    static int lastAppended = 0; // entries before this index are already saved to a file
    static final Set<String> BUILTINS =
            Set.of("exit", "echo", "type", "pwd", "cd", "jobs", "complete", "history");
    static final Map<String, String> completers = new HashMap<>();
    static File cwd = new File(System.getProperty("user.dir"));
    static boolean rawOk = true;
    static final BufferedReader fallbackReader =
            new BufferedReader(new InputStreamReader(System.in));
    static final PrintStream realOut = System.out;
    static final PrintStream realErr = System.err;

    // ---------- redirection ----------

    static class Redirects {
        File out, err;
        boolean outAppend, errAppend;
    }

    static File resolve(String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(cwd, path);
    }

    static Redirects extractRedirects(List<String> tokens) {
        Redirects r = new Redirects();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean isOut = t.equals(">") || t.equals("1>");
            boolean isOutApp = t.equals(">>") || t.equals("1>>");
            boolean isErr = t.equals("2>");
            boolean isErrApp = t.equals("2>>");
            if (!(isOut || isOutApp || isErr || isErrApp)) continue;
            if (i + 1 >= tokens.size()) break;
            File target = resolve(tokens.get(i + 1));
            if (isOut || isOutApp) {
                r.out = target;
                r.outAppend = isOutApp;
            } else {
                r.err = target;
                r.errAppend = isErrApp;
            }
            tokens.remove(i + 1);
            tokens.remove(i);
            i--;
        }
        return r;
    }

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

    // ---------- history ----------

    // NEW: reusable loader, used by `history -r` and by startup HISTFILE loading
    static boolean loadHistoryFile(File f) {
        List<String> loaded = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.trim().isEmpty()) loaded.add(l);
            }
        } catch (IOException e) {
            return false;
        }
        history.addAll(loaded);
        lastAppended = history.size(); // loaded lines are not "new"
        return true;
    }

    // NEW: write in-memory history to HISTFILE (called on exit)
    static void saveHistoryOnExit() {
        String histFile = System.getenv("HISTFILE");
        if (histFile == null || histFile.isEmpty()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(histFile, false))) {
            for (String entry : history) {
                w.write(entry);
                w.write("\n");
            }
        } catch (IOException ignored) {}
    }

    static void builtinHistory(List<String> args) {
        // history -r <file>
        if (!args.isEmpty() && args.get(0).equals("-r")) {
            if (args.size() < 2) {
                System.err.println("history: -r: option requires an argument");
                return;
            }
            if (!loadHistoryFile(resolve(args.get(1)))) {
                System.err.println("history: " + args.get(1) + ": cannot read history file");
            }
            return;
        }

        // history -w <file>
        if (!args.isEmpty() && args.get(0).equals("-w")) {
            if (args.size() < 2) {
                System.err.println("history: -w: option requires an argument");
                return;
            }
            File f = resolve(args.get(1));
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, false))) {
                for (String entry : history) {
                    w.write(entry);
                    w.write("\n");
                }
            } catch (IOException e) {
                System.err.println("history: " + args.get(1) + ": cannot write history file");
                return;
            }
            lastAppended = history.size();
            return;
        }

        // history -a <file>
        if (!args.isEmpty() && args.get(0).equals("-a")) {
            if (args.size() < 2) {
                System.err.println("history: -a: option requires an argument");
                return;
            }
            File f = resolve(args.get(1));
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
                for (int i = lastAppended; i < history.size(); i++) {
                    w.write(history.get(i));
                    w.write("\n");
                }
            } catch (IOException e) {
                System.err.println("history: " + args.get(1) + ": cannot write history file");
                return;
            }
            lastAppended = history.size();
            return;
        }

        int total = history.size();
        int start = 0;
        if (!args.isEmpty()) {
            try {
                int n = Integer.parseInt(args.get(0));
                if (n < 0) {
                    System.err.println("history: " + args.get(0) + ": invalid option");
                    return;
                }
                start = Math.max(0, total - n);
            } catch (NumberFormatException e) {
                System.err.println("history: " + args.get(0) + ": numeric argument required");
                return;
            }
        }
        for (int i = start; i < total; i++) {
            System.out.printf("%5d  %s%n", i + 1, history.get(i));
        }
    }

    // ---------- line input with Tab completion and history ----------

    static void replaceLine(StringBuilder buf, String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < buf.length(); i++) out.append("\b \b");
        out.append(text);
        System.out.print(out);
        System.out.flush();
        buf.setLength(0);
        buf.append(text);
    }

    static String readLine() throws IOException {
        if (!rawOk) {
            return fallbackReader.readLine();
        }
        StringBuilder buf = new StringBuilder();
        int tabCount = 0;
        int histIndex = history.size();
        String saved = "";
        try {
            while (true) {
                int c = System.in.read();
                if (c == -1) return buf.length() == 0 ? null : buf.toString();

                if (c == '\n' || c == '\r') {
                    System.out.print("\n");
                    System.out.flush();
                    return buf.toString();
                } else if (c == 27) { // escape sequence (arrow keys)
                    int c1 = System.in.read();
                    if (c1 == '[' || c1 == 'O') {
                        int c2 = System.in.read();
                        if (c2 == 'A') { // Up
                            if (histIndex > 0) {
                                if (histIndex == history.size()) saved = buf.toString();
                                histIndex--;
                                replaceLine(buf, history.get(histIndex));
                            }
                        } else if (c2 == 'B') { // Down
                            if (histIndex < history.size()) {
                                histIndex++;
                                String next = histIndex == history.size()
                                        ? saved : history.get(histIndex);
                                replaceLine(buf, next);
                            }
                        }
                    }
                    tabCount = 0;
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
            setCooked();
        }
    }

    static TreeSet<String> fileCandidates(String word) {
        TreeSet<String> result = new TreeSet<>();
        int slash = word.lastIndexOf('/');
        String dirPart = slash >= 0 ? word.substring(0, slash + 1) : "";
        String prefix = slash >= 0 ? word.substring(slash + 1) : word;

        File dir;
        if (dirPart.isEmpty()) dir = cwd;
        else if (dirPart.startsWith("/")) dir = new File(dirPart);
        else dir = new File(cwd, dirPart);

        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") && !prefix.startsWith(".")) continue;
            if (name.startsWith(prefix)) {
                result.add(f.isDirectory() ? name + "/" : name);
            }
        }
        return result;
    }

    static boolean handleTab(StringBuilder buf, int tabCount) {
        String line = buf.toString();
        int lastSpace = line.lastIndexOf(' ');
        String word = line.substring(lastSpace + 1);
        String prefix = word;

        TreeSet<String> candidates = new TreeSet<>();

        if (lastSpace < 0) {
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
            String[] parts = line.trim().isEmpty() ? new String[0] : line.split(" +");
            String cmd = parts.length > 0 ? parts[0] : "";
            String script = completers.get(cmd);
            if (script != null) {
                String prev;
                if (word.isEmpty()) prev = parts[parts.length - 1];
                else prev = parts.length >= 2 ? parts[parts.length - 2] : cmd;
                candidates.addAll(runCompleter(script, cmd, word, prev, line));
            } else {
                candidates = fileCandidates(word);
                int slash = word.lastIndexOf('/');
                prefix = slash >= 0 ? word.substring(slash + 1) : word;
            }
        }

        if (candidates.isEmpty()) {
            System.out.print("\u0007");
            System.out.flush();
            return false;
        }

        if (candidates.size() == 1) {
            String match = candidates.first();
            String rest = match.substring(prefix.length());
            if (!match.endsWith("/")) rest += " ";
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
        if (lcp.length() > prefix.length()) {
            String rest = lcp.substring(prefix.length());
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
        // NEW: load history from HISTFILE on startup
        String histFile = System.getenv("HISTFILE");
        if (histFile != null && !histFile.isEmpty()) {
            File f = new File(histFile);
            if (f.isFile()) loadHistoryFile(f);
        }

        while (true) {
            reapJobs();
            setRaw();
            System.out.print("$ ");
            System.out.flush();

            String line = readLine();
            if (line == null) {
                saveHistoryOnExit(); // NEW: Ctrl-D / EOF
                break;
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            history.add(line);

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

            List<List<String>> segments = splitPipeline(tokens);
            if (segments.size() > 1) {
                runPipeline(segments);
                continue;
            }

            Redirects redirects = extractRedirects(tokens);
            if (tokens.isEmpty()) continue;

            String cmd = tokens.get(0);
            List<String> cmdArgs = tokens.subList(1, tokens.size());

            if (BUILTINS.contains(cmd)) {
                runBuiltinRedirected(cmd, cmdArgs, redirects);
            } else {
                runExternal(tokens, background, commandText, redirects);
            }
        }
    }

    // ---------- pipelines ----------

    static List<List<String>> splitPipeline(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String t : tokens) {
            if (t.equals("|")) {
                segments.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        segments.add(cur);
        return segments;
    }

    static Thread pump(InputStream in, OutputStream out, boolean closeOut) {
        Thread t = new Thread(() -> {
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    out.write(b, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                if (closeOut) {
                    try { out.close(); } catch (IOException ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void runBuiltinTo(String cmd, List<String> args, OutputStream dest) {
        PrintStream ps = new PrintStream(dest, true);
        File savedCwd = cwd;
        System.setOut(ps);
        try {
            if (!cmd.equals("exit")) runBuiltin(cmd, args);
        } finally {
            ps.flush();
            System.setOut(realOut);
            cwd = savedCwd;
        }
    }

    static void runPipeline(List<List<String>> segments) {
        for (List<String> seg : segments) {
            if (seg.isEmpty()) {
                realOut.println("syntax error near unexpected token `|'");
                realOut.flush();
                return;
            }
            String name = seg.get(0);
            if (!BUILTINS.contains(name) && findInPath(name) == null) {
                realOut.println(name + ": command not found");
                realOut.flush();
                return;
            }
        }

        List<String> lastSeg = segments.get(segments.size() - 1);
        Redirects r = extractRedirects(lastSeg);
        if (lastSeg.isEmpty()) return;

        int n = segments.size();
        List<Process> procs = new ArrayList<>();
        Process lastProc = null;
        InputStream prev = null;

        try {
            realOut.flush();
            for (int i = 0; i < n; i++) {
                List<String> seg = segments.get(i);
                boolean last = (i == n - 1);
                String cmd = seg.get(0);
                List<String> cmdArgs = seg.subList(1, seg.size());

                if (BUILTINS.contains(cmd)) {
                    if (prev != null) pump(prev, OutputStream.nullOutputStream(), false);

                    if (last) {
                        if (r.out != null) {
                            try (OutputStream fo = new FileOutputStream(r.out, r.outAppend)) {
                                runBuiltinTo(cmd, cmdArgs, fo);
                            }
                        } else {
                            runBuiltinTo(cmd, cmdArgs, realOut);
                            realOut.flush();
                        }
                        prev = null;
                    } else {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        runBuiltinTo(cmd, cmdArgs, baos);
                        prev = new ByteArrayInputStream(baos.toByteArray());
                    }
                } else {
                    ProcessBuilder pb = new ProcessBuilder(seg);
                    pb.directory(cwd);
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    if (i == 0) pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    if (last) {
                        if (r.out != null) {
                            pb.redirectOutput(r.outAppend
                                    ? ProcessBuilder.Redirect.appendTo(r.out)
                                    : ProcessBuilder.Redirect.to(r.out));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }
                        if (r.err != null) {
                            pb.redirectError(r.errAppend
                                    ? ProcessBuilder.Redirect.appendTo(r.err)
                                    : ProcessBuilder.Redirect.to(r.err));
                        }
                    }
                    Process p = pb.start();
                    procs.add(p);
                    if (prev != null) pump(prev, p.getOutputStream(), true);
                    if (last) {
                        lastProc = p;
                        prev = null;
                    } else {
                        prev = p.getInputStream();
                    }
                }
            }

            if (lastProc != null) lastProc.waitFor();

            for (Process p : procs) {
                if (p != lastProc && p.isAlive()) p.destroy();
            }
            for (Process p : procs) {
                if (p != lastProc && !p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor();
                }
            }
        } catch (IOException | InterruptedException e) {
            realOut.println("pipeline: " + e.getMessage());
            realOut.flush();
        }
    }

    // ---------- builtins ----------

    static void runBuiltinRedirected(String cmd, List<String> args, Redirects r) {
        PrintStream outFile = null, errFile = null;
        try {
            if (r.out != null) {
                outFile = new PrintStream(new FileOutputStream(r.out, r.outAppend), true);
                System.setOut(outFile);
            }
            if (r.err != null) {
                errFile = new PrintStream(new FileOutputStream(r.err, r.errAppend), true);
                System.setErr(errFile);
            }
            runBuiltin(cmd, args);
        } catch (IOException e) {
            realErr.println(cmd + ": " + e.getMessage());
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(realOut);
            System.setErr(realErr);
            if (outFile != null) outFile.close();
            if (errFile != null) errFile.close();
        }
    }

    static void runBuiltin(String cmd, List<String> args) {
        switch (cmd) {
            case "exit":
                saveHistoryOnExit(); // NEW
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
                else System.err.println("cd: " + target + ": No such file or directory");
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
            case "history":
                builtinHistory(args);
                break;
        }
        System.out.flush();
        System.err.flush();
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

    static void runExternal(List<String> tokens, boolean background, String commandText, Redirects r) {
        String path = findInPath(tokens.get(0));
        if (path == null) {
            try {
                if (r.out != null) new FileOutputStream(r.out, r.outAppend).close();
                if (r.err != null) new FileOutputStream(r.err, r.errAppend).close();
            } catch (IOException ignored) {}
            String msg = tokens.get(0) + ": command not found";
            if (r.err != null) {
                try (PrintStream ps = new PrintStream(new FileOutputStream(r.err, true), true)) {
                    ps.println(msg);
                } catch (IOException ignored) {}
            } else {
                realOut.println(msg);
                realOut.flush();
            }
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.directory(cwd);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            if (r.out != null) {
                pb.redirectOutput(r.outAppend
                        ? ProcessBuilder.Redirect.appendTo(r.out)
                        : ProcessBuilder.Redirect.to(r.out));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            if (r.err != null) {
                pb.redirectError(r.errAppend
                        ? ProcessBuilder.Redirect.appendTo(r.err)
                        : ProcessBuilder.Redirect.to(r.err));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

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

    // ---------- tokenizer ----------

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