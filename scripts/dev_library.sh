#!/usr/bin/env bash
# dev_library.sh — list, filter, and open installed novels in the debug app on the emulator.
#
# Reads the app's on-disk library (files/webnovel_archiver/stories/*.json) over
# `adb exec-out run-as <debug-pkg> cat …`, so no app change or content provider is
# needed. Opening a novel/chapter uses the debug-only `dev_start_*` intent extras
# already honored by MainActivity/DevLaunchPlanning.kt.
#
# Device safety (per AGENTS.md): targets the `webnovel_api36` emulator only. Every
# device-targeting adb call uses `adb -s <emulator-serial>`. The only unqualified
# adb call allowed is `adb devices -l` (discovery). Fails closed rather than ever
# touching the owner's physical phone.
#
# Usage:
#   scripts/dev_library.sh list [filters]            # table of installed novels
#   scripts/dev_library.sh chapters <story-ref> [--downloaded]
#   scripts/dev_library.sh open <story-ref> [--chapter <chapter-ref>] [--rebuild] [--dry-run]
#   scripts/dev_library.sh help
#
# Filters (apply to `list`, and to row-number refs in `chapters`/`open`):
#   --status <s>          download status: idle|downloading|completed|failed|paused|partial
#   --pub-status <s>      publication status: unknown|ongoing|completed|outdated|hiatus
#   --source <host-sub>   substring match against sourceUrl host
#   --tag <t>             novel has this tag (case-insensitive)
#   --author <s>          author substring (case-insensitive)
#   --title <s>           title substring (case-insensitive)
#   --tab <id>            tabId equals <id>
#   --archived | --not-archived
#   --incomplete          downloadedChapters < totalChapters
#   --jq '<bool expr>'    filter on ANY Story field, e.g. --jq '.score == "9.0"'
#   --ids                 print only story ids (one per line)
#   --json                print the raw filtered JSON array
#
# Story/chapter refs (for chapters/open):
#   1. positive integer  → 1-based row in the CURRENT filtered+sorted list
#                          (pass the same filter flags so row numbers line up)
#   2. exact id          → e.g. rr_158518
#   3. title substring   → case-insensitive; errors + lists candidates if >1 match
set -euo pipefail

PKG="com.vinicius741.webnovelarchiver.nativeapp.debug"
ACTIVITY="com.vinicius741.webnovelarchiver.app.MainActivity"
STORIES_DIR="files/webnovel_archiver/stories"
ADB="${ADB:-$(command -v adb || echo adb)}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REDEPLOY="$REPO_ROOT/scripts/redeploy.sh"

# ---------------------------------------------------------------------------
# Emulator serial resolution (mirrors scripts/redeploy.sh; never a phone).
# ---------------------------------------------------------------------------
resolve_serial() {
  if [ -n "${EMULATOR_SERIAL:-}" ]; then
    local state
    state=$("$ADB" -s "$EMULATOR_SERIAL" get-state 2>/dev/null || true)
    if [[ "$EMULATOR_SERIAL" != emulator-* ]] || [ "$state" != "device" ]; then
      die "EMULATOR_SERIAL must name a healthy emulator-* target."
    fi
    echo "$EMULATOR_SERIAL"
    return
  fi

  local serials=()
  while read -r serial state _rest; do
    if [[ "$serial" == emulator-* && "$state" == "device" ]]; then
      serials+=("$serial")
    fi
  done < <("$ADB" devices -l)

  case "${#serials[@]}" in
    1) echo "${serials[0]}" ;;
    0) die "No running emulator found. Start webnovel_api36 and re-run." ;;
    *) die "Multiple emulators are running. Set EMULATOR_SERIAL to the intended emulator-* serial." \
           "Running:" "$(printf ' %s' "${serials[@]}")" ;;
  esac
}

