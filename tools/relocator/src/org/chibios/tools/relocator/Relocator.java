/********************************************************************************
 * Copyright (c) 2026 Giovanni Di Sirio.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/

package org.chibios.tools.relocator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Moves a ChibiStudio installation: rewrites, in the Eclipse files of the
 * bundle, the absolute path the bundle had when its workspaces were created
 * (kept in tools/chibistudio/relocator/bundle_path) with its current path.
 *
 * <p>Files handled, only inside the bundle directory: every Eclipse
 * workspace metadata directory (.metadata), eclipse/configuration and the
 * Eclipse project files (.project, .cproject, .settings/*). Project locations
 * (.location, binary) are rewritten keeping their format; other files are
 * treated as text when they contain no NUL byte. Paths outside the bundle,
 * e.g. a user's own ChibiOS tree or workspace, are never changed.
 *
 * <p>A relocation is a transaction: every file to change is first copied to
 * &lt;file&gt;.relocbak and always rewritten from that copy; bundle_path is
 * committed only when every file succeeded, then the copies are deleted.
 * An interrupted run is completed by the next run, or rolled back if the
 * bundle is back at the recorded path. Only one run at a time (file lock).
 *
 * <p>Usage: {@code java -jar relocator.jar <ChibiStudio directory>}, normally
 * through relocator.sh (Linux) or relocator.bat (Windows), which pass their
 * own bundle directory and use the Java runtime shipped with Eclipse.
 */
public final class Relocator {

  private static final String BAK = ".relocbak";
  private static final String TMP = ".reloc.tmp";
  private static final String LOCATION_PREFIX = "URI//file:";
  private static final int CHUNK_HEADER = 16;

  /**
   * Characters allowed in an install path (besides the Windows drive). No
   * spaces: ChibiOS Eclipse launchers and scripts pass paths unquoted.
   */
  private static final String PATH_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._+-()/";
  /** Characters that cannot precede the start of a path. */
  private static final String NAME_CHARS =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._+-()%/\\";
  /** Characters that may follow the end of a path. */
  private static final String END_CHARS = "/\\\"<>\r\n\t";

  private final boolean windows;
  private final Path bundle;
  private final Path dir;
  private final Path record;
  private final Path log;
  private final Path lockFile;
  private final Path cleanup;
  private final Path inProgress;

  /** A relocation failure: reported to the user, exit code 1. */
  static final class RelocationException extends Exception {
    private static final long serialVersionUID = 1L;

    RelocationException(String message) {
      super(message);
    }
  }

  /** One textual form of a path and its replacement. */
  record Form(String from, String to) {}

