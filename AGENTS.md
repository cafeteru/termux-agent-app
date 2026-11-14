# Guidelines & Architecture Hypothesis for Agents (`termux-agent-app`)

## 1. Visión General e Hipótesis Inicial

`termux-agent-app` es una aplicación nativa de Android (APK en Kotlin) que actúa como interfaz visual (Chat + Terminal) y gestor de notificaciones en segundo plano para agentes de IA en consola que corren dentro de Termux, empezando por:
- **GitHub Copilot CLI (`copilot`)**: instalado vía [cafeteru/copilot-termux](https://github.com/cafeteru/copilot-termux).
- **Google Antigravity CLI (`agy`)**: instalado vía [wallentx/antigravity-cli-termux](https://github.com/wallentx/antigravity-cli-termux).

### Reglas Arquitectónicas Inquebrantables
1. **No modificar nunca `agy` ni `copilot`:**
   - Prohibido parchear binarios, envolver ejecutables existentes o inyectar configuraciones/hooks obligatorios en las instalaciones de `agy` o `copilot`.
   - Toda la integración debe ocurrir desde fuera, tratando cada CLI como una caja negra interactiva.
2. **Los Adapters viven dentro de la APK, no en Termux:**
   - En Termux **no** se instala ningún demonio o puente propio; únicamente se utiliza el servidor estándar **`sshd`** de Termux (`pkg install openssh`, puerto `8022`).
   - La APK se conecta por SSH (`127.0.0.1:8022`) desde un `ForegroundService`, abre canales PTY (y SFTP en solo-lectura cuando aporta valor, como para leer `transcript.jsonl` de `agy`) y ejecuta los adapters en Kotlin.
3. **Notificaciones accionables en segundo plano:**
   - Cuando un adapter detecta que el agente está esperando permiso (`WAITING_PERMISSION`), el `ForegroundService` debe emitir una notificación de alta prioridad con botones de acción directa (**Permitir** / **Denegar**) que envíen la respuesta al PTY sin obligar al usuario a abrir la actividad principal.
   - Cuando un adapter detecta que el agente ha terminado su turno (`COMPLETED`), debe emitir una notificación informando del fin de la tarea.

---

## 2. Diseño del Sistema de Adapters (en Kotlin)

Para soportar múltiples CLIs con interfaces TUI basadas en secuencias ANSI, el flujo del PTY se procesa mediante un buffer de terminal 2D en memoria antes de pasarlo al adapter activo.

### Contrato `AgentAdapter`
Cada agente implementa la interfaz común `AgentAdapter`:
- `id`: identificador del agente (`"agy"`, `"copilot"`).
- `launchCommand(workspacePath: String): String`: comando de arranque en el PTY sobre SSH.
- `onScreenUpdated(screenLines: List<String>, idleMs: Long): AdapterEvent?`: analiza la pantalla 2D renderizada y el tiempo de inactividad para detectar:
  - `PermissionRequired(id, tool, command, options)`
  - `TurnCompleted(summary)`
  - `StateChanged(state)` (`IDLE`, `WORKING`, `WAITING_PERMISSION`, `COMPLETED`)
- `sendPrompt(ptyOutput: OutputStream, text: String)`: envía el mensaje del usuario y el retorno de carro adecuado.
- `resolvePermission(ptyOutput: OutputStream, allow: Boolean, optionIndex: Int?)`: envía al PTY la secuencia exacta de teclas que espera ese agente para aprobar o denegar.

### Particularidades por Agente
- **`AgyAdapter`**:
  - Puede complementar la pantalla PTY leyendo en modo solo-lectura el archivo `~/.gemini/antigravity-cli/brain/<conversation-id>/.system_generated/logs/transcript.jsonl` vía SFTP para mostrar burbujas de chat limpias (mensajes del usuario, respuestas del modelo y llamadas a herramientas).
  - Detecta los diálogos de confirmación en la TUI desde el buffer 2D.
- **`CopilotAdapter`**:
  - Lanza `$PREFIX/bin/copilot` (creado por `cafeteru/copilot-termux`).
  - Extrae tanto los mensajes como los diálogos de permiso desde el buffer 2D del PTY (o mediante flags estándar del CLI si están disponibles sin modificar la instalación).

---

## 3. Flujo de Trabajo y Gestión de Ramas

1. Mantener los cambios pequeños, directos y sin sobreingeniería (principio YAGNI).
2. Toda nueva funcionalidad debe ir en una rama descriptiva (`feat/...`, `fix/...`, `docs/...`) y fusionarse mediante Pull Request a `main`.
3. **Limpieza de ramas:** tras fusionar un PR en `main`, eliminar siempre la rama tanto en `origin` como en local:
   ```bash
   gh pr merge <pr-number> --delete-branch
   git checkout main
   git pull origin main
   git branch -d <branch>
   ```
