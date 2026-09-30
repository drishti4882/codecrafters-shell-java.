import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class Main {
  static final Set<String> BUILTINS = Set.of("echo", "exit", "type", "pwd", "cd", "history");
  static Path cwd = Paths.get("").toAbsolutePath();

  static final List<String> history = new ArrayList<>();
  static int historyWritten = 0;   // entries before this index are already on disk

  static class Cmd {
    List<String> args = new ArrayList<>();
    File out, err;
    boolean outApp, errApp;
  }

  // ---------- history helpers ----------
  static void loadHistory(Path p) {
    try {
      for (String l : Files.readAllLines(p)) {
        if (!l.isEmpty()) history.add(l);
      }
    } catch (IOException ignored) {
    }
  }

  static void writeHistory(Path p, boolean append) {
    try {
      List<String> lines = append
          ? history.subList(Math.min(historyWritten, history.size()), history.size())
          : history;
      if (append) {
        Files.write(p, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } else {
        Files.write(p, lines, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      }
      historyWritten = history.size();
    } catch (IOException ignored) {
    }
  }

  static void saveOnExit() {
    String hf = System.getenv("HISTFILE");
    if (hf != null && !hf.isEmpty()) writeHistory(Paths.get(hf), false);
  }

  // ---------- terminal helpers ----------
  static void stty(String... flags) {
    try {
      List<String> c = new ArrayList<>();
      c.add("stty");
      c.addAll(List.of(flags));
      new ProcessBuilder(c)
          .redirectInput(ProcessBuilder.Redirect.INHERIT)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start().waitFor();
    } catch (Exception ignored) {
    }
  }

  static void redraw(StringBuilder buf) {
    System.out.print("\r\u001b[K$ " + buf);
  }

  static String readLine() throws IOException {
    stty("-icanon", "-echo", "min", "1");
    try {
      StringBuilder buf = new StringBuilder();
      boolean lastWasTab = false;
      int pos = history.size();            // where the Up/Down cursor is
      while (true) {
        int ch = System.in.read();
        if (ch == -1) return buf.length() == 0 ? null : buf.toString();

        if (ch == '\t') {
          lastWasTab = complete(buf, lastWasTab);
          System.out.flush();
          continue;
        }
        lastWasTab = false;

        if (ch == '\n' || ch == '\r') {
          System.out.print("\r\n");
          System.out.flush();
          return buf.toString();
        } else if (ch == 27) {             // escape sequence: ESC [ A / B
          int b1 = System.in.read();
          if (b1 == '[') {
            int b2 = System.in.read();
            if (b2 == 'A' && pos > 0) {    // Up
              pos--;
              buf.setLength(0);
              buf.append(history.get(pos));
              redraw(buf);
            } else if (b2 == 'B' && pos < history.size()) {   // Down
              pos++;
              buf.setLength(0);
              if (pos < history.size()) buf.append(history.get(pos));
              redraw(buf);
            }
          }
        } else if (ch == 4) {
          if (buf.length() == 0) return null;
        } else if (ch == 127 || ch == 8) {
          if (buf.length() > 0) {
            buf.setLength(buf.length() - 1);
            System.out.print("\b \b");
          }
        } else {
          buf.append((char) ch);
          System.out.print((char) ch);
        }
        System.out.flush();
      }
    } finally {
      stty("icanon", "echo");
    }
  }

  static boolean complete(StringBuilder buf, boolean lastWasTab) {
    String prefix = buf.toString();
    if (prefix.contains(" ")) {
      System.out.print("\u0007");
      return false;
    }
    TreeSet<String> matches = new TreeSet<>();
    for (String b : BUILTINS) {
      if (b.startsWith(prefix)) matches.add(b);
    }
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        File[] files = new File(dir).listFiles();
        if (files == null) continue;
        for (File f : files) {
          if (f.getName().startsWith(prefix) && f.isFile() && f.canExecute()) {
            matches.add(f.getName());
          }
        }
      }
    }
    if (matches.isEmpty()) {
      System.out.print("\u0007");
      return false;
    }
    if (matches.size() == 1) {
      String rest = matches.first().substring(prefix.length()) + " ";
      buf.append(rest);
      System.out.print(rest);
      return false;
    }
    String lcp = matches.first();
    for (String m : matches) {
      int k = 0;
      while (k < lcp.length() && k < m.length() && lcp.charAt(k) == m.charAt(k)) k++;
      lcp = lcp.substring(0, k);
    }
    if (lcp.length() > prefix.length()) {
      String rest = lcp.substring(prefix.length());
      buf.append(rest);
      System.out.print(rest);
      return false;
    }
    if (!lastWasTab) {
      System.out.print("\u0007");
      return true;
    }
    System.out.print("\r\n" + String.join("  ", matches) + "\r\n");
    System.out.print("$ " + buf);
    return false;
  }

  // ---------- shell ----------
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
    boolean inToken = false, inSingle = false, inDouble = false;

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
        if (i + 1 < s.length()) cur.append(s.charAt(++i));
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

  static Cmd extract(List<String> tokens) {
    Cmd c = new Cmd();
    for (int i = 0; i < tokens.size(); i++) {
      String t = tokens.get(i);
      boolean hasNext = i + 1 < tokens.size();
      if (hasNext && (t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>"))) {
        c.outApp = t.endsWith(">>");
        c.out = cwd.resolve(tokens.get(++i)).toFile();
      } else if (hasNext && (t.equals("2>") || t.equals("2>>"))) {
        c.errApp = t.equals("2>>");
        c.err = cwd.resolve(tokens.get(++i)).toFile();
      } else {
        c.args.add(t);
      }
    }
    return c;
  }

  static void runBuiltin(Cmd c, PrintStream out, PrintStream err) {
    String name = c.args.get(0);
    List<String> argv = c.args.subList(1, c.args.size());
    switch (name) {
      case "pwd" -> out.println(cwd);
      case "echo" -> out.println(String.join(" ", argv));
      case "cd" -> {
        String target = argv.isEmpty() ? "~" : argv.get(0);
        String expanded = target;
        if (target.equals("~") || target.startsWith("~/")) {
          String home = System.getenv("HOME");
          expanded = (home == null ? "" : home) + target.substring(1);
        }
        Path p = cwd.resolve(expanded).normalize();
        if (Files.isDirectory(p)) cwd = p;
        else err.println("cd: " + target + ": No such file or directory");
      }
      case "type" -> {
        for (String a : argv) {
          if (BUILTINS.contains(a)) {
            out.println(a + " is a shell builtin");
          } else {
            String found = findInPath(a);
            if (found != null) out.println(a + " is " + found);
            else out.println(a + ": not found");
          }
        }
      }
      case "history" -> {
        if (argv.size() >= 2 && argv.get(0).equals("-r")) {
          loadHistory(cwd.resolve(argv.get(1)));
        } else if (argv.size() >= 2 && argv.get(0).equals("-w")) {
          writeHistory(cwd.resolve(argv.get(1)), false);
        } else if (argv.size() >= 2 && argv.get(0).equals("-a")) {
          writeHistory(cwd.resolve(argv.get(1)), true);
        } else {
          int start = 0;
          if (!argv.isEmpty()) {
            try {
              start = Math.max(0, history.size() - Integer.parseInt(argv.get(0)));
            } catch (NumberFormatException e) {
              err.println("history: " + argv.get(0) + ": numeric argument required");
              return;
            }
          }
          for (int i = start; i < history.size(); i++) {
            out.println(String.format("%5d  %s", i + 1, history.get(i)));
          }
        }
      }
      default -> { } // "exit" is handled in main
    }
  }

  static void runPipeline(List<Cmd> cmds) throws Exception {
    InputStream prev = null;
    List<Process> procs = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();
    Process lastProc = null;
    int n = cmds.size();

    for (int i = 0; i < n; i++) {
      Cmd c = cmds.get(i);
      boolean last = i == n - 1;
      String name = c.args.get(0);

      if (c.out != null) new FileOutputStream(c.out, c.outApp).close();
      if (c.err != null) new FileOutputStream(c.err, c.errApp).close();

      final PrintStream err = c.err != null
          ? new PrintStream(new FileOutputStream(c.err, true), true)
          : System.err;
      InputStream next = new ByteArrayInputStream(new byte[0]);

      if (BUILTINS.contains(name)) {
        if (prev != null) { try { prev.close(); } catch (IOException ignored) { } }

        final PrintStream out;
        if (c.out != null) {
          out = new PrintStream(new FileOutputStream(c.out, true), true);
        } else if (last) {
          out = System.out;
        } else {
          PipedInputStream pin = new PipedInputStream(1 << 16);
          out = new PrintStream(new PipedOutputStream(pin), true);
          next = pin;
        }
        Runnable r = () -> {
          try {
            runBuiltin(c, out, err);
          } finally {
            out.flush();
            if (out != System.out) out.close();
            if (err != System.err) err.close();
          }
        };
        if (last) {
          r.run();
        } else {
          Thread t = new Thread(r);
          t.start();
          threads.add(t);
        }
      } else if (findInPath(name) == null) {
        if (prev != null) { try { prev.close(); } catch (IOException ignored) { } }
        err.println(name + ": command not found");
        if (err != System.err) err.close();
      } else {
        ProcessBuilder pb = new ProcessBuilder(c.args);
        pb.directory(cwd.toFile());
        pb.redirectInput(prev != null
            ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(c.out != null
            ? ProcessBuilder.Redirect.appendTo(c.out)
            : (last ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE));
        pb.redirectError(c.err != null
            ? ProcessBuilder.Redirect.appendTo(c.err)
            : ProcessBuilder.Redirect.INHERIT);
        Process p = pb.start();
        procs.add(p);

        if (prev != null) {
          final InputStream in = prev;
          Thread pump = new Thread(() -> {
            try (OutputStream o = p.getOutputStream()) {
              in.transferTo(o);
            } catch (IOException ignored) {
            }
          });
          pump.setDaemon(true);
          pump.start();
        }
        if (c.out == null && !last) next = p.getInputStream();
        if (last) lastProc = p;
        if (err != System.err) err.close();
      }
      prev = next;
    }

    if (lastProc != null) lastProc.waitFor();
    for (Thread t : threads) t.join();
    for (Process p : procs) {
      if (p != lastProc && p.isAlive()) p.destroy();
    }
  }

  public static void main(String[] args) throws Exception {
    String hf = System.getenv("HISTFILE");
    if (hf != null && !hf.isEmpty()) {
      loadHistory(Paths.get(hf));
      historyWritten = history.size();
    }

    while (true) {
      System.out.print("$ ");
      System.out.flush();

      String line = readLine();
      if (line == null) break;
      if (line.trim().isEmpty()) continue;
      history.add(line);

      List<String> tokens = parse(line);
      if (tokens.isEmpty()) continue;

      List<Cmd> cmds = new ArrayList<>();
      List<String> seg = new ArrayList<>();
      for (String t : tokens) {
        if (t.equals("|")) {
          cmds.add(extract(seg));
          seg = new ArrayList<>();
        } else {
          seg.add(t);
        }
      }
      cmds.add(extract(seg));

      boolean bad = false;
      for (Cmd c : cmds) if (c.args.isEmpty()) bad = true;
      if (bad) continue;

      if (cmds.size() == 1 && cmds.get(0).args.get(0).equals("exit")) {
        saveOnExit();
        System.exit(0);
      }

      runPipeline(cmds);
      System.out.flush();
    }
    saveOnExit();
  }
}