# ---------------------------------------------------------------------------
# Pull the entire library as one sorted JSON array of payloads.
# Reads one file at a time over exec-out run-as (robust to adb's double shell).
# Emits "[]" on empty library.
# ---------------------------------------------------------------------------
fetch_library_json() {
  local serial="$1"
  local listing
  # `ls -1` (one per line); plain `ls` emits space-padded columns that break
  # line-oriented parsing because filenames never contain spaces here.
  listing=$("$ADB" -s "$serial" exec-out run-as "$PKG" ls -1 "$STORIES_DIR" 2>/dev/null || true)

  if [ -z "$listing" ]; then
    # run-as refused (app not installed / not debuggable) or dir missing.
    if ! "$ADB" -s "$serial" shell pm path "$PKG" >/dev/null 2>&1; then
      die "Debug app $PKG is not installed on $serial. Build+install the debug APK first."
    fi
    die "Could not read $STORIES_DIR via run-as (is the debug app installed?)."
  fi

  local first=true
  printf '['
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in *.json) ;; *) continue ;; esac
    local doc
    doc=$("$ADB" -s "$serial" exec-out run-as "$PKG" cat "$STORIES_DIR/$f" 2>/dev/null || true)
    [ -n "$doc" ] || continue
    local payload
    payload=$(printf '%s' "$doc" | jq -c '.payload // empty' 2>/dev/null || true)
    [ -n "$payload" ] || continue
    if $first; then first=false; else printf ','; fi
    printf '%s' "$payload"
  done < <(printf '%s\n' "$listing")
  printf ']'
}

# ---------------------------------------------------------------------------
# Build a jq program that applies the named filters + the --jq escape hatch,
# then sorts by title. The input is a JSON ARRAY of Story payloads, so each
# predicate is wrapped in `map(select(...))`. Predicates reference jq variables
# ($status, …) that run_filter() binds via `jq --arg`. Keep names in sync.
# Output: e.g. `map(select(...)) | sort_by(.title // "")`.
# ---------------------------------------------------------------------------
filter_program() {
  local body='.'
  [ -n "${ARG_STATUS:-}" ]      && body="$body | map(select((((.status // \"\")) | ascii_downcase) == \$status))"
  [ -n "${ARG_PUB_STATUS:-}" ]  && body="$body | map(select((((.publicationStatus // \"\")) | ascii_downcase) == \$pubstatus))"
  [ -n "${ARG_SOURCE:-}" ]      && body="$body | map(select((((.sourceUrl // \"\")) | ascii_downcase) | contains(\$source | ascii_downcase)))"
  [ -n "${ARG_TAG:-}" ]         && body="$body | map(select((.tags // []) | map(ascii_downcase) | any(. == \$tag)))"
  [ -n "${ARG_AUTHOR:-}" ]      && body="$body | map(select((((.author // \"\")) | ascii_downcase) | contains(\$author | ascii_downcase)))"
  [ -n "${ARG_TITLE:-}" ]       && body="$body | map(select((((.title // \"\")) | ascii_downcase) | contains(\$title | ascii_downcase)))"
  [ -n "${ARG_TAB:-}" ]         && body="$body | map(select((.tabId // \"\") == \$tab))"
  [ "${ARG_ARCHIVED:-0}" = 1 ]      && body="$body | map(select(.isArchived == true))"
  [ "${ARG_NOT_ARCHIVED:-0}" = 1 ]  && body="$body | map(select(.isArchived != true))"
  [ "${ARG_INCOMPLETE:-0}" = 1 ]    && body="$body | map(select(((.downloadedChapters // 0)) < ((.totalChapters // 0))))"
  [ -n "${ARG_JQ:-}" ]          && body="$body | map(select(${ARG_JQ}))"
  printf '%s | sort_by(.title // "")\n' "$body"
}

# Apply filters + sort. Input: raw library JSON array on stdin. Output: filtered array.
run_filter() {
  local prog
  prog=$(filter_program)
  jq -c \
    ${ARG_STATUS:+--arg status "$(printf '%s' "$ARG_STATUS" | tr '[:upper:]' '[:lower:]')"} \
    ${ARG_PUB_STATUS:+--arg pubstatus "$(printf '%s' "$ARG_PUB_STATUS" | tr '[:upper:]' '[:lower:]')"} \
    ${ARG_SOURCE:+--arg source "$ARG_SOURCE"} \
    ${ARG_TAG:+--arg tag "$(printf '%s' "$ARG_TAG" | tr '[:upper:]' '[:lower:]')"} \
    ${ARG_AUTHOR:+--arg author "$ARG_AUTHOR"} \
    ${ARG_TITLE:+--arg title "$ARG_TITLE"} \
    ${ARG_TAB:+--arg tab "$ARG_TAB"} \
    "$prog"
}

