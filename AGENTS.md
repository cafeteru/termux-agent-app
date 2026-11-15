# Guidelines & Architecture Hypothesis for Agents (`termux-agent-app`)

## 1. Overview & Initial Hypothesis

`termux-agent-app` is a native Android application (Kotlin APK) that serves as a visual interface (Chat + Terminal) and background notification manager for CLI AI agents running inside Termux, starting with:
- **GitHub Copilot CLI (`copilot`)**: installed via [cafeteru/copilot-termux](https://github.com/cafeteru/copilot-termux).
- **Google Antigravity CLI (`agy`)**: installed via [wallentx/antigravity-cli-termux](https://github.com/wallentx/antigravity-cli-termux).

### Non-Negotiable Architectural Rules
1. **Never modify `agy` or `copilot`:**
   - Do not patch binaries, wrap existing executables, or inject mandatory configs/hooks into the `agy` or `copilot` installations.
   - All integration must happen externally, treating each CLI as an interactive black box.
2. **Adapters live inside the APK, not in Termux:**
   - **No** custom daemon or bridge is installed in Termux; only Termux's standard **`sshd`** server (`pkg install openssh`, port `8022`) is used.
   - The APK connects over SSH (`127.0.0.1:8022`) from a `ForegroundService`, opens PTY channels (plus read-only SFTP where useful, such as reading `agy`'s `transcript.jsonl`), and runs the adapters in Kotlin.
3. **Actionable background notifications:**
   - When an adapter detects that the agent is awaiting permission (`WAITING_PERMISSION`), the `ForegroundService` must post a high-priority notification with direct action buttons (**Allow** / **Deny**) that send the response to the PTY without requiring the user to open the main activity.
   - When an adapter detects that the agent has finished its turn (`COMPLETED`), it must post a task-completion notification.

---

## 2. Adapter System Design (in Kotlin)

To support multiple CLIs with ANSI-based TUI interfaces, the PTY stream is processed through an in-memory 2D terminal buffer before being inspected by the active adapter.

### `AgentAdapter` Contract
Each agent implements the common `AgentAdapter` interface:
- `id`: agent identifier (`"agy"`, `"copilot"`).
- `launchCommand(workspacePath: String): String`: startup command executed inside the SSH PTY.
- `onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent?`: inspects the rendered 2D screen and idle time to emit:
  - `PermissionRequired(id, tool, command, options)`
  - `TurnCompleted(summary)`
  - `StateChanged(state)` (`IDLE`, `WORKING`, `WAITING_PERMISSION`, `COMPLETED`)
- `sendPrompt(ptyOutput: OutputStream, text: String)`: writes the user message and appropriate carriage return to the PTY.
- `resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int?)`: writes the exact key sequence expected by that agent to approve or deny.

### Agent-Specific Details
- **`AgyAdapter`**:
  - Can supplement the PTY screen by reading `~/.gemini/antigravity-cli/brain/<conversation-id>/.system_generated/logs/transcript.jsonl` in read-only mode over SFTP to render clean chat bubbles (user messages, model replies, and tool calls).
  - Detects TUI confirmation dialogs from the 2D buffer.
- **`CopilotAdapter`**:
  - Launches `$PREFIX/bin/copilot` (created by `cafeteru/copilot-termux`).
  - Extracts both messages and permission prompts from the PTY 2D buffer (or via standard CLI flags if available without modifying the installation).

---

## 3. Workflow & Branch Management

1. Keep changes small, direct, and free of over-engineering (YAGNI).
2. Every new feature must be developed on a descriptive branch (`feat/...`, `fix/...`, `docs/...`) and merged via Pull Request into `main`.
3. **Pull Request creation (`gh` via Python skill — solo bajo petición explícita):** nunca crear un PR automáticamente tras implementar un cambio; ejecutar [`create_pr.py`](.github/skills/github-pr-creator/scripts/create_pr.py) o `gh pr create` solo cuando el usuario lo pida explícitamente (protegido por `.agents/hooks.json`). Usar [`github-pr-creator`](.github/skills/github-pr-creator/SKILL.md) con el template de 4 bloques (**What / Why / How It Was Tested / Follow-Up & Rollout**), `git merge-tree`, assignee `cafeteru` y `git push` previo.
4. **No auto-merge:** Nunca ejecutar `gh pr merge` automáticamente después de crear un PR. Dejar el PR abierto y esperar a que el usuario lo revise o pida explícitamente hacer el merge (protegido por `.agents/hooks.json` y `.githooks/pre-push`).
5. **Branch cleanup (solo tras un merge aprobado por el usuario):** after a PR is merged into `main`, delete the branch both on `origin` and locally:
   ```bash
   gh pr merge <pr-number> --merge --delete-branch
   git checkout main
   git pull origin main
   git branch -d <branch>
   ```

---

## 4. Local Build, Versioning & On-Device APK Testing (Termux)

1. **Versioning:** Keep `versionName = "0.0.1"` (`versionCode = 1`) in [`app/build.gradle.kts`](app/build.gradle.kts) until the initial functional MVP is ready.
2. **Local Gradle & Android SDK (`aarch64`):**
   - Termux packages `gradle` (`9.9.1`), `openjdk-21`, and native ARM64 `aapt2` (`/data/data/com.termux/files/usr/bin/aapt2`) are installed locally to validate tests and build APKs without waiting on CI.
   - Android SDK (`platforms;android-35`, `build-tools;35.0.0`, `build-tools;34.0.0`, `platform-tools`) lives in `/data/data/com.termux/files/home/android-sdk` (invoke `sdkmanager` via `sh $HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/android-sdk ...` because Termux lacks `/usr/bin/env`).
   - Git-ignored `local.properties` sets `sdk.dir=/data/data/com.termux/files/home/android-sdk`.
   - User-level `~/.gradle/gradle.properties` sets `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2` so local ARM64 builds succeed while GitHub Actions (`ubuntu-latest`) remains unaffected.
   - Always pass `--no-daemon` when running Gradle in Termux:
     ```bash
     gradle testDebugUnitTest assembleDebug --no-daemon
     ```
3. **On-Device APK Installation (Android 15):**
   - Copy the built APK to shared Downloads (`cp -f app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/termux-agent-0.0.1-debug.apk`) and install it from the system Files app (**Mis archivos → Descargas**).
   - Termux's `com.termux.app.api.file.FileViewReceiverActivity` and `FileReceiverActivity` are disabled via `IPackageManager` (`setComponentEnabledSetting` using `/system/bin/app_process` with `chmod 0400 classes.dex` and `env -u LD_LIBRARY_PATH -u LD_PRELOAD`) so `.apk` files resolve directly to `com.google.android.packageinstaller` instead of `TermuxFileReceiver`.
