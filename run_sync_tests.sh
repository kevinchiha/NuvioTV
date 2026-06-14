#!/usr/bin/env bash
# Runs SQL setup/test files against a Supabase BRANCH DB (not Docker, not the live project).
# *_setup.sql / *_teardown.sql are applied for real; *_test.sql is wrapped in a rolled-back
# transaction so its fixtures (auth.users rows, the member_addon seed trigger) never leak.
#
# Connection: export SYNC_TEST_DB_URL to the branch's SESSION-mode pooler endpoint:
#   host aws-0-<region>.pooler.supabase.com, PORT 5432 (session mode), user postgres.<branch-ref>.
#   - NOT the 6543 transaction pooler — it breaks SET ROLE / session GUCs / multi-statement txns the tests use.
#   - NOT the direct db.<branch-ref>.supabase.co endpoint — it is IPv6-only and unreachable on IPv4-only hosts.
#   e.g. postgresql://postgres.<ref>:<pw>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
# Stored in the untracked .supabase_db.env (gitignored); never commit the password.
#
# Usage:
#   ./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"   # pin CWD = repo root so relative \i / -f resolve

: "${SYNC_TEST_DB_URL:?export SYNC_TEST_DB_URL to the branch DIRECT connection string}"
PSQL=(psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -q)

"${PSQL[@]}" -c 'select 1' >/dev/null   # fail fast on a bad connstring / SSL
"${PSQL[@]}" -f sync_test_helpers.sql

for f in "$@"; do
  echo "── applying $f"
  if [[ "$f" == *_test.sql ]]; then
    # one rolled-back transaction per test file: schema from prior *_setup.sql persists,
    # but this file's data + any DDL re-applied via \i is undone.
    printf 'begin;\n\\i %s\nrollback;\n' "$f" | "${PSQL[@]}"
  else
    "${PSQL[@]}" -f "$f"
  fi
done

echo "ALL SYNC SQL TESTS PASSED"
