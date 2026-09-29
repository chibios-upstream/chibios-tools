# ChibiStudio relocator

Moves a ChibiStudio installation to another directory. The Eclipse workspaces
of the bundle record the absolute path they were created at (Windows
`C:/ChibiStudio`, Linux the build directory), kept in
`tools/chibistudio/relocator/bundle_path`. The relocator rewrites that path to
the current location of the bundle in:

- every Eclipse workspace metadata directory (`.metadata`) in the bundle:
  path variables `CHIBIOS`/`CHIBISTUDIO`, project locations (binary
  `.location` files), CDT and JDT data, XML catalogs;
- `eclipse/configuration` (recent workspaces list);
- Eclipse project files (`.project`, `.cproject`, `.settings/*`) in the bundle;
- Windows only: the ChibiStudio shortcuts in the bundle directory
  (`Chibi Studio GCC *.lnk` with a `start_gcc*.bat` target present in the
  bundle): target, working directory and Eclipse icon, through PowerShell's
  `WScript.Shell` (part of Windows). Other shortcuts and copies elsewhere
  (desktop) are not touched; a failure there is only a warning.

Every form Eclipse writes a path in is handled (plain, `file:` URIs with
`%20`, properties escapes such as `C\:/` and `C\:\\` on Windows). Nothing
outside the bundle directory is read or changed, `eclipse/` (except
`configuration`) and `tools/` included. A relocation is a transaction:
backups (`*.relocbak`) first, `bundle_path` committed only when every file
succeeded, backups removed; an interrupted run is completed, or rolled back
if the bundle is back at the recorded path, by the next run. Supported
install paths: letters, digits and `. _ + - ( ) /` (plus the drive on
Windows), no spaces: the ChibiOS Eclipse launchers (debug configuration
generator, FMPP) and `makelaunch.sh` pass paths unquoted.

The user runs it by hand, with ChibiStudio closed, once after unpacking in a
new directory and after every move:

- Windows: `tools\chibistudio\relocator\relocator.bat` (double click works)
- Linux: `bash tools/chibistudio/relocator/relocator.sh`

Both run `relocator.jar` with the Java runtime shipped with Eclipse (JustJ):
nothing to install.

## Build and test

Java 17 or later (the bundle's JustJ JRE has `javac` and `jar`):

```
javac --release 17 -d build/classes src/org/chibios/tools/relocator/Relocator.java
javac --release 17 -cp build/classes -d build/test test/org/chibios/tools/relocator/RelocatorTest.java
java -cp build/classes:build/test org.chibios.tools.relocator.RelocatorTest build   # ';' on Windows
jar --create --file relocator.jar --main-class org.chibios.tools.relocator.Relocator -C build/classes .
```

`RelocatorTest` runs on the host platform: it builds fake bundles (binary
`.location` files, preferences in every path form, project files, files
outside the bundle) under a directory with parentheses and checks
first relocation, a move into a subdirectory of the old path, no-op re-runs,
interruptions before and after the commit (then moved again, or moved back),
and rejected paths. The ChibiStudio build scripts (`build_relocator`) run it
before installing the jar.
