import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
  static final List<String> BUILTINS = List.of("cd", "complete", "echo", "exit", "pwd", "type");
  static final Map<String, String> completers = new HashMap<>();   // command -> completer script
  static Path cwd = Paths.get("").toAbsolutePath();

  // ---------- terminal ----------
  static void stty(String flags) {
    try {
      new ProcessBuilder("sh", "-c", "stty " + flags + " < /dev/tty 2>/dev/null || stty " + flags)
          .redirectInput(ProcessBuilder.Redirect.INHERIT)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start().waitFor();
    } catch (Exception ignored) {}
  }

  // ---------- completion ----------
  static TreeSet<String> candidates(String prefix) {
    TreeSet<String> result = new TreeSet<>();
    for (String b : BUILTINS) {
      if (b.startsWith(prefix)) result.add(b);
    }
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        File[] files = new File(dir).listFiles();
        if (files == null) continue;
        for (File f : files) {
          if (f.getName().startsWith(prefix) && f.isFile() && f.canExecute()) {
            result.add(f.getName());
          }
        }
      }
    }
    return result;
  }

  static TreeSet<String> fileCandidates(String word) {
    TreeSet<String> result = new TreeSet<>();
    int slash = word.lastIndexOf('/');
    String dirPart = slash >= 0 ? word.substring(0, slash + 1) : "";
    String namePart = word.substring(slash + 1);
    File dir = dirPart.isEmpty() ? cwd.toFile() : cwd.resolve(dirPart).toFile();
    File[] files = dir.listFiles();
    if (files == null) return result;
    for (File f : files) {
      if (f.getName().startsWith(namePart)) {
        result.add(dirPart + f.getName() + (f.isDirectory() ? "/" : ""));
      }
    }
    return result;
  }

  static String commonPrefix(TreeSet<String> names) {
    String first = names.first();
    String last = names.last();
    int k = 0;
    while (k < first.length() && k < last.length() && first.charAt(k) == last.charAt(k)) k++;
    return first.substring(0, k);
  }

  // Terminal must already be in raw mode when this is called.
  static String readLine() throws IOException {
    StringBuilder buf = new StringBuilder();
    boolean lastWasTab = false;
    while (true) {
      int ch = System.in.read();
      if (ch == -1) return buf.length() == 0 ? null : buf.toString();

      if (ch == '\t') {
        String full = buf.toString();
        int sp = full.lastIndexOf(' ');
        boolean isArg = sp >= 0;
        String head = isArg ? full.substring(0, sp + 1) : "";
        String prefix = isArg ? full.substring(sp + 1) : full;
        TreeSet<String> matches = new TreeSet<>();
        if (isArg) {
          matches = fileCandidates(prefix);
        } else if (!prefix.isEmpty()) {
          matches = candidates(prefix);
        }

        if (matches.isEmpty()) {
          System.out.print("\u0007");
          lastWasTab = false;
        } else if (matches.size() == 1) {
          String m = matches.first();
          buf.setLength(0);
          buf.append(head).append(m);
          if (!m.endsWith("/")) buf.append(' ');
          System.out.print("\r\u001b[K$ " + buf);
          lastWasTab = false;
        } else {
          String lcp = commonPrefix(matches);
          if (lcp.length() > prefix.length()) {
            buf.setLength(0);
            buf.append(head).append(lcp);
            System.out.print("\r\u001b[K$ " + buf);
            lastWasTab = false;
          } else if (!lastWasTab) {
            System.out.print("\u0007");
            lastWasTab = true;
          } else {
            System.out.print("\r\n" + String.join("  ", matches) + "\r\n");
            System.out.print("$ " + buf);
            lastWasTab = false;
          }
        }
        System.out.flush();
        continue;
      }
      lastWasTab = false;

      if (ch == '\n' || ch == '\r') {
        System.out.print("\r\n");
        System.out.flush();
        return buf.toString();
      } else if (ch == 127 || ch == 8) {
        if (buf.length() > 0) {
          buf.setLength(buf.length() - 1);
          System.out.print("\b \b");
        }
      } else if (ch == 4) {
        if (buf.length() == 0) return null;
      } else if (ch >= 32) {
        buf.append((char) ch);
        System.out.print((char) ch);
      }
      System.out.flush();
    }
  }

  // ---------- parsing ----------
  static List<String> parse(String s) {
    List<String> tokens = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inToken = false, inSingle = false, inDouble = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inSingle) {
        if (c == '\'') inSingle = false; else cur.append(c);
      } else if (inDouble) {
        if (c == '"') inDouble = false;
        else if (c == '\\' && i + 1 < s.length() && "\\\"$`".indexOf(s.charAt(i + 1)) >= 0)
          cur.append(s.charAt(++i));
        else cur.append(c);
      } else if (c == '\\') {
        if (i + 1 < s.length()) cur.append(s.charAt(++i));
        inToken = true;
      } else if (c == '\'') { inSingle = true; inToken = true; }
      else if (c == '"') { inDouble = true; inToken = true; }
      else if (Character.isWhitespace(c)) {
        if (inToken) { tokens.add(cur.toString()); cur.setLength(0); inToken = false; }
      } else { cur.append(c); inToken = true; }
    }
    if (inToken) tokens.add(cur.toString());
    return tokens;
  }

  static String findInPath(String cmd) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      File f = new File(dir, cmd);
      if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }

  // ---------- pipelines ----------
  static void runBuiltinTo(List<String> cmd, PrintStream out, PrintStream err) {
    String name = cmd.get(0);
    List<String> argv = cmd.subList(1, cmd.size());
    switch (name) {
      case "pwd" -> out.println(cwd);
      case "echo" -> out.println(String.join(" ", argv));
      case "type" -> {
        for (String a : argv) {
          if (BUILTINS.contains(a)) out.println(a + " is a shell builtin");
          else {
            String f = findInPath(a);
            out.println(f != null ? a + " is " + f : a + ": not found");
          }
        }
      }
      default -> { }   // cd, exit, complete do nothing inside a pipeline
    }
  }

  static void runPipeline(List<List<String>> segs) throws Exception {
    List<Process> procs = new ArrayList<>();
    InputStream prev = null;
    Thread builtinThread = null;
    Process last = null;

    for (int i = 0; i < segs.size(); i++) {
      List<String> seg = segs.get(i);
      boolean isLast = i == segs.size() - 1;
      String name = seg.get(0);

      if (BUILTINS.contains(name)) {
        if (prev != null) prev.close();
        if (isLast) {
          runBuiltinTo(seg, System.out, System.err);
          System.out.flush();
          prev = null;
        } else {
          PipedInputStream pin = new PipedInputStream(1 << 16);
          PipedOutputStream pout = new PipedOutputStream(pin);
          PrintStream ps = new PrintStream(pout, true);
          final List<String> s = seg;
          builtinThread = new Thread(() -> {
            runBuiltinTo(s, ps, System.err);
            ps.close();
          });
          builtinThread.start();
          prev = pin;
        }
      } else if (findInPath(name) == null) {
        if (prev != null) prev.close();
        System.err.println(name + ": command not found");
        prev = InputStream.nullInputStream();
      } else {
        ProcessBuilder pb = new ProcessBuilder(seg);
        pb.directory(cwd.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(prev == null
            ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(isLast
            ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        procs.add(p);
        if (prev != null) {
          final InputStream in = prev;
          Thread pump = new Thread(() -> {
            try (OutputStream o = p.getOutputStream()) {
              in.transferTo(o);
            } catch (IOException ignored) {}
          });
          pump.setDaemon(true);
          pump.start();
        }
        prev = isLast ? null : p.getInputStream();
        if (isLast) last = p;
      }
    }

    if (last != null) last.waitFor();
    if (builtinThread != null) builtinThread.join();
    for (Process p : procs) if (p != last && p.isAlive()) p.destroy();
  }

  // ---------- main loop ----------
  public static void main(String[] args) throws Exception {
    while (true) {
      stty("-icanon -echo min 1");
      System.out.print("$ ");
      System.out.flush();

      String line = readLine();
      stty("icanon echo");
      if (line == null) break;
      List<String> tokens = parse(line);
      if (tokens.isEmpty()) continue;

      if (tokens.contains("|")) {
        List<List<String>> segs = new ArrayList<>();
        List<String> seg = new ArrayList<>();
        for (String t : tokens) {
          if (t.equals("|")) { segs.add(seg); seg = new ArrayList<>(); }
          else seg.add(t);
        }
        segs.add(seg);
        boolean bad = false;
        for (List<String> s : segs) if (s.isEmpty()) bad = true;
        if (!bad) runPipeline(segs);
        continue;
      }

      File outFile = null, errFile = null;
      boolean outApp = false, errApp = false;
      List<String> cmd = new ArrayList<>();
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        boolean hasNext = i + 1 < tokens.size();
        if (hasNext && (t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>"))) {
          outApp = t.endsWith(">>");
          outFile = cwd.resolve(tokens.get(++i)).toFile();
        } else if (hasNext && (t.equals("2>") || t.equals("2>>"))) {
          errApp = t.equals("2>>");
          errFile = cwd.resolve(tokens.get(++i)).toFile();
        } else {
          cmd.add(t);
        }
      }
      if (cmd.isEmpty()) continue;

      PrintStream out = outFile != null
          ? new PrintStream(new FileOutputStream(outFile, outApp), true) : System.out;
      PrintStream err = errFile != null
          ? new PrintStream(new FileOutputStream(errFile, errApp), true) : System.err;

      String name = cmd.get(0);
      List<String> argv = cmd.subList(1, cmd.size());

      switch (name) {
        case "exit" -> System.exit(0);
        case "pwd" -> out.println(cwd);
        case "echo" -> out.println(String.join(" ", argv));
        case "complete" -> {
          if (argv.size() >= 2 && argv.get(0).equals("-p")) {
            String script = completers.get(argv.get(1));
            if (script == null) {
              err.println("complete: " + argv.get(1) + ": no completion specification");
            } else {
              out.println("complete -C '" + script + "' " + argv.get(1));
            }
          } else if (argv.size() >= 3 && argv.get(0).equals("-C")) {
            completers.put(argv.get(2), argv.get(1));   // command -> script path
          } else if (argv.size() >= 2 && argv.get(0).equals("-r")) {
            completers.remove(argv.get(1));
          }
        }
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
            if (BUILTINS.contains(a)) out.println(a + " is a shell builtin");
            else {
              String f = findInPath(a);
              out.println(f != null ? a + " is " + f : a + ": not found");
            }
          }
        }
        default -> {
          if (findInPath(name) != null) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(cwd.toFile());
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(outFile != null
                ? ProcessBuilder.Redirect.appendTo(outFile) : ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(errFile != null
                ? ProcessBuilder.Redirect.appendTo(errFile) : ProcessBuilder.Redirect.INHERIT);
            pb.start().waitFor();
          } else {
            err.println(name + ": command not found");
          }
        }
      }
      out.flush();
      err.flush();
      if (outFile != null) out.close();
      if (errFile != null) err.close();
    }
  }
}