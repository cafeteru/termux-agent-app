# Copilot instructions

Read `AGENTS.md` first. Use the repository's specialized agents for isolated work:

- `platform-orchestrator` routes multi-area tasks and keeps the main context clean.
- `android-codebase-specialist` owns Kotlin, Compose, adapters, SSH/PTTY, and Android behavior.
- `android-ui-ux-specialist` owns visual hierarchy, interaction quality, accessibility, and Compose UI reviews.
- `android-build-release-specialist` owns Gradle, tests, APK output, Termux-specific builds, and local APK delivery.

Never modify `agy` or `copilot` installations. Adapters and integrations remain inside the APK. Never run a merge or remote PR action unless the user explicitly requests it.
