# Login file sign-in

## Goal
KevBox Setup on a phone can sign a customer in to KevBox TV by leaving `kevbox-login.json` in the
app's folder, with nobody typing on the TV, following `~/projects/kevbox-setup/docs/login-file.md`
exactly.

## Scope
New package `app/src/main/java/com/nuvio/tv/core/auth/loginfile/`, one call in `MainActivity.onResume`,
and the error-message order in `AccountViewModel.kt`.

**Gate:** run each separately, with `set -o pipefail`:
`./gradlew :app:compileFullDebugKotlin`, `./gradlew :app:testFullDebugUnitTest --continue`,
`./gradlew :app:assembleFullRelease --dry-run`. Full flavor only. The unit-test run is green when its
only failures are the ones on the Build log's `baseline:` line.

## Acceptance criteria
- **AC-1** A login file whose `requestedAt` is not more than 10 minutes older than the box clock
  (a date in the future counts as fresh, as the contract says) leads to exactly one
  call to `AuthManager.signInWithEmail(email, password)`, and the login file no longer exists at the
  moment that call starts.
- **AC-2** A login file whose `requestedAt` is more than 600 000 ms older than
  `System.currentTimeMillis()` is deleted, no sign-in happens, and no result file is written.
- **AC-3** A login file that is not valid JSON, or lacks `email`, `password` or `requestedAt`, or has a
  blank email, or a `requestedAt` that is not a whole number, is deleted with no sign-in and no result file.
- **AC-4** After a sign-in that works, `kevbox-login.result.json` holds exactly `{"ok":true}`.
- **AC-5** After a sign-in that fails, the result file holds `{"ok":false,"error":"<text>"}`, where a
  wrong email or password gives `Wrong email or password`, and the text never contains the password,
  the server's raw reply, or the word "sync".
- **AC-6** The result file appears in one step (written to a temporary name, then renamed), so a
  reader never sees half a file.
- **AC-7** The app checks for the file from `MainActivity.onResume`, which runs on every start and
  every return to the foreground; two checks close together run one sign-in, not two.
- **AC-8** The sign-in runs in an app-wide coroutine scope, so leaving the screen does not cancel it
  before the result file is written.
- **AC-9** A sign-in that has not finished 20 s after the file was read writes
  `{"ok":false,"error":"The TV took too long to sign in. Check its internet connection and try again."}`.
- **AC-10** Already signed in as the same email (case and spaces ignored): the app signs in again
  without signing out first, so the profile data stays. Signed in as a different email: the app calls
  `AuthManager.signOut()` first (the same reset a person gets from Account, Sign out), then signs in.
  While auth state is still `Loading`, the app waits up to 5 s for it to settle.
- **AC-11** No log line, exception message or result file written by the new code contains the
  password, the email, or the login file's text.
- **AC-12** On the sign-in screen, a wrong password now shows "Incorrect email or password." instead
  of "Invalid sync code.".
- **AC-14** If deleting a valid, fresh login file fails, no sign-in happens and the result file holds
  `{"ok":false,"error":"The TV could not clear the login file. Try again."}`; a stale or invalid file
  whose delete fails gets no result file.
- **AC-13** On the emulator, a hand-pushed fresh file for `tv.kevbox.debug` is gone within a few
  seconds of launch and the result file matches the outcome; a stale file and a bad-JSON file are
  deleted with no result file.

## Non-goals
- KevBox Live. Its own repo implements the same contract separately.
- Telling the phone when the account is switched off (access kill switch) or over its device limit.
  Sign-in succeeds at the account server in both cases; the TV then shows its locked-out screen as it
  does today. Reporting that would mean waiting on the access check, which has its own grace period.
- Changing the contract, the file names, or the 30 s budget.
- Translating the result text. It goes to the phone and to Kevin's Telegram report, both English.

## Decisions
- Call `AuthManager.signInWithEmail`, the one network call the sign-in screen makes, and let the
  listeners that already react to any sign-in do the rest (StartupSyncService's pull,
  MemberConfigService, the access and device checks, the first-launch gate in MainActivity that
  moves on to profiles and home once auth is `FullAccount`), over moving `AccountViewModel.signIn`'s
  extra `pullRemoteData` into a shared class, because that ViewModel is upstream code (every move
  there costs us at the next upstream merge), StartupSyncService already pulls the same data on
  sign-in, and KevBox's startup pull deliberately skips the legacy addon reconcile that the
  ViewModel's pull still runs.
- Write `{"ok":true}` as soon as `signInWithEmail` succeeds, over waiting for the data pull, because
  a big watch history takes longer than the phone's 30 s wait.
- Different account: sign out, then sign in, over signing straight in on top, because signing in on
  top leaves the old account's profiles and history on the box. Cost: a wrong password on a box that
  was signed in to someone else leaves it signed out. Same account: sign in again, no sign-out, so
  nothing gets wiped and the password still gets checked.
- Fixed English error texts mapped from the failure, over the app's localised `userFriendlyError`
  strings, because the phone shows the text as-is and Kevin reads it in Telegram.
- kotlinx.serialization `Json` for reading and writing, over `org.json`, because unit tests run with
  `isReturnDefaultValues = true` and `org.json` there returns empty stubs.
- If deleting the login file fails, do not sign in. For a valid, fresh file write
  `{"ok":false,"error":"The TV could not clear the login file. Try again."}`; for a stale or invalid
  one write nothing (AC-2 and AC-3 still hold). The contract forbids signing in while the password
  sits on disk.
