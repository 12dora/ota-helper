#!/usr/bin/env bash
# Fails if any tracked file leaks machine- or person-identifying data.
# Usage: scripts/privacy-scan.sh   (run from anywhere inside the repository)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
self="scripts/privacy-scan.sh"

declare -a checks=(
  "absolute macOS home path|/Users/[^/[:space:]]+"
  "absolute Linux home path|/home/[a-z_][a-z0-9_-]*"
  "Android SDK location (sdk.dir assignment)|^[[:space:]]*sdk\\.dir[[:space:]]*="
  "private IPv4 address|\\b(10\\.[0-9]{1,3}|192\\.168|172\\.(1[6-9]|2[0-9]|3[01]))\\.[0-9]{1,3}\\.[0-9]{1,3}\\b"
  "mDNS / LAN hostname|\\b[A-Za-z0-9-]+\\.(local|lan|localdomain)\\b"
)

# The current machine's hostname, when it is distinctive enough to search for.
host="$(hostname -s 2>/dev/null || true)"
if [[ ${#host} -ge 4 && "$host" != "localhost" ]]; then
  checks+=("this machine's hostname|\\b${host}\\b")
fi

files=()
while IFS= read -r -d '' f; do
  [[ "$f" == "$self" ]] && continue
  [[ -f "$f" ]] && files+=("$f")
done < <(git ls-files -z)

status=0
report() {
  echo "privacy-scan: $1:" >&2
  echo "$2" | sed 's/^/  /' >&2
  status=1
}

for entry in "${checks[@]}"; do
  label="${entry%%|*}"
  pattern="${entry#*|}"
  if hits="$(grep -InE -- "$pattern" "${files[@]}" 2>/dev/null)"; then
    report "$label" "$hits"
  fi
done

# Email addresses, except GitHub noreply aliases.
email='[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
# GitHub noreply addresses and RFC 2606 reserved example domains are not personal data.
reserved='@(users\.noreply\.github\.com|([A-Za-z0-9-]+\.)*example\.(com|org|net)|[A-Za-z0-9.-]+\.(test|invalid))$'
if hits="$(grep -InoE -- "$email" "${files[@]}" 2>/dev/null | grep -vE "$reserved")"; then
  report "email address" "$hits"
fi

if [[ $status -eq 0 ]]; then
  echo "privacy-scan: ${#files[@]} tracked files clean"
fi
exit $status
