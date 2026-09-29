/********************************************************************************
 * Copyright (c) 2026 Giovanni Di Sirio.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/

/*
 * Tests of the ChibiStudio relocator on the host platform (Linux or Windows).
 * Not shipped. Build and run from tools/relocator:
 *
 *   javac --release 17 -d build src/org/chibios/tools/relocator/Relocator.java \
 *         test/org/chibios/tools/relocator/RelocatorTest.java
 *   java -cp build org.chibios.tools.relocator.RelocatorTest [work dir]
 *
 * Every scenario builds a fake bundle (workspace metadata, binary .location
 * files, preferences in every path form, project files, a file outside the
 * bundle) in a work directory whose name contains parentheses; install paths
 * with spaces are rejected (scenarioRejectedPath).
 */

package org.chibios.tools.relocator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class RelocatorTest {

  private static final boolean WIN =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  /** The path the workspaces were built at. */
  private static final String OLD = WIN ? "C:/ChibiStudio" : "/home/chibios/Projects/ChibiStudio";
  private static final String OUTSIDE = WIN ? "E:/Other/Project" : "/opt/other/project";
  private static final String LOC = ".metadata/.plugins/org.eclipse.core.resources/.projects/";
  private static final String PREFS = ".metadata/.plugins/org.eclipse.core.runtime/.settings/test.prefs";

  private static int failures;
  private static int checks;

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"))
        .resolve("relocator_test(1)").toAbsolutePath();
    deleteTree(work);
    Files.createDirectories(work);

    unitText();
    unitLocation();
    scenarioMoves(work.resolve("moves"));
    scenarioInterruptedThenMoved(work.resolve("interrupted"));
    scenarioInterruptedThenBack(work.resolve("back"));
    scenarioCommittedNotCleaned(work.resolve("committed"));
    scenarioRejectedPath(work.resolve("rejected"));
    scenarioLinkedLog(work.resolve("linked"));
    scenarioLinkedParents(work.resolve("linkedparents"));
    scenarioNestedLinks(work.resolve("nested"));
    if (WIN) {
      scenarioShortcuts(work.resolve("shortcuts"));
    }
    if (!WIN) {   // Windows cannot create names ending with a space
      scenarioTrailingSpace(work.resolve("trailing"));
    }

    System.out.println((failures == 0 ? "ALL PASSED" : failures + " FAILED") + " (" + checks + " checks, "
        + (WIN ? "Windows" : "Linux") + ")");
    if (failures == 0) {
      deleteTree(work);
    }
    System.exit(failures == 0 ? 0 : 1);
  }

  // ------------------------------------------------------------------ units

  static void unitText() throws Exception {
    Relocator lx = new Relocator(Paths.get("."), false);
    String o = "/home/chibios/Projects/ChibiStudio";
    String n = "/home/u/My Dir (2)/ChibiStudio";
    List<?> f = lxForms(lx, o, n);
    check("lx plain", lx.relocateText("a=" + o + "/x\n", cast(f)), "a=" + n + "/x\n");
    check("lx end", lx.relocateText("CHIBISTUDIO=" + o + "\r\n", cast(f)), "CHIBISTUDIO=" + n + "\r\n");
    check("lx recent", lx.relocateText("R=" + o + "/w1\\n" + o + "/w2", cast(f)), "R=" + n + "/w1\\n" + n + "/w2");
    check("lx uri", lx.relocateText("file:" + o + "/x", cast(f)), "file:" + n.replace(" ", "%20") + "/x");
    check("lx uri3", lx.relocateText("file://" + o + "/x", cast(f)), "file://" + n.replace(" ", "%20") + "/x");
    check("lx uri esc", lx.relocateText("file\\:" + o + "/x", cast(f)), "file\\:" + n.replace(" ", "%20") + "/x");
    check("lx left", lx.relocateText("/other" + o + "/x", cast(f)), "/other" + o + "/x");
    check("lx right", lx.relocateText(o + "2/x " + o + "_old", cast(f)), o + "2/x " + o + "_old");
    check("lx xml", lx.relocateText("<a p=\"" + o + "/e\"/>", cast(f)), "<a p=\"" + n + "/e\"/>");
    String sub = o + "/archive/ChibiStudio";
    check("lx into subdir", lx.relocateText("a=" + o + "/x", cast(lxForms(lx, o, sub))), "a=" + sub + "/x");

    Relocator w = new Relocator(Paths.get("."), true);
    String wo = "C:/ChibiStudio";
    String wn = "D:/Work Dir/CS (1)";
    List<?> g = lxForms(w, wo, wn);
    check("w prefs", w.relocateText("pathvariable.CHIBIOS=C\\:/ChibiStudio/chibios_21.11.5\r\n", cast(g)),
        "pathvariable.CHIBIOS=D\\:/Work Dir/CS (1)/chibios_21.11.5\r\n");
    check("w prefs end", w.relocateText("pathvariable.CHIBISTUDIO=C\\:/ChibiStudio\r\n", cast(g)),
        "pathvariable.CHIBISTUDIO=D\\:/Work Dir/CS (1)\r\n");
    check("w jdt", w.relocateText("path\\=\"C\\:\\\\ChibiStudio\\\\eclipse\\\\jre\"", cast(g)),
        "path\\=\"D\\:\\\\Work Dir\\\\CS (1)\\\\eclipse\\\\jre\"");
    check("w recent", w.relocateText("R=C\\:\\\\ChibiStudio\\\\w1\\nC\\:\\\\ChibiStudio\\\\w2", cast(g)),
        "R=D\\:\\\\Work Dir\\\\CS (1)\\\\w1\\nD\\:\\\\Work Dir\\\\CS (1)\\\\w2");
    check("w xml", w.relocateText("<x path=\"C:\\ChibiStudio\\eclipse\"/>", cast(g)),
        "<x path=\"D:\\Work Dir\\CS (1)\\eclipse\"/>");
    check("w sc", w.relocateText("value=\"C:/ChibiStudio/chibios\"", cast(g)), "value=\"D:/Work Dir/CS (1)/chibios\"");
    check("w uri", w.relocateText("file:/C:/ChibiStudio/x", cast(g)), "file:/D:/Work%20Dir/CS%20(1)/x");
    check("w case", w.relocateText("x=c:/chibistudio/y", cast(g)), "x=D:/Work Dir/CS (1)/y");
    check("w other dir", w.relocateText("C:\\ChibiStudio_setup\\x C:/ChibiStudio2", cast(g)),
        "C:\\ChibiStudio_setup\\x C:/ChibiStudio2");
    check("w left", w.relocateText("XC:/ChibiStudio/x", cast(g)), "XC:/ChibiStudio/x");
    // Sibling directories whose names continue with , ' ; : ] & = are not the bundle.
    for (String c : new String[] {",", "'", ";", "]", "&", "="}) {
      check("w sibling " + c, w.relocateText("<l>C:/ChibiStudio" + c + "other/t</l>", cast(g)),
          "<l>C:/ChibiStudio" + c + "other/t</l>");
      check("lx sibling " + c, lx.relocateText("<l>" + o + c + "other/t</l>", cast(f)), "<l>" + o + c + "other/t</l>");
    }
  }

  static void unitLocation() throws Exception {
    Relocator lx = new Relocator(Paths.get("."), false);
    byte[] b = location(false, "/home/chibios/Projects/ChibiStudio/chibios_x/demos/P1");
    byte[] r = lx.relocateLocation(b, "/home/chibios/Projects/ChibiStudio", "/u/A B/CS");
    check("lx location", uriOf(r), "URI//file:/u/A%20B/CS/chibios_x/demos/P1");
    checkTrue("lx location tail", tailEquals(b, r));
    checkTrue("lx location outside", lx.relocateLocation(location(false, "/opt/x"), "/home/chibios/Projects/ChibiStudio", "/u") == null);
    Relocator w = new Relocator(Paths.get("."), true);
    byte[] wb = location(true, "C:/ChibiStudio/chibios_x/demos/P1");
    byte[] wr = w.relocateLocation(wb, "C:/ChibiStudio", "D:/A B/CS");
    check("w location", uriOf(wr), "URI//file:/D:/A%20B/CS/chibios_x/demos/P1");
    checkTrue("w location tail", tailEquals(wb, wr));
  }

  @SuppressWarnings("unchecked")
  static List<?> lxForms(Relocator r, String o, String n) {
    return r.forms(o, n);
  }

  @SuppressWarnings("unchecked")
  static <T> List<T> cast(List<?> l) {
    return (List<T>) l;
  }

  // -------------------------------------------------------------- scenarios

  /** First relocation, a second move into a subdirectory, a no-op rerun. */
  static void scenarioMoves(Path base) throws Exception {
    Path a = base.resolve("A(1)").resolve("ChibiStudio");
    Path outside = makeBundle(a, OLD);
    run("moves: first", a);
    verifyBundle("moves: first", a, outside);
    Path tmp = base.resolve("tmpmove");
    Files.move(a, tmp);
    Path b = base.resolve("A(1)").resolve("ChibiStudio").resolve("archive").resolve("ChibiStudio");
    Files.createDirectories(b.getParent());
    Files.move(tmp, b);
    run("moves: into own subdir", b);
    verifyBundle("moves: into own subdir", b, outside);
    String before = snapshot(b);
    run("moves: rerun", b);
    check("moves: rerun changes nothing", snapshot(b), before);
  }

  /** Interrupted before the commit, then moved elsewhere and rerun. */
  static void scenarioInterruptedThenMoved(Path base) throws Exception {
    Path a = base.resolve("A").resolve("ChibiStudio");
    Path outside = makeBundle(a, OLD);
    fakeInterruption(a, false);
    Path c = base.resolve("C").resolve("ChibiStudio");
    Files.createDirectories(c.getParent());
    Files.move(a, c);
    run("interrupted+moved", c);
    verifyBundle("interrupted+moved", c, outside);
  }

  /** Interrupted before the commit, then moved back to the recorded path. */
  static void scenarioInterruptedThenBack(Path base) throws Exception {
    Path a = base.resolve("A").resolve("ChibiStudio");
    makeBundle(a, OLD);
    run("back: first", a);
    String original = snapshot(a);
    fakeInterruption(a, false);
    run("back: rollback", a);
    check("back: files restored", snapshot(a), original);
  }

  /** Committed (.cleanup written) but not cleaned, then rerun in place. */
  static void scenarioCommittedNotCleaned(Path base) throws Exception {
    Path a = base.resolve("A").resolve("ChibiStudio");
    Path outside = makeBundle(a, OLD);
    fakeInterruption(a, true);
    run("committed: rerun", a);
    verifyBundle("committed", a, outside);
  }

  static void scenarioRejectedPath(Path base) throws Exception {
    for (String bad : new String[] {"bad#dir", "bad dir", "Prova (1)"}) {
      Path a = base.resolve(bad).resolve("ChibiStudio");
      makeBundle(a, OLD);
      String before = snapshot(a);
      try {
        new Relocator(a, WIN).run();
        fail("rejected: unsupported path accepted: " + bad);
      } catch (Relocator.RelocationException e) {
        check("rejected '" + bad + "': nothing changed", snapshot(a), before);
      }
    }
  }

  /** relocator.log linked outside the bundle: refused, nothing written there. */
  static void scenarioLinkedLog(Path base) throws Exception {
    Path a = base.resolve("A").resolve("ChibiStudio");
    makeBundle(a, OLD);
    Path target = base.resolve("outside.txt");
    Files.writeString(target, "keep\n");
    try {
      Files.createSymbolicLink(a.resolve("tools/chibistudio/relocator/relocator.log"), target);
    } catch (IOException | UnsupportedOperationException e) {
      System.out.println("skipped linked-log scenario (cannot create links here: " + e.getMessage() + ")");
      return;
    }
    String before = snapshot(a);
    try {
      new Relocator(a, WIN).run();
      fail("linked: a linked relocator.log was accepted");
    } catch (Relocator.RelocationException e) {
      check("linked: outside file unchanged", Files.readString(target), "keep\n");
      check("linked: nothing changed", snapshot(a), before);
    }
  }

  /**
   * Linked parents: tools/chibistudio/relocator or eclipse/ linked to an
   * outside directory. Refused, nothing written outside (error log included).
   */
  static void scenarioLinkedParents(Path base) throws Exception {
    for (String which : new String[] {"relocator", "eclipse"}) {
      Path a = base.resolve(which).resolve("ChibiStudio");
      makeBundle(a, OLD);
      Path outside = base.resolve(which + "-outside");
      Path linkAt = which.equals("relocator") ? a.resolve("tools/chibistudio/relocator") : a.resolve("eclipse");
      Files.move(linkAt, outside);
      try {
        Files.createSymbolicLink(linkAt, outside);
      } catch (IOException | UnsupportedOperationException e) {
        System.out.println("skipped linked-parent scenarios (cannot create links here)");
        return;
      }
      String before = snapshot(outside);
      Relocator r = new Relocator(a, WIN);
      try {
        r.run();
        fail("linked " + which + ": accepted");
      } catch (Relocator.RelocationException e) {
        r.logLine("ERROR: " + e.getMessage());   // what main() does
        check("linked " + which + ": outside unchanged", snapshot(outside), before);
        checkTrue("linked " + which + ": no log outside", !Files.exists(outside.resolve("relocator.log")));
      }
    }
  }

  /**
   * Nested links leading outside (Windows: directory junctions, which Java
   * reports as plain directories; Linux: symbolic links) at
   * workspace_b/.metadata and eclipse/configuration/.settings: the
   * relocation succeeds for the rest, the outside files stay unchanged.
   */
  static void scenarioNestedLinks(Path base) throws Exception {
    Path a = base.resolve("A").resolve("ChibiStudio");
    Path outsideRef = makeBundle(a, OLD);
    Path[] at = {a.resolve("workspace_b/.metadata"), a.resolve("eclipse/configuration/.settings")};
    Path[] out = {base.resolve("ext-metadata"), base.resolve("ext-settings")};
    for (int i = 0; i < at.length; i++) {
      Files.createDirectories(out[i]);
      Files.writeString(out[i].resolve("x.prefs"), "p=" + props(OLD) + "/x\n");
      if (Files.exists(at[i])) {
        Path keep = at[i].resolveSibling(".settings.orig");
        Files.move(at[i], keep);
      }
      Files.createDirectories(at[i].getParent());
      if (!link(at[i], out[i])) {
        System.out.println("skipped nested-link scenario (cannot create links or junctions here)");
        return;
      }
    }
    String before0 = Files.readString(out[0].resolve("x.prefs"));
    String before1 = Files.readString(out[1].resolve("x.prefs"));
    run("nested links", a);
    check("nested links: outside metadata unchanged", Files.readString(out[0].resolve("x.prefs")), before0);
    check("nested links: outside settings unchanged", Files.readString(out[1].resolve("x.prefs")), before1);
    check("nested links: outside workspace unchanged", Files.readString(outsideRef), "CHIBISTUDIO=" + OLD + "\n");
    check("nested links: bundle relocated",
        Relocator.readFirstLine(a.resolve("tools/chibistudio/relocator/bundle_path")),
        new Relocator(a, WIN).normalize(a.toRealPath().toString()));
  }

  /**
   * Windows shortcuts: "Chibi Studio GCC 15.3.lnk" in the bundle pointing to
   * C:\ChibiStudio\start_gcc153.bat is repointed to the bundle (and a stale
   * icon alone is repaired); another shortcut in the bundle with a target of
   * the same name elsewhere, and a shortcut outside the bundle, are left
   * alone.
   */
  static void scenarioShortcuts(Path base) throws Exception {
    Path a = base.resolve("A(1)").resolve("ChibiStudio");
    makeBundle(a, OLD);
    Files.writeString(a.resolve("start_gcc153.bat"), "@echo off\r\n");
    Path lnk = a.resolve("Chibi Studio GCC 15.3.lnk");
    Path other = a.resolve("Other.lnk");
    Path desktop = base.resolve("Desktop copy.lnk");
    ps("$l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:L1); $l.TargetPath = 'C:\\ChibiStudio\\start_gcc153.bat';"
        + " $l.WorkingDirectory = 'C:\\ChibiStudio'; $l.IconLocation = 'C:\\ChibiStudio\\eclipse\\eclipse.exe,0'; $l.Save();"
        + " $l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:L2); $l.TargetPath = 'C:\\OtherStudio\\start_gcc153.bat'; $l.Save();"
        + " $l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:L3); $l.TargetPath = 'C:\\ChibiStudio\\start_gcc153.bat'; $l.Save()",
        lnk, other, desktop);
    run("shortcuts", a);
    String root = a.toRealPath().toString();
    check("shortcuts: bundle shortcut", ps("$l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:L1);"
        + " $l.TargetPath + '|' + $l.WorkingDirectory + '|' + $l.IconLocation", lnk, other, desktop),
        root + "\\start_gcc153.bat|" + root + "|" + root + "\\eclipse\\eclipse.exe,0");
    check("shortcuts: other target untouched", ps("(New-Object -ComObject WScript.Shell).CreateShortcut($env:L2).TargetPath",
        lnk, other, desktop), "C:\\OtherStudio\\start_gcc153.bat");
    // A stale icon alone is repaired too.
    ps("$l = (New-Object -ComObject WScript.Shell).CreateShortcut($env:L1);"
        + " $l.IconLocation = 'C:\\ChibiStudio\\eclipse\\eclipse.exe,0'; $l.Save()", lnk, other, desktop);
    run("shortcuts: icon", a);
    check("shortcuts: icon repaired", ps("(New-Object -ComObject WScript.Shell).CreateShortcut($env:L1).IconLocation",
        lnk, other, desktop), root + "\\eclipse\\eclipse.exe,0");
    check("shortcuts: desktop copy untouched", ps("(New-Object -ComObject WScript.Shell).CreateShortcut($env:L3).TargetPath",
        lnk, other, desktop), "C:\\ChibiStudio\\start_gcc153.bat");
  }

  /** Runs a PowerShell snippet with $env:L1..L3 set; returns its trimmed output. */
  static String ps(String script, Path l1, Path l2, Path l3) throws Exception {
    String enc = java.util.Base64.getEncoder().encodeToString(
        ("$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue'; " + script)
            .getBytes(StandardCharsets.UTF_16LE));
    ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
        "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc).redirectError(ProcessBuilder.Redirect.DISCARD);
    pb.environment().put("L1", l1.toString());
    pb.environment().put("L2", l2.toString());
    pb.environment().put("L3", l3.toString());
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    if (p.waitFor() != 0) {
      throw new IOException("PowerShell test helper failed (exit " + p.exitValue() + "): " + out);
    }
    return out;
  }

  /** Directory junction on Windows, symbolic link elsewhere. */
  static boolean link(Path at, Path target) {
    try {
      if (WIN) {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", at.toString(), target.toString())
            .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor() == 0 && Files.isDirectory(at);
      }
      Files.createSymbolicLink(at, target);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** An install directory whose name ends with a space: refused. */
  static void scenarioTrailingSpace(Path base) throws Exception {
    Path a = base.resolve("ChibiStudio ");
    makeBundle(a, OLD);
    String before = snapshot(a);
    try {
      new Relocator(a, WIN).run();
      fail("trailing: a name ending with a space was accepted");
    } catch (Relocator.RelocationException e) {
      check("trailing: nothing changed", snapshot(a), before);
    }
  }

  /**
   * Simulates a crash in the middle of a relocation to a phantom directory:
   * backups of the originals, files half rewritten, .inprogress written
   * (and, if committed, .cleanup + bundle_path pointing at the current dir).
   */
  static void fakeInterruption(Path bundle, boolean committed) throws Exception {
    Relocator r = new Relocator(bundle, WIN);
    String recorded = Relocator.readFirstLine(bundle.resolve("tools/chibistudio/relocator/bundle_path"));
    String phantom = r.normalize(bundle.getParent().resolve("phantom").toString());
    String target = committed ? r.normalize(bundle.toRealPath().toString()) : phantom;
    List<Path> files = r.scan();
    int i = 0;
    for (Path f : files) {
      byte[] src = Files.readAllBytes(f);
      boolean loc = f.getFileName().toString().equals(".location");
      if (!r.contains(src, cast(r.forms(recorded, target))) || (!loc && !Relocator.isText(src))) {
        continue;   // the relocator never backs up or rewrites binary files
      }
      Files.copy(f, f.resolveSibling(f.getFileName() + ".relocbak"), StandardCopyOption.REPLACE_EXISTING);
      if (committed || i++ % 2 == 0) {
        byte[] dst = f.getFileName().toString().equals(".location")
            ? r.relocateLocation(src, recorded, target)
            : r.relocateText(new String(src, StandardCharsets.ISO_8859_1), cast(r.forms(recorded, target)))
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(f, dst == null ? src : dst);
      }
    }
    Path dir = bundle.resolve("tools/chibistudio/relocator");
    Files.writeString(dir.resolve(".inprogress"), target + "\n");
    if (committed) {
      Files.writeString(dir.resolve(".cleanup"), target + "\n");
    }
  }

  // ------------------------------------------------------------- the bundle

  /** Creates a fake bundle recorded at old; returns a file outside it. */
  static Path makeBundle(Path b, String old) throws Exception {
    Files.createDirectories(b.resolve("tools/chibistudio/relocator"));
    Files.writeString(b.resolve("tools/chibistudio/relocator/bundle_path"), old + "\n");
    for (String p : new String[] {"P1", "ChibiOS Tools"}) {
      Path d = b.resolve("workspace_a").resolve(LOC + p);
      Files.createDirectories(d);
      Files.write(d.resolve(".location"), location(WIN, old + "/chibios_x/demos/" + p));
    }
    Path d = b.resolve("workspace_a").resolve(LOC + "Outside");
    Files.createDirectories(d);
    Files.write(d.resolve(".location"), location(WIN, OUTSIDE));
    Path prefs = b.resolve("workspace_a").resolve(PREFS);
    Files.createDirectories(prefs.getParent());
    Files.writeString(prefs, prefsText(old));
    Path conf = b.resolve("eclipse/configuration/.settings/org.eclipse.ui.ide.prefs");
    Files.createDirectories(conf.getParent());
    Files.writeString(conf, WIN ? "RECENT_WORKSPACES=" + propsBs(old) + "\\\\workspace_a\r\n"
                                : "RECENT_WORKSPACES=" + old + "/workspace_a\n");
    Path proj = b.resolve("chibios_x/demos/P1");
    Files.createDirectories(proj.resolve(".settings"));
    Files.writeString(proj.resolve(".project"), "<location>" + native_(old) + native_("/chibios_x/os") + "</location>\n");
    Files.writeString(proj.resolve(".cproject"), "<entry path=\"" + old + "/chibios_x/os/include\"/>\n");
    Files.writeString(proj.resolve(".settings/language.settings.xml"), "<x loc=\"" + old + "/chibios_x\"/>\n");
    byte[] bin = (old + "/binary").getBytes(StandardCharsets.ISO_8859_1);
    byte[] withNul = new byte[bin.length + 2];
    System.arraycopy(bin, 0, withNul, 1, bin.length);
    Files.write(b.resolve("workspace_a/.metadata/cache.bin"), withNul);
    // Tools and the Eclipse installation are never touched.
    Files.createDirectories(b.resolve("tools/other"));
    Files.writeString(b.resolve("tools/other/.project"), old + "/untouched\n");
    // A user's own workspace outside the bundle referring to it.
    Path outside = b.getParent().resolve("my_workspace/.metadata/x.prefs");
    Files.createDirectories(outside.getParent());
    Files.writeString(outside, "CHIBISTUDIO=" + old + "\n");
    return outside;
  }

  static String prefsText(String p) {
    String nl = WIN ? "\r\n" : "\n";
    StringBuilder s = new StringBuilder();
    s.append("pathvariable.CHIBIOS=").append(props(p)).append("/chibios_x").append(nl);
    s.append("pathvariable.CHIBISTUDIO=").append(props(p)).append(nl);
    s.append("catalog=file:").append(WIN ? "/" : "").append(p).append("/workspace_a/c.xml").append(nl);
    s.append("jre=path\\=\"").append(WIN ? propsBs(p) + "\\\\eclipse\\\\jre" : p + "/eclipse/jre").append("\"").append(nl);
    // A sibling directory of the recorded path: never changed.
    s.append("other=").append(WIN ? "C:\\ChibiStudio_setup\\x" : OLD + "_setup/x").append(nl);
    return s.toString();
  }

  static String props(String p) {
    return WIN ? p.charAt(0) + "\\:" + p.substring(2) : p;
  }

  static String propsBs(String p) {
    return p.charAt(0) + "\\:" + p.substring(2).replace("/", "\\\\");
  }

  static String native_(String p) {
    return WIN ? p.replace('/', '\\') : p;
  }

  static void verifyBundle(String what, Path b, Path outside) throws Exception {
    Relocator r = new Relocator(b, WIN);
    String now = r.normalize(b.toRealPath().toString());
    check(what + ": bundle_path", Relocator.readFirstLine(b.resolve("tools/chibistudio/relocator/bundle_path")), now);
    String enc = Relocator.uriEncode(now);
    check(what + ": location P1", uriOf(Files.readAllBytes(b.resolve("workspace_a").resolve(LOC + "P1/.location"))),
        "URI//file:" + (WIN ? "/" : "") + enc + "/chibios_x/demos/P1");
    check(what + ": location outside", uriOf(Files.readAllBytes(b.resolve("workspace_a").resolve(LOC + "Outside/.location"))),
        "URI//file:" + (WIN ? "/" : "") + OUTSIDE);
    String prefs = Files.readString(b.resolve("workspace_a").resolve(PREFS));
    check(what + ": prefs", prefs, prefsText(now).replace("file:" + (WIN ? "/" : "") + now, "file:" + (WIN ? "/" : "") + enc));
    check(what + ": .project", Files.readString(b.resolve("chibios_x/demos/P1/.project")),
        "<location>" + native_(now) + native_("/chibios_x/os") + "</location>\n");
    check(what + ": .cproject", Files.readString(b.resolve("chibios_x/demos/P1/.cproject")),
        "<entry path=\"" + now + "/chibios_x/os/include\"/>\n");
    check(what + ": tools untouched", Files.readString(b.resolve("tools/other/.project")), OLD + "/untouched\n");
    check(what + ": outside untouched", Files.readString(outside), "CHIBISTUDIO=" + OLD + "\n");
    checkTrue(what + ": binary untouched", new String(Files.readAllBytes(b.resolve("workspace_a/.metadata/cache.bin")),
        StandardCharsets.ISO_8859_1).contains(OLD + "/binary"));
    List<String> left = new ArrayList<>();
    try (Stream<Path> s = Files.walk(b)) {
      s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
       .filter(n -> n.endsWith(".relocbak") || n.endsWith(".tmp") || n.equals(".cleanup") || n.equals(".inprogress"))
       .forEach(left::add);
    }
    check(what + ": no transaction leftovers", left.toString(), "[]");
  }

  // ---------------------------------------------------------------- helpers

  static void run(String what, Path bundle) {
    try {
      new Relocator(bundle, WIN).run();
    } catch (Exception e) {
      fail(what + ": " + e);
    }
  }

  static byte[] location(boolean win, String path) {
    byte[] header = {0x40, (byte) 0xb1, (byte) 0x8b, (byte) 0x81, 0x23, (byte) 0xbc, 0x00, 0x14,
                     0x1a, 0x25, (byte) 0x96, (byte) 0xe7, (byte) 0xa3, (byte) 0x93, (byte) 0xbe, 0x1e};
    byte[] tail = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xc0, 0x58, (byte) 0xfb, (byte) 0xf3, 0x23,
                   (byte) 0xbc, 0x00, 0x14, 0x1a, 0x51, (byte) 0xf3, (byte) 0x8c, 0x7b, (byte) 0xbb, 0x77, (byte) 0xc6};
    byte[] uri = ("URI//file:" + (win ? "/" : "") + Relocator.uriEncode(path)).getBytes(StandardCharsets.ISO_8859_1);
    byte[] out = new byte[16 + 2 + uri.length + tail.length];
    System.arraycopy(header, 0, out, 0, 16);
    out[16] = (byte) (uri.length >> 8);
    out[17] = (byte) uri.length;
    System.arraycopy(uri, 0, out, 18, uri.length);
    System.arraycopy(tail, 0, out, 18 + uri.length, tail.length);
    return out;
  }

  static String uriOf(byte[] b) {
    int len = ((b[16] & 0xff) << 8) | (b[17] & 0xff);
    return new String(b, 18, len, StandardCharsets.ISO_8859_1);
  }

  static boolean tailEquals(byte[] a, byte[] b) {
    int la = ((a[16] & 0xff) << 8) | (a[17] & 0xff);
    int lb = ((b[16] & 0xff) << 8) | (b[17] & 0xff);
    if (a.length - la != b.length - lb) {
      return false;
    }
    for (int i = 0; i < 16; i++) {
      if (a[i] != b[i]) {
        return false;
      }
    }
    for (int i = 0; i < a.length - 18 - la; i++) {
      if (a[18 + la + i] != b[18 + lb + i]) {
        return false;
      }
    }
    return true;
  }

  static String snapshot(Path b) throws IOException {
    StringBuilder s = new StringBuilder();
    try (Stream<Path> st = Files.walk(b)) {
      for (Path p : (Iterable<Path>) st.sorted()::iterator) {
        if (Files.isRegularFile(p) && !p.getFileName().toString().equals("relocator.log")
            && !p.getFileName().toString().equals(".lock")) {
          s.append(b.relativize(p)).append('=')
           .append(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)).append('\n');
        }
      }
    }
    return s.toString();
  }

  static void check(String what, String got, String expected) {
    checks++;
    if (!got.equals(expected)) {
      fail(what + "\n    got:      " + got.replace("\n", "|") + "\n    expected: " + expected.replace("\n", "|"));
    }
  }

  static void checkTrue(String what, boolean ok) {
    checks++;
    if (!ok) {
      fail(what);
    }
  }

  static void fail(String what) {
    failures++;
    System.out.println("FAIL " + what);
  }

  /** Deletes the test's own tree; links and junctions are removed, never entered. */
  static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(p, new java.nio.file.SimpleFileVisitor<Path>() {
      @Override
      public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a)
          throws IOException {
        if (a.isOther() || a.isSymbolicLink()) {
          Files.delete(d);   // the junction itself
          return java.nio.file.FileVisitResult.SKIP_SUBTREE;
        }
        return java.nio.file.FileVisitResult.CONTINUE;
      }

      @Override
      public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a)
          throws IOException {
        Files.delete(f);
        return java.nio.file.FileVisitResult.CONTINUE;
      }

      @Override
      public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        Files.delete(d);
        return java.nio.file.FileVisitResult.CONTINUE;
      }
    });
  }
}
