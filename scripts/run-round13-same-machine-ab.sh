#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 BASELINE_PROJECT CANDIDATE_PROJECT ARCHIVE_ZIP EMPTY_OUTPUT_DIR" >&2
  exit 2
fi

baseline=$(cd "$1" && pwd -P)
candidate=$(cd "$2" && pwd -P)
archive=$(cd "$(dirname "$3")" && pwd -P)/$(basename "$3")
output=$4
runner=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/$(basename "${BASH_SOURCE[0]}")
importer="$candidate/scripts/round13-ab-evidence.mjs"
samples=30
warmups=3

if [[ ! -f "$archive" || ! -f "$importer" ]]; then
  echo "archive or importer is missing" >&2
  exit 2
fi
if [[ -e "$output" ]]; then
  if [[ ! -d "$output" || -n "$(ls -A "$output")" ]]; then
    echo "output must be a unique nonexistent or empty directory: $output" >&2
    exit 2
  fi
else
  mkdir -p "$output"
fi
output=$(cd "$output" && pwd -P)
mkdir "$output/baseline" "$output/candidate"

export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
campaign_id=$(node -e 'console.log(require("node:crypto").randomUUID())')
preflight="$output/preflight-bindings.json"
ledger="$output/completion-ledger.jsonl"
postflight="$output/postflight-bindings.json"

node "$importer" --capture-preflight \
  --raw "$output" \
  --baseline "$baseline" \
  --candidate "$candidate" \
  --archive "$archive" \
  --runner "$runner" \
  --campaign-id "$campaign_id" \
  --samples "$samples" \
  --warmups "$warmups" \
  --output "$preflight"

run_one() {
  local project=$1
  local variant=$2
  local pair=$3
  local ordinal=$4
  local raw="$output/$variant/$pair.json"
  local started_at
  local ended_at
  started_at=$(node -e 'console.log(new Date().toISOString())')
  (
    cd "$project"
    ./gradlew round13PlaygroundSample --rerun-tasks --console=plain --quiet \
      -PtestJavaVersion=17 \
      -PblueDependencyMode=published-artifact \
      -Dblue.coordination.round13.warmups="$warmups" \
      -Dblue.coordination.round13.sample="$raw"
  )
  ended_at=$(node -e 'console.log(new Date().toISOString())')
  node "$importer" --append-ledger \
    --preflight "$preflight" \
    --ledger "$ledger" \
    --raw-sample "$raw" \
    --campaign-id "$campaign_id" \
    --ordinal "$ordinal" \
    --pair "$pair" \
    --variant "$variant" \
    --started-at "$started_at" \
    --ended-at "$ended_at" \
    --runner "$runner"
}

ordinal=0
for ((pair=1; pair<=samples; pair++)); do
  if (( pair % 2 == 1 )); then
    ordinal=$((ordinal + 1)); run_one "$baseline" baseline "$pair" "$ordinal"
    ordinal=$((ordinal + 1)); run_one "$candidate" candidate "$pair" "$ordinal"
  else
    ordinal=$((ordinal + 1)); run_one "$candidate" candidate "$pair" "$ordinal"
    ordinal=$((ordinal + 1)); run_one "$baseline" baseline "$pair" "$ordinal"
  fi
done

node "$importer" --capture-postflight \
  --raw "$output" \
  --baseline "$baseline" \
  --candidate "$candidate" \
  --archive "$archive" \
  --runner "$runner" \
  --campaign-id "$campaign_id" \
  --preflight "$preflight" \
  --ledger "$ledger" \
  --output "$postflight"

echo "ROUND13_AB_CAMPAIGN=$campaign_id"
echo "ROUND13_AB_RAW=$output"
