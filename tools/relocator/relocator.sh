#!/bin/bash
# ChibiStudio relocator (Linux): run it once after unpacking ChibiStudio in a
# directory different from the one it was built in, or after moving it.
# Close ChibiStudio first. It updates the Eclipse workspaces and project
# locations to the current directory; see relocator.jar for the details.
#
#   bash <ChibiStudio>/tools/chibistudio/relocator/relocator.sh

RELOCATOR_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
CHIBISTUDIO="$(cd "$RELOCATOR_DIR/../../.." && pwd)"

# The Java runtime shipped with Eclipse (JustJ).
JAVA=""
for d in "$CHIBISTUDIO"/eclipse/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.linux.x86_64_*/jre; do
  [ -x "$d/bin/java" ] && JAVA="$d/bin/java"
done
if [ -z "$JAVA" ]; then
  echo "ChibiStudio relocation failed: Java runtime not found under $CHIBISTUDIO/eclipse/plugins" >&2
  exit 1
fi

exec "$JAVA" -jar "$RELOCATOR_DIR/relocator.jar" "$CHIBISTUDIO"
