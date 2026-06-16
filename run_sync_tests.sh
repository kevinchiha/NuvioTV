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
# Usage (files live in sql/sync/; pass bare names — the basename is resolved under sql/sync/):
#   ./run_sync_tests.sh get_sync_owner_setup.sql watch_progress_setup.sql watch_progress_test.sql
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"   # pin CWD = repo root so the sql/sync paths below resolve

SQL_DIR=sql/sync   # all cloud-restore sync SQL lives here; *_test.sql use \ir to reload their sibling setup

: "${SYNC_TEST_DB_URL:?export SYNC_TEST_DB_URL to the branch DIRECT connection string}"
PSQL=(psql "$SYNC_TEST_DB_URL" -v ON_ERROR_STOP=1 -q)

"${PSQL[@]}" -c 'select 1' >/dev/null   # fail fast on a bad connstring / SSL
"${PSQL[@]}" -f "$SQL_DIR/sync_test_helpers.sql"

for f in "$@"; do
  path="$SQL_DIR/$(basename "$f")"   # tolerate either a bare name or a sql/sync/ path
  echo "── applying $path"
  if [[ "$f" == *_test.sql ]]; then
    # one rolled-back transaction per test file: schema from prior *_setup.sql persists,
    # but this file's data + any DDL re-applied via \ir is undone.
    printf 'begin;\n\\i %s\nrollback;\n' "$path" | "${PSQL[@]}"
  else
    "${PSQL[@]}" -f "$path"
  fi
done

echo "ALL SYNC SQL TESTS PASSED"
