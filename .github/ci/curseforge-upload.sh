#!/usr/bin/env bash
# Upload the built Fabric + NeoForge jars for one release to CurseForge (Upload API).
#
#   CURSEFORGE_TOKEN=... .github/ci/curseforge-upload.sh v2.4.1 [--dry-run]
#
# Changelog: docs/release-notes/<tag>.md (markdown). Game version IDs are looked up by name at
# run time (minecraft_version from gradle.properties, Fabric/NeoForge, Client, Java 25), so a
# Minecraft bump needs no edit here. --dry-run prints the metadata instead of uploading.
#
# The Upload API only handles files: the project description and gallery images stay manual.
set -euo pipefail

PROJECT_ID=1594231
API=https://minecraft.curseforge.com/api
TAG=${1:?usage: curseforge-upload.sh <tag> [--dry-run]}
DRY=${2:-}
VERSION=${TAG#v}
: "${CURSEFORGE_TOKEN:?CURSEFORGE_TOKEN is not set}"

MC=$(grep -E '^minecraft_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
NOTES="docs/release-notes/$TAG.md"
if [ -f "$NOTES" ]; then CHANGELOG=$(cat "$NOTES"); else CHANGELOG="FW Paint $TAG for Minecraft $MC."; fi

auth=(-H "X-Api-Token: $CURSEFORGE_TOKEN")
TYPES=$(curl -fsS "${auth[@]}" "$API/game/version-types")
VERSIONS=$(curl -fsS "${auth[@]}" "$API/game/versions")

# id of the version called $1 whose version-type slug matches the regex $2
vid() {
  jq -r --arg n "$1" --arg t "$2" --argjson types "$TYPES" '
    ($types | map({key: (.id|tostring), value: .slug}) | from_entries) as $slug
    | [.[] | select(.name == $n and (($slug[(.gameVersionTypeID|tostring)] // "") | test($t)))][0].id // empty
  ' <<<"$VERSIONS"
}

MC_ID=$(vid "$MC" '^minecraft-')
CLIENT_ID=$(vid "Client" '^environment$')
JAVA_ID=$(vid "Java 25" '^java$')
FABRIC_ID=$(vid "Fabric" '^modloader$')
NEO_ID=$(vid "NeoForge" '^modloader$')
for v in MC_ID CLIENT_ID JAVA_ID FABRIC_ID NEO_ID; do
  [ -n "${!v}" ] || { echo "CurseForge has no game version for $v (Minecraft $MC)" >&2; exit 1; }
done

upload() { # <jar> <loader name> <loader id> <relations json>
  local jar=$1 loader=$2 loader_id=$3 relations=$4
  [ -f "$jar" ] || { echo "missing $jar" >&2; exit 1; }
  local meta
  meta=$(jq -n --arg cl "$CHANGELOG" --arg name "FW Paint $VERSION ($loader, $MC)" \
    --argjson ids "[$MC_ID, $loader_id, $CLIENT_ID, $JAVA_ID]" --argjson rel "$relations" \
    '{changelog: $cl, changelogType: "markdown", displayName: $name, gameVersions: $ids,
      releaseType: "release"} + (if ($rel | length) > 0 then {relations: {projects: $rel}} else {} end)')
  if [ "$DRY" = "--dry-run" ]; then
    echo "== $jar"; jq 'del(.changelog)' <<<"$meta"
    return
  fi
  echo "Uploading $jar ..."
  curl -sS --fail-with-body "${auth[@]}" -F "metadata=$meta" -F "file=@$jar" "$API/projects/$PROJECT_ID/upload-file"
  echo
}

upload "fabric/build/libs/fw-paint-fabric-$VERSION.jar" Fabric "$FABRIC_ID" \
  '[{"slug": "fabric-api", "type": "requiredDependency"}]'
upload "neoforge/build/libs/fw-paint-neoforge-$VERSION.jar" NeoForge "$NEO_ID" '[]'