- Fix the sign-in screen's wrong message by moving the auth checks in `userFriendlyError` above the
  sync-code checks (the server reply `Auth request failed endpoint=... "error_code":"invalid_credentials"`
  contains both "invalid" and "code", so the sync-code branch caught it first). The `when` moves into
  an `internal` top-level function in the same file so a JVM test can reach it.

## Tasks

### Task 1: Login file processor and error text
**Delivers:** AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-9, AC-11, AC-14
**Files:** `app/src/main/java/com/nuvio/tv/core/auth/loginfile/LoginFileProcessor.kt`,
`app/src/main/java/com/nuvio/tv/core/auth/loginfile/LoginFileErrors.kt`,
`app/src/test/java/com/nuvio/tv/core/auth/loginfile/LoginFileProcessorTest.kt`
**Interfaces:**
- `class LoginFileProcessor(dir: () -> File?, nowMs: () -> Long, timeoutMs: Long = 20_000L, log: (String) -> Unit = {})`
  with `suspend fun process(signIn: suspend (email: String, password: String) -> LoginFileOutcome)`;
  pure JVM, no Android imports (no `Log`; `log` takes password-free notes such as
  "login file stale, deleted").
- `sealed interface LoginFileOutcome { data object Ok; data class Failed(val error: String) }`.
- `const val LOGIN_FILE_NAME = "kevbox-login.json"`, `const val LOGIN_RESULT_FILE_NAME = "kevbox-login.result.json"`,
  `const val LOGIN_FILE_MAX_AGE_MS = 600_000L`.
- `fun loginFileErrorText(error: Throwable): String`, mapping by the lowercased cause-chain messages:
  "invalid login credentials" or "invalid_credentials" → `Wrong email or password`; "email not
  confirmed" → `This account's email is not confirmed yet`; "rate limit", "too many requests" or
  "status=429" → `Too many sign-in tries. Wait a minute and try again`; `UnknownHostException`,
  `SocketTimeoutException`, `ConnectException`, "timeout", "unable to resolve host" → `The TV could
  not reach the internet. Check its connection and try again`; anything else → `Sign-in did not
  work. Try again, or sign in on the TV`.
**Does:**
- No dir, or no file: returns without touching anything.
- Reads the file, then deletes it, then validates; stale or invalid → stop, no result.
- Delete failed (`File.delete()` false and the file still exists): stale or invalid → stop, no result;
  valid and fresh → write the AC-14 result, no sign-in.
- Calls `signIn` under `withTimeoutOrNull(timeoutMs)`; null → the AC-9 text; a thrown exception
  (other than cancellation) → `Failed(loginFileErrorText(e))`.
- Writes the result to `kevbox-login.result.json.tmp`, then renames it over the result file.
**Tests:** fresh/stale/bad-JSON/missing-key cases (file gone, sign-in called or not, result present or
not); the `signIn` lambda asserts the login file is already gone; result shapes for Ok, a wrong-password
exception, and a timeout, each checked to contain no password; a delete that fails (for example a
`dir` lambda pointing at a read-only folder, or the login path made a non-empty directory) gives the
AC-14 result for a fresh file and nothing for a stale one.
**Verify:** `./gradlew :app:testFullDebugUnitTest --tests "*LoginFileProcessorTest"`

### Task 2: Wire it into the app and fix the sign-in screen message
**Delivers:** AC-7, AC-8, AC-10, AC-11, AC-12, AC-13
**Files:** `app/src/main/java/com/nuvio/tv/core/auth/loginfile/LoginFileSignIn.kt`,
`app/src/main/java/com/nuvio/tv/MainActivity.kt`,
`app/src/main/java/com/nuvio/tv/ui/screens/account/AccountViewModel.kt`,
`app/src/test/java/com/nuvio/tv/core/auth/loginfile/LoginFileAccountActionTest.kt`,
`app/src/test/java/com/nuvio/tv/ui/screens/account/AccountErrorResIdTest.kt`
**Interfaces:**
- `@Singleton class LoginFileSignIn @Inject constructor(@ApplicationContext context, authManager: AuthManager)`
  with `fun checkNow()`; own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`; an `AtomicBoolean`
  so a second `checkNow()` while one runs returns at once.
- `internal fun loginFileAccountAction(state: AuthState, fileEmail: String): LoginFileAccountAction`
  returning `SignInOnly` (signed out, or same email ignoring case and spaces) or `SignOutThenSignIn`.
- `internal fun accountErrorResId(message: String): Int` in `AccountViewModel.kt`, the old `when` with
  the auth-error branches moved above the sync-code branches; `userFriendlyError` calls it.
**Does:**
- `checkNow()` builds a `LoginFileProcessor` on `context.getExternalFilesDir(null)` and
  `System::currentTimeMillis`; its `signIn` waits up to 5 s for auth state to leave `Loading`, applies
  the account action, then folds the `Result<Unit>` that `authManager.signInWithEmail` returns (it
  never throws): success → `Ok`, failure → `Failed(loginFileErrorText(it))`.
- `MainActivity` injects `LoginFileSignIn` and calls `checkNow()` at the top of `onResume`.
- AC-13 (the emulator check) is run by the orchestrator after the final review, not by the implementer.
**Tests:** account action for signed out / same email with different case / different email;
`accountErrorResId` on the real wrong-password reply text gives `account_error_invalid_credentials`,
and a real "invalid sync code" text still gives the sync-code string.
**Verify:** `./gradlew :app:compileFullDebugKotlin :app:testFullDebugUnitTest --tests "*LoginFileAccountActionTest" --tests "*AccountErrorResIdTest"`

## Build log

baseline: TraktProgressServiceRefreshTest > change seen by a cancelled refresh is fetched by the next poll (2300 tests, 1 failed, on kevbox 2ddce968d)
Task 1: started at 21429ae59