  Relocator(Path bundle, boolean windows) throws IOException {
    this.windows = windows;
    this.bundle = bundle.toRealPath();
    this.dir = this.bundle.resolve("tools").resolve("chibistudio").resolve("relocator");
    this.record = dir.resolve("bundle_path");
    this.log = dir.resolve("relocator.log");
    this.lockFile = dir.resolve(".lock");
    this.cleanup = dir.resolve(".cleanup");
    this.inProgress = dir.resolve(".inprogress");
  }

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: java -jar relocator.jar <ChibiStudio directory>");
      System.exit(2);
    }
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    if (windows) {
      Path p = Paths.get(args[0]);
      if (p.getRoot() == null || p.toString().startsWith("\\\\")) {
        System.err.println("ChibiStudio relocation failed: use a local drive path such as D:\\ChibiStudio.");
        System.exit(1);
      }
    }
    Relocator r = null;
    try {
      r = new Relocator(Paths.get(args[0]), windows);
      r.run();
    } catch (RelocationException | IOException | UncheckedIOException e) {
      String msg = e.getMessage();
      System.err.println("ChibiStudio relocation failed: " + msg);
      if (r != null && r.isInside(r.dir) && !Files.isSymbolicLink(r.log)) {
        r.logLine("ERROR: " + msg);
        System.err.println("See " + r.log);
      }
      System.exit(1);
    }
  }

  @SuppressWarnings("try") // the lock is held for the whole block
  void run() throws IOException, RelocationException {
    if (!Files.isRegularFile(record)) {
      throw new RelocationException(record + " not found.");
    }
    String now = normalize(bundle.toString());
    checkPath(now, "install path");
    checkOwnFiles();
    try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         FileLock lock = acquire(ch)) {
      relocateWorkspaces(now);
      if (windows) {
        updateShortcuts();
      }
    }
  }

  void relocateWorkspaces(String now) throws IOException, RelocationException {
    String old = readFirstLine(record);
    if (old.isEmpty()) {
      throw new RelocationException(record + " is empty.");
    }
    boolean pending = Files.exists(cleanup) || Files.exists(inProgress);
    if (!pending && samePath(old, now)) {
      System.out.println("ChibiStudio is already at " + now + ": nothing to do.");
      return;
    }
    // Something will be written: Eclipse must not be using these files.
    checkNotRunning(now);
    deleteQuietly(dir, "bundle_path.tmp", ".cleanup.tmp", ".inprogress.tmp");
    finishCommitted();
    old = readFirstLine(record);
    if (samePath(old, now)) {
      if (Files.exists(inProgress)) {
        rollBack(now);
      } else {
        System.out.println("ChibiStudio is at " + now + ".");
      }
      return;
    }
    checkPath(old, "recorded path");
    relocate(old, now);
  }

  // ------------------------------------------------------------ shortcuts

  /**
   * Windows: the ChibiStudio shortcuts in the bundle directory, i.e. the ones
   * package.ps1 creates ("Chibi Studio GCC x.x.lnk" with a start_gcc*.bat
   * target present in the bundle), are pointed at the bundle's current
   * location (target, working directory, Eclipse icon). Any other shortcut,
   * and copies elsewhere (desktop), are not touched. Run on every start, so
   * it also repairs an interrupted run; a failure is only a warning (the
   * workspaces are already relocated).
   */
  void updateShortcuts() {
    String script = String.join("\n",
        "$ErrorActionPreference = 'Stop'",
        "$ProgressPreference = 'SilentlyContinue'",
        "$root = $env:CHIBISTUDIO_RELOCATOR_ROOT",
        "$icon = Join-Path $root 'eclipse\\eclipse.exe'",
        "$sh = New-Object -ComObject WScript.Shell",
        "$n = 0",
        "foreach ($f in Get-ChildItem -LiteralPath $root -Filter 'Chibi Studio GCC *.lnk' -File) {",
        "  if ($f.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }",
        "  $l = $sh.CreateShortcut($f.FullName)",
        "  if (-not $l.TargetPath) { continue }",
        "  $leaf = Split-Path -Leaf $l.TargetPath",
        "  if ($leaf -notlike 'start_gcc*.bat') { continue }",
        "  $target = Join-Path $root $leaf",
        "  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { continue }",
        "  $newIcon = $l.IconLocation",
        "  if ($l.IconLocation -match '\\\\eclipse\\\\eclipse\\.exe,(-?\\d+)$') { $newIcon = $icon + ',' + $Matches[1] }",
        "  if (($l.TargetPath -ieq $target) -and ($l.WorkingDirectory -ieq $root) -and ($l.IconLocation -ieq $newIcon)) { continue }",
        "  $l.TargetPath = $target",
        "  $l.WorkingDirectory = $root",
        "  $l.IconLocation = $newIcon",
        "  $l.Save()",
        "  $n++",
        "}",
        "Write-Output $n");
    String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    try {
      ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
          "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
      pb.environment().put("CHIBISTUDIO_RELOCATOR_ROOT", bundle.toString());
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);   // progress records, not results
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (p.waitFor() != 0) {
        throw new IOException("PowerShell exit code " + p.exitValue());
      }
      if (!out.equals("0")) {
        System.out.println("Shortcuts updated: " + out + ".");
        logLine("  " + out + " shortcuts updated");
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println("Warning: shortcuts not updated (" + e.getMessage() + "); create them to start_gcc*.bat.");
      logLine("  WARNING: shortcuts not updated: " + e.getMessage());
    }
  }

  /**
   * The relocator's own directory must really be inside the bundle and its
   * files must not be links: nothing may be written outside the bundle.
   */
  void checkOwnFiles() throws IOException, RelocationException {
    if (!isInside(dir)) {
      throw new RelocationException(dir + " is reached through a link.");
    }
    for (Path p : new Path[] {record, log, lockFile, cleanup, inProgress}) {
      if (Files.isSymbolicLink(p)) {
        throw new RelocationException(p + " is a link.");
      }
    }
  }

  /** p exists and no link on the way from the bundle to it. */
  boolean isInside(Path p) {
    try {
      return p.toRealPath().equals(p);
    } catch (IOException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- paths

  /** Absolute path with '/' separators and an upper-case drive letter. */
  String normalize(String p) {
    String s = p;
    if (windows) {
      s = s.replace('\\', '/');
      if (s.length() >= 2 && s.charAt(1) == ':') {
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
      }
    }
    while (s.length() > 1 && s.endsWith("/") && !(windows && s.length() == 3)) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  void checkPath(String p, String what) throws RelocationException {
    String rest = p;
    if (windows) {
      if (p.length() < 3 || !Character.isLetter(p.charAt(0)) || p.charAt(1) != ':' || p.charAt(2) != '/') {
        throw new RelocationException("unsupported " + what + " '" + p + "'.");
      }
      rest = p.substring(2);
    } else if (!p.startsWith("/")) {
      throw new RelocationException("unsupported " + what + " '" + p + "'.");
    }
    for (int i = 0; i < rest.length(); i++) {
      if (PATH_CHARS.indexOf(rest.charAt(i)) < 0) {
        throw new RelocationException("the " + what + " '" + p + "' contains unsupported characters:"
            + " use only letters, digits and . _ + - ( ) /, no spaces");
      }
    }
  }

  boolean samePath(String a, String b) {
    return windows ? a.equalsIgnoreCase(b) : a.equals(b);
  }

  static String uriEncode(String p) {
    return p.replace(" ", "%20");
  }

  /**
   * The textual forms a path takes in Eclipse files, longest first: on Linux
   * the plain path; on Windows C:/x, C:\x, C\:/x (properties) and C\:\\x
   * (properties with escaped backslashes).
   */
  List<Form> forms(String from, String to) {
    List<Form> f = new ArrayList<>();
    if (windows) {
      String fb = from.replace('/', '\\');
      String tb = to.replace('/', '\\');
      f.add(new Form(fb.substring(0, 1) + "\\:" + fb.substring(2).replace("\\", "\\\\"),
                     tb.substring(0, 1) + "\\:" + tb.substring(2).replace("\\", "\\\\")));
      f.add(new Form(from.substring(0, 1) + "\\:" + from.substring(2), to.substring(0, 1) + "\\:" + to.substring(2)));
      f.add(new Form(from, to));
      f.add(new Form(fb, tb));
    } else {
      f.add(new Form(from, to));
    }
    if (from.indexOf(' ') >= 0) {
      List<Form> encoded = new ArrayList<>();
      for (Form x : f) {
        encoded.add(new Form(uriEncode(x.from()), uriEncode(x.to())));
      }
      f.addAll(encoded);
    }
    return f;
  }

  // ----------------------------------------------------------- text files

  /**
   * Rewrites every whole occurrence of the old path, in any of its forms, in
   * one left-to-right pass (replacements are never searched again). Inside a
   * file: URI the new path is %20-encoded.
   */
  String relocateText(String text, List<Form> forms) {
    StringBuilder out = new StringBuilder(text.length() + 256);
    int start = 0;
    int i = 0;
    int n = text.length();
    while (i < n) {
      Form hit = null;
      for (Form f : forms) {
        if (text.regionMatches(windows, i, f.from(), 0, f.from().length())) {
          hit = f;
          break;
        }
      }
      if (hit != null && leftOk(text, i) && rightOk(text, i + hit.from().length())) {
        out.append(text, start, i);
        out.append(inUri(text, i) ? uriEncode(hit.to()) : hit.to());
        i += hit.from().length();
        start = i;
      } else {
        i++;
      }
    }
    out.append(text, start, n);
    return out.toString();
  }

  /** file:/ file:// file:/// (also properties-escaped file\:) before i. */
  static boolean inUri(String s, int i) {
    int j = i;
    int slashes = 0;
    while (j > 0 && s.charAt(j - 1) == '/' && slashes < 3) {
      j--;
      slashes++;
    }
    return s.startsWith("file:", j - 5) || s.startsWith("file\\:", j - 6);
  }

  static boolean leftOk(String s, int i) {
    if (i == 0 || inUri(s, i)) {
      return true;
    }
    char c = s.charAt(i - 1);
    if (NAME_CHARS.indexOf(c) < 0) {
      return true;
    }
    // An escape such as \n or \t right before the path.
    return i >= 2 && s.charAt(i - 2) == '\\' && c >= 'a' && c <= 'z';
  }

  static boolean rightOk(String s, int j) {
    return j >= s.length() || END_CHARS.indexOf(s.charAt(j)) >= 0;
  }

  // ------------------------------------------------------ .location files

  /**
   * Project location: 16-byte chunk header, 2-byte big-endian length,
   * "URI//file:" + path (+ rest of the URI), then the remaining bytes, which
   * are kept unchanged. Returns null when the location is not under the old
   * path.
   */
  byte[] relocateLocation(byte[] b, String from, String to) throws RelocationException {
    if (b.length < CHUNK_HEADER + 2) {
      throw new RelocationException("truncated project location");
    }
    int len = ((b[CHUNK_HEADER] & 0xff) << 8) | (b[CHUNK_HEADER + 1] & 0xff);
    int uriStart = CHUNK_HEADER + 2;
    if (b.length < uriStart + len) {
      throw new RelocationException("truncated project location");
    }
    String uri = new String(b, uriStart, len, StandardCharsets.ISO_8859_1);
    String lead = LOCATION_PREFIX + (windows ? "/" : "");
    String oldUri = lead + uriEncode(from);
    if (!uri.regionMatches(windows, 0, oldUri, 0, oldUri.length())
        || !(uri.length() == oldUri.length() || uri.charAt(oldUri.length()) == '/')) {
      return null;
    }
    byte[] head = (lead + uriEncode(to)).getBytes(StandardCharsets.ISO_8859_1);
    int restLen = len - oldUri.length();
    int newLen = head.length + restLen;
    if (newLen > 0xffff) {
      throw new RelocationException("project location too long");
    }
    byte[] out = new byte[b.length - len + newLen];
    System.arraycopy(b, 0, out, 0, CHUNK_HEADER);
    out[CHUNK_HEADER] = (byte) (newLen >> 8);
    out[CHUNK_HEADER + 1] = (byte) newLen;
    System.arraycopy(head, 0, out, uriStart, head.length);
    System.arraycopy(b, uriStart + oldUri.length(), out, uriStart + head.length, restLen);
    System.arraycopy(b, uriStart + len, out, uriStart + newLen, b.length - uriStart - len);
    return out;
  }

  // ---------------------------------------------------------- transaction

  void relocate(String old, String now) throws IOException, RelocationException {
    System.out.println("Relocating ChibiStudio from " + old + " to " + now + " ...");
    logLine("relocating '" + old + "' -> '" + now + "'");
    List<Path> files = scan();
    List<Form> forms = forms(old, now);
    Set<Path> todo = new TreeSet<>();
    for (Path f : files) {
      String name = f.getFileName().toString();
      if (name.endsWith(BAK)) {
        todo.add(f.resolveSibling(name.substring(0, name.length() - BAK.length())));
      } else if (!name.endsWith(TMP) && !name.endsWith(BAK + ".tmp")) {
        if (!Files.isReadable(f) || !Files.isWritable(f)) {
          throw new RelocationException("cannot read or write " + f);
        }
        if (contains(Files.readAllBytes(f), forms)) {
          todo.add(f);
        }
      }
    }
    writeAtomically(inProgress, now + "\n");
    int locations = 0;
    int texts = 0;
    for (Path f : todo) {
      Path bak = f.resolveSibling(f.getFileName() + BAK);
      if (Files.isSymbolicLink(bak)) {
        throw new RelocationException(bak + " is a link.");
      }
      if (!Files.exists(bak)) {
        Path t = f.resolveSibling(f.getFileName() + BAK + ".tmp");
        Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(t, bak, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
      byte[] src = Files.readAllBytes(bak);
      byte[] dst;
      if (f.getFileName().toString().equals(".location")) {
        dst = relocateLocation(src, old, now);
        if (dst == null) {
          dst = src;
        }
        locations++;
      } else if (isText(src)) {
        String text = new String(src, StandardCharsets.ISO_8859_1);
        dst = relocateText(text, forms).getBytes(StandardCharsets.ISO_8859_1);
        texts++;
      } else {
        logLine("  binary file left unchanged: " + f);
        Files.delete(bak);
        continue;
      }
      Path t = f.resolveSibling(f.getFileName() + TMP);
      Files.deleteIfExists(t);   // a stale temporary file or link, never followed
      Files.write(t, dst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      Files.move(t, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    logLine("  " + locations + " project locations, " + texts + " text files");
    // Commit, then drop the backups.
    writeAtomically(cleanup, now + "\n");
    writeAtomically(record, now + "\n");
    removeBackups();
    Files.deleteIfExists(cleanup);
    Files.deleteIfExists(inProgress);
    System.out.println("ChibiStudio relocated to " + now + " (" + locations + " projects, " + texts + " files).");
  }

  /** A relocation interrupted after .cleanup was written: commit it. */
  void finishCommitted() throws IOException, RelocationException {
    if (!Files.exists(cleanup)) {
      return;
    }
    String target = readFirstLine(cleanup);
    if (target.isEmpty()) {
      throw new RelocationException(cleanup + " is empty.");
    }
    if (!readFirstLine(record).equals(target)) {
      writeAtomically(record, target + "\n");
    }
    removeBackups();
    Files.deleteIfExists(cleanup);
    Files.deleteIfExists(inProgress);
    logLine("completed the relocation to '" + target + "'");
  }

  /** Interrupted before its commit and moved back: restore the originals. */
  void rollBack(String now) throws IOException, RelocationException {
    for (Path f : scan()) {
      String name = f.getFileName().toString();
      if (name.endsWith(BAK)) {
        Files.move(f, f.resolveSibling(name.substring(0, name.length() - BAK.length())),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
    }
    removeBackups();
    Files.deleteIfExists(inProgress);
    logLine("interrupted relocation rolled back, bundle at '" + now + "'");
    System.out.println("Interrupted relocation rolled back: ChibiStudio is at " + now + ".");
  }

  void removeBackups() throws IOException, RelocationException {
    for (Path f : scan()) {
      String name = f.getFileName().toString();
      if (name.endsWith(BAK) || name.endsWith(TMP) || name.endsWith(BAK + ".tmp")) {
        Files.delete(f);
      }
    }
  }

  /**
   * Files the relocator may change: everything under a .metadata directory
   * and the Eclipse project files anywhere in the bundle (the Eclipse
   * installation and tools/ excepted), plus eclipse/configuration.
   */
  List<Path> scan() throws IOException, RelocationException {
    List<Path> out = new ArrayList<>();
    Path eclipse = bundle.resolve("eclipse");
    Path tools = bundle.resolve("tools");
    walk(bundle, p -> !p.startsWith(eclipse) && !p.startsWith(tools), p -> isEclipseFile(bundle.relativize(p)), out);
    Path conf = eclipse.resolve("configuration");
    if (Files.isDirectory(conf, LinkOption.NOFOLLOW_LINKS)) {
      if (!isInside(conf)) {
        throw new RelocationException(conf + " is reached through a link.");
      }
      walk(conf, p -> true, p -> true, out);
    }
    return out;
  }

  /**
   * Regular files under root accepted by fileFilter, descending only into
   * directories accepted by dirFilter that are really inside the bundle:
   * links and Windows junctions (reported by Java as directories) leading
   * elsewhere are skipped, never followed.
   */
  void walk(Path root, Predicate<Path> dirFilter,
            Predicate<Path> fileFilter, List<Path> out) throws RelocationException {
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
          if (!dirFilter.test(d)) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          if (a.isOther() || a.isSymbolicLink() || !isInside(d)) {
            logLine("  linked directory skipped: " + d);
            return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
          if (a.isRegularFile() && !a.isOther() && fileFilter.test(f)) {
            out.add(f);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path f, IOException e) throws IOException {
          throw e;
        }
      });
    } catch (IOException e) {
      throw new RelocationException("cannot list " + root + ": " + e.getMessage());
    }
  }

  /** p is relative to the bundle directory. */
  static boolean isEclipseFile(Path p) {
    for (int i = 0; i < p.getNameCount() - 1; i++) {
      String n = p.getName(i).toString();
      if (n.equals(".metadata") || n.equals(".settings")) {
        return true;
      }
    }
    String name = p.getFileName().toString();
    for (String base : new String[] {".project", ".cproject"}) {
      if (name.equals(base) || name.equals(base + BAK) || name.equals(base + TMP) || name.equals(base + BAK + ".tmp")) {
        return true;
      }
    }
    return false;
  }

  boolean contains(byte[] b, List<Form> forms) {
    String s = new String(b, StandardCharsets.ISO_8859_1);
    if (windows) {
      s = s.toLowerCase(Locale.ROOT);
    }
    for (Form f : forms) {
      if (s.contains(windows ? f.from().toLowerCase(Locale.ROOT) : f.from())) {
        return true;
      }
    }
    return false;
  }

  static boolean isText(byte[] b) {
    for (byte x : b) {
      if (x == 0) {
        return false;
      }
    }
    return true;
  }

  /** An Eclipse started from this bundle would rewrite the files meanwhile. */
  void checkNotRunning(String now) throws RelocationException {
    List<String> keys = new ArrayList<>();
    keys.add((now + "/eclipse/").toLowerCase(Locale.ROOT));
    keys.add((now.replace('/', '\\') + "\\eclipse\\").toLowerCase(Locale.ROOT));
    boolean running = ProcessHandle.allProcesses()
        .filter(h -> h.pid() != ProcessHandle.current().pid())
        .map(h -> h.info().commandLine().orElse(h.info().command().orElse("")).toLowerCase(Locale.ROOT))
        .anyMatch(c -> keys.stream().anyMatch(c::contains));
    if (running) {
      throw new RelocationException("ChibiStudio is running: close it and run the relocator again.");
    }
  }

  // ------------------------------------------------------------- helpers

  static FileLock acquire(FileChannel ch) throws IOException, RelocationException {
    long deadline = System.currentTimeMillis() + 600_000;
    while (true) {
      FileLock l = ch.tryLock();
      if (l != null) {
        return l;
      }
      if (System.currentTimeMillis() > deadline) {
        throw new RelocationException("another relocation is still running.");
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RelocationException("interrupted while waiting for another relocation.");
      }
    }
  }

  static String readFirstLine(Path p) throws IOException {
    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
    return lines.isEmpty() ? "" : lines.get(0).trim();
  }

  void writeAtomically(Path p, String content) throws IOException {
    Path t = p.resolveSibling(p.getFileName() + ".tmp");
    Files.deleteIfExists(t);   // a stale temporary file or link, never followed
    Files.write(t, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    Files.move(t, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  static void deleteQuietly(Path dir, String... prefixes) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        String n = p.getFileName().toString();
        for (String pre : prefixes) {
          if (n.startsWith(pre)) {
            Files.deleteIfExists(p);
          }
        }
      }
    }
  }

  void logLine(String line) {
    if (!isInside(dir) || Files.isSymbolicLink(log)) {
      return;   // never write through a link (see checkOwnFiles): stderr only
    }
    try {
      String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      Files.write(log, (stamp + " " + line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("relocator: cannot write " + log + ": " + e.getMessage());
    }
  }
}