# ---------------------------------------------------------------------------
# Commands.
# ---------------------------------------------------------------------------

cmd_list() {
  local serial; serial=$(resolve_serial)
  local lib; lib=$(fetch_library_json "$serial")

  if [ "$(printf '%s' "$lib" | jq 'length')" -eq 0 ]; then
    echo "No novels found on $serial." >&2
    return 0
  fi

  if [ "${ARG_IDS:-0}" = 1 ]; then
    printf '%s' "$lib" | run_filter | jq -r '.[].id'
    return
  fi
  if [ "${ARG_JSON:-0}" = 1 ]; then
    printf '%s' "$lib" | run_filter | jq '.'
    return
  fi

  # Human table. jq emits tab-separated columns; column -t aligns them.
  printf '%s' "$lib" | run_filter | jq -r --arg sep $'\t' '
    to_entries[] |
    [
      ("\(.key + 1)"),
      (.value.id // ""),
      (.value.title // ""),
      (.value.author // ""),
      ((.value.status // "-")),
      ((.value.publicationStatus // "-")),
      ("\((.value.downloadedChapters // 0))/\((.value.totalChapters // 0))"),
      (((.value.sourceUrl // "") | sub("^https?://"; "") | split("/")[0]))
    ] | @tsv' \
    | awk -F'\t' 'BEGIN{OFS="\t"; print "#","ID","TITLE","AUTHOR","STATUS","PUB","PROGRESS","SOURCE"}
                        {print}' \
    | column -s$'\t' -t
}

cmd_chapters() {
  local story_ref="${1:-}"
  [ -n "$story_ref" ] || die "Usage: dev_library.sh chapters <story-ref> [--downloaded]"
  shift

  # Remaining args are this command's own flags (parse_filters already consumed
  # any shared filter flags before the positional story-ref).
  while [ $# -gt 0 ]; do
    case "$1" in
      --downloaded) ARG_DOWNLOADED=1; shift ;;
      *) die "Unknown option: $1" ;;
    esac
  done

  local serial; serial=$(resolve_serial)
  local lib; lib=$(fetch_library_json "$serial")
  local story_json; story_json=$(resolve_story "$lib" "$story_ref")
  local story_id; story_id=$(printf '%s' "$story_json" | jq -r '.id')

  # Normalize an epoch int to a YYYY-MM-DD string. Storage uses milliseconds;
  # guard against accidental seconds values (anything below ~year-2000 in ms).
  local date_fn='def d(v): (v // null) | if . == null then "-" elif . > 1000000000000 then (. / 1000 | todateiso8601[0:10]) else (todateiso8601[0:10]) end;'

  if [ "${ARG_DOWNLOADED:-0}" = 1 ]; then
    printf '%s' "$story_json" | jq -r --arg id "$story_id" "$date_fn"'
      .chapters | map(select(.downloaded == true)) | to_entries[] |
      [("\(.key + 1)"), (.value.id // ""), (.value.title // ""), "yes", d(.value.publishedAt)] | @tsv' \
      | awk -F'\t' 'BEGIN{OFS="\t"; print "#","CHAPTER ID","TITLE","DL","PUBLISHED"}{print}' \
      | column -s$'\t' -t
  else
    printf '%s' "$story_json" | jq -r "$date_fn"'
      .chapters | to_entries[] |
      [("\(.key + 1)"), (.value.id // ""), (.value.title // ""),
       (if (.value.downloaded == true) then "yes" else "no" end), d(.value.publishedAt)] | @tsv' \
      | awk -F'\t' 'BEGIN{OFS="\t"; print "#","CHAPTER ID","TITLE","DL","PUBLISHED"}{print}' \
      | column -s$'\t' -t
  fi
}

cmd_open() {
  local story_ref="${1:-}"
  [ -n "$story_ref" ] || die "Usage: dev_library.sh open <story-ref> [--chapter <chapter-ref>] [--rebuild] [--dry-run]"
  shift

  local want_rebuild=0 dry_run=0 chapter_ref=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --chapter) chapter_ref="${2:-}"; shift 2 ;;
      --rebuild) want_rebuild=1; shift ;;
      --dry-run) dry_run=1; shift ;;
      *) die "Unknown option: $1" ;;
    esac
  done

  local serial; serial=$(resolve_serial)
  local lib; lib=$(fetch_library_json "$serial")
  local story_json; story_json=$(resolve_story "$lib" "$story_ref")
  local story_id; story_id=$(printf '%s' "$story_json" | jq -r '.id')
  local story_title; story_title=$(printf '%s' "$story_json" | jq -r '.title')

  local screen="details"
  local chapter_id="" chapter_title=""
  if [ -n "$chapter_ref" ]; then
    screen="reader"
    chapter_id=$(resolve_chapter "$story_json" "$chapter_ref")
    chapter_title=$(printf '%s' "$story_json" | jq -r --arg cid "$chapter_id" '.chapters[] | select(.id == $cid) | .title')
    local dl; dl=$(printf '%s' "$story_json" | jq -r --arg cid "$chapter_id" '.chapters[] | select(.id == $cid) | (if .downloaded == true then "yes" else "no" end)')
    if [ "$dl" = "no" ]; then
      echo "⚠ Chapter \"$chapter_title\" ($chapter_id) is not downloaded; the reader may show a spinner or empty body." >&2
    fi
  fi

  echo "▸ Screen:   $screen"
  echo "  Novel:    $story_title ($story_id)"
  [ -n "$chapter_id" ] && echo "  Chapter:  $chapter_title ($chapter_id)"

  if [ "$dry_run" = 1 ]; then
    echo "  [dry-run] not launching."
    return 0
  fi

  if [ "$want_rebuild" = 1 ]; then
    echo "▸ Rebuilding + installing via scripts/redeploy.sh (story/chapter ids forwarded)…"
    REDEPLOY_STORY_ID="$story_id" \
    REDEPLOY_CHAPTER_ID="$chapter_id" \
      bash "$REDEPLOY" "$screen"
    return
  fi

  echo "▸ force-stop + cold-start on $serial"
  local args=(--es dev_start_screen "$screen" --es dev_start_story "$story_id")
  [ -n "$chapter_id" ] && args+=(--es dev_start_chapter "$chapter_id")
  "$ADB" -s "$serial" shell am force-stop "$PKG"
  "$ADB" -s "$serial" shell am start -n "$PKG/$ACTIVITY" "${args[@]}" >/dev/null
  echo "✓ Launched."
}

# ---------------------------------------------------------------------------
# Ref resolution.
# ---------------------------------------------------------------------------

# Echo the matched story payload JSON object; die on ambiguity / no match.
resolve_story() {
  local lib="$1" ref="$2"
  local filtered; filtered=$(printf '%s' "$lib" | run_filter)

  if printf '%s' "$ref" | grep -qE '^[1-9][0-9]*$'; then
    local row; row=$(printf '%s' "$ref" | jq .)
    local hit; hit=$(printf '%s' "$filtered" | jq -c --argjson i "$((row - 1))" '.[$i] // empty')
    [ -n "$hit" ] || die "Row $ref is out of range (have $(printf '%s' "$filtered" | jq 'length') rows in the current filtered list)."
    printf '%s' "$hit"
    return
  fi

  # Exact id match.
  local hit; hit=$(printf '%s' "$filtered" | jq -c --arg id "$ref" '.[] | select(.id == $id)' | head -1)
  if [ -n "$hit" ]; then printf '%s' "$hit"; return; fi

  # Case-insensitive title substring.
  local matches; matches=$(printf '%s' "$filtered" | jq -c --arg t "$(printf '%s' "$ref" | tr '[:upper:]' '[:lower:]')" '[.[] | select(((.title // "") | ascii_downcase) | contains($t))]')
  case "$(printf '%s' "$matches" | jq 'length')" in
    0) die "No novel matches \"$ref\" (tried: row number, exact id, title substring)." ;;
    1) printf '%s' "$matches" | jq -c '.[0]' ;;
    *) echo "✗ \"$ref\" matches multiple novels:" >&2
       printf '%s' "$matches" | jq -r --arg sep $'\t' 'to_entries[] | [("  #\(.key+1)"), (.value.id), (.value.title)] | @tsv' >&2
       die "Refine the title or pass the exact id." ;;
  esac
}

# Echo the matched chapter id; die on ambiguity / no match.
resolve_chapter() {
  local story_json="$1" ref="$2"

  if printf '%s' "$ref" | grep -qE '^[1-9][0-9]*$'; then
    local row; row=$(printf '%s' "$ref" | jq .)
    local id; id=$(printf '%s' "$story_json" | jq -r --argjson i "$((row - 1))" '.chapters[$i].id // empty')
    [ -n "$id" ] || die "Chapter row $ref is out of range (story has $(printf '%s' "$story_json" | jq '.chapters | length') chapters)."
    printf '%s' "$id"
    return
  fi

  # Exact id.
  local id; id=$(printf '%s' "$story_json" | jq -r --arg cid "$ref" '.chapters[] | select(.id == $cid) | .id' | head -1)
  if [ -n "$id" ]; then printf '%s' "$id"; return; fi

  # Title substring (case-insensitive).
  local matches; matches=$(printf '%s' "$story_json" | jq -c --arg t "$(printf '%s' "$ref" | tr '[:upper:]' '[:lower:]')" '[.chapters | .[] | select(((.title // "") | ascii_downcase) | contains($t)) | .id]')
  case "$(printf '%s' "$matches" | jq 'length')" in
    0) die "No chapter matches \"$ref\"." ;;
    1) printf '%s' "$matches" | jq -r '.[0]' ;;
    *) echo "✗ \"$ref\" matches multiple chapters:" >&2
       printf '%s' "$matches" | jq -r 'to_entries[] | "  #\(.key+1)  \(.value)"' >&2
       die "Refine the title or pass the exact chapter id." ;;
  esac
}

# ---------------------------------------------------------------------------
# Arg parsing + dispatch.
# ---------------------------------------------------------------------------

die() { echo "✗ $*" >&2; exit 1; }

usage() {
  sed -n '3,40p' "$0"
  exit 0
}

# Parse shared filter/output flags from anywhere in the arg list. Non-filter
# tokens (positional refs + subcommand-specific flags) are collected into the
# global POSITIONAL array, in order, so callers receive them regardless of where
# the filters appeared. Filter flags may be interleaved with positionals.
POSITIONAL=()
parse_filters() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --status)        ARG_STATUS="${2:-}"; shift 2 ;;
      --pub-status)    ARG_PUB_STATUS="${2:-}"; shift 2 ;;
      --source)        ARG_SOURCE="${2:-}"; shift 2 ;;
      --tag)           ARG_TAG="${2:-}"; shift 2 ;;
      --author)        ARG_AUTHOR="${2:-}"; shift 2 ;;
      --title)         ARG_TITLE="${2:-}"; shift 2 ;;
      --tab)           ARG_TAB="${2:-}"; shift 2 ;;
      --archived)      ARG_ARCHIVED=1; shift ;;
      --not-archived)  ARG_NOT_ARCHIVED=1; shift ;;
      --incomplete)    ARG_INCOMPLETE=1; shift ;;
      --jq)            ARG_JQ="${2:-}"; shift 2 ;;
      --ids)           ARG_IDS=1; shift ;;
      --json)          ARG_JSON=1; shift ;;
      --) shift; while [ $# -gt 0 ]; do POSITIONAL+=("$1"); shift; done ;;
      *) POSITIONAL+=("$1"); shift ;;
    esac
  done
}

main() {
  command -v jq >/dev/null || die "jq is required (brew install jq)."
  [ $# -lt 1 ] && set -- help
  local sub="$1"; shift
  case "$sub" in
    list)
      parse_filters "$@"
      cmd_list
      ;;
    chapters)
      parse_filters "$@"
      [ "${#POSITIONAL[@]}" -lt 1 ] && die "Usage: dev_library.sh chapters <story-ref> [--downloaded]"
      cmd_chapters "${POSITIONAL[@]}"
      ;;
    open)
      parse_filters "$@"
      [ "${#POSITIONAL[@]}" -lt 1 ] && die "Usage: dev_library.sh open <story-ref> [--chapter <chapter-ref>] [--rebuild] [--dry-run]"
      cmd_open "${POSITIONAL[@]}"
      ;;
    help|-h|--help) usage ;;
    *) die "Unknown command: $sub (try: list, chapters, open, help)" ;;
  esac
}

main "$@"
