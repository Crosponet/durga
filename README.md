# Statement of intent

This repository is a lab. Its purpose is to explore two questions:

1. Is it worthwhile to build your own personal tools now that agents have made
   it much easier to create simple programs?
2. How far can a purely organizational or administrative task be delegated and
   streamlined so that it can be integrated transparently into a workflow?

# Durga

Client investigation tracking application.

## Context and purpose

Durga tracks client investigation tasks (bugs, requests, and incidents):
subject, description, iteration, affected server, work status, and any
current blocker ("waiting for").

The project deliberately uses simple, independent layers without a database
or ORM, so it remains easy to evolve. Business operations are exposed through
the REST API and through **MCP (Model Context Protocol)** for use by AI agents.

## Technology stack

- Java 21
- Spring Boot 4.1.1
- Gradle (wrapper included: `gradlew` / `gradlew.bat`)
- Lombok
- Jackson, including `jackson-datatype-jsr310` for `LocalDateTime`
- Spring AI MCP Server WebMVC 2.0.1, based on the official MCP Java SDK
- Storage: one JSON document per Task in a dedicated Git checkout, with
  **no ORM or database**; meetings and UI ordering remain local
- Frontend: framework-free HTML/CSS/JS SPA, served statically by Spring Boot
  from `src/main/resources/static`

## Architecture

```text
src/main/java/nf/durga/
├── model/       Task, Status, WorkStatus, WaitingFor, TaskSource (enums + Lombok POJOs)
├── storage/     TaskStorage, GitTaskStorage, GitClient, LocalTaskOrderStorage, JsonMeetingStorage
├── service/     TaskService, UpdateTaskRequest (business logic)
├── web/         TaskController, ApiExceptionHandler (REST API and error mapping)
└── mcp/         TaskMcpTools, McpConfig (MCP adaptation and exposure)

src/main/resources/
├── static/      index.html, app.js, style.css (SPA)
└── application.properties
```

Keep the following separation rules when extending the application:

- **`model`** contains data structures only; it must not contain business
  logic.
- **`storage`** reads/writes individual Task documents and runs explicit Git
  checkpoints. It contains no business rules. `TaskStorage.checkpoint` refreshes
  the checkout, checks expected blob revisions, and calls the service-supplied
  operation against that refreshed snapshot before persisting any changes.
- **`service`** (`TaskService`) contains all business logic, including UUID
  generation, `createdAt`/`updatedAt` handling, and future validation rules.
  It evaluates rules against the refreshed snapshot and supplies changed Tasks
  and a business checkpoint message. It never executes Git commands itself.
- **`web`** (`TaskController`) contains no business logic and only delegates to
  `TaskService`. `ApiExceptionHandler` maps business exceptions such as
  `NoSuchElementException` to HTTP responses.
- **`mcp`** (`TaskMcpTools`) adapts MCP parameters and delegates exclusively to
  `TaskService`. MCP tools contain no business logic and never access storage
  directly.

```text
SPA -> REST ----\
                 -> TaskService -> TaskStorage -> GitTaskStorage -> Git executable
Agent -> MCP ---/
```

## Data model (`Task`)

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` | Generated automatically when the task is created |
| `subject` | `String` | |
| `description` | `String` | Markdown source; rendered in the task detail modal |
| `iteration` | `Integer` | |
| `serverKey` | `String` | |
| `status` | `Status` | `DATA_GRABBING`, `INVESTIGATION`, `FIXING`, `REPORT` |
| `workStatus` | `WorkStatus` | `TODO`, `ON IT`, `PAUSE`, `FINISHED`; defaults to `TODO` |
| `waitingFor` | `WaitingFor` | `NONE`, `CLOUDOPS`, `RND`, `CUSTOM` |
| `waitingForCustom` | `String` | Free-form label when `waitingFor = CUSTOM` |
| `source` | `TaskSource` | `MANUAL`, `AGENT`, `COPILOT`, `EMAIL` |
| `createdAt` / `updatedAt` | `LocalDateTime` | Managed automatically by `TaskService` |
| `archived` | `Boolean` | Defaults to `false`; archived tasks no longer appear in `list()` or the main view |
| `dueDate` | `LocalDate` | |
| `tags` | `String` | |
| `priority` | `Integer` | `1 = critical`, `2 = high`, `3 = normal`, `4 = low` |
| `jiraUrl` | `String` | Link to the Jira request |
| `requires` | `List<UUID>` | Prerequisite tasks that must be finished first; until every prerequisite has `workStatus = FINISHED`, this task cannot move to `ON IT` or `FINISHED` |

Task responses also contain technical `revision` (the raw Git blob ID) and
local `sortOrder` metadata. Neither field belongs to the shared Task document.

Additional business operations exposed by `TaskService`:

- `search(serverKey, status, waitingFor, archived, priority, workStatus)`:
  searches using optional criteria; `null` criteria are ignored.
- `archive(id)`: marks a task as archived.
- `changeStatus(id, newStatus)`: changes only the task status.
- `changeWorkStatus(id, newWorkStatus)`: changes only the work status.
- `patch(id, request)`: updates only the non-null fields in
  `UpdateTaskRequest` and preserves every other field.
- `getActiveTasks()`: returns non-archived tasks (equivalent to `list()`).
- `getBlockedTasks()`: returns active tasks where `waitingFor != NONE`.
- `getTasksByServer(serverKey)`: returns active tasks for a given server.
- Prerequisite enforcement (`requires`): `create`, `update`, `patch`, and
  `changeWorkStatus` reject (with `IllegalStateException`, exposed as HTTP
  409) any transition to `workStatus = ON IT` or `FINISHED` while at least one
  prerequisite is not itself `FINISHED`. The `requires` link is never removed
  automatically; either finish the prerequisite or remove its reference
  manually with `patch`. This rule applies only to task-to-task links
  (`requires`), not to tasks associated with a client meeting
  (`ClientMeeting.taskIds`).
- Forced completion on archive: `archive(id)` always changes the task to
  `workStatus = FINISHED`, even when prerequisites are unfinished. Archiving is
  never blocked by the prerequisite rule.

## REST API

Base path: `/api/tasks`

| Method | URL | Description |
|---|---|---|
| `GET` | `/api/tasks` | Lists non-archived tasks |
| `GET` | `/api/tasks/search` | Filtered search (`?serverKey=&status=&waitingFor=&workStatus=`, all optional) |
| `GET` | `/api/tasks/blocked` | Active tasks waiting for another party (`archived=false` and `waitingFor != NONE`) |
| `GET` | `/api/tasks/server/{serverKey}` | Active tasks for a given server |
| `GET` | `/api/tasks/{id}` | Gets a task (404 if absent) |
| `POST` | `/api/tasks` | Creates a task (201) |
| `PUT` | `/api/tasks/{id}` | Updates a task (404 if absent) |
| `DELETE` | `/api/tasks/{id}` | Deletes a task (204, 404 if absent) |
| `POST` | `/api/tasks/{id}/archive` | Archives a task (200 on success, 404 if absent) |
| `POST` | `/api/tasks/{id}/status` | Changes only the status; body `{"status": "FIXING"}` (200 on success, 404 if absent) |
| `POST` | `/api/tasks/{id}/work-status` | Changes only the work status; body `{"workStatus": "ON IT"}` (200 on success, 404 if absent) |
| `POST` | `/api/tasks/{id}/milestone` | Appends a timestamped milestone; body `{"text":"Analysis complete"}` |
| `POST` | `/api/tasks/{id}/reorder` | Moves a task to a given position; body `{"sortOrder": 2.5}`; recalculates and returns all active tasks with normalized positions (200 on success, 404 if absent) |
| `POST` | `/api/tasks/refresh` | Fetches and fast-forwards the dedicated checkout; returns active Tasks |

These endpoints only delegate to `TaskService`; `TaskController` contains no
business logic.

Individual Task reads and Task-valued mutation responses include a strong,
quoted `ETag`, for example `"20ca...<full blob ID>"`. Existing-Task shared
mutations (PUT, DELETE, archive, status, work-status, milestone) **require
`If-Match` with that quoted ETag**. Collection items expose raw `revision` so
the SPA can send the header automatically. Weak tags, wildcards, missing tags
and malformed revisions are rejected with 400. Stale revisions and push races
return **409** with a reload instruction; Git/synchronization failures return
**503**. A stale mutation of a remotely deleted Task is also a conflict.
This deliberately prevents blind writes. Creation and local reorder need no
revision. There is no automatic mutation retry.

## Client meetings

Client meetings are stored separately in `data/meetings.json`. A meeting
contains an ID, a server key, a date/time (`meetingAt`), a list of task IDs
(`taskIds`), and its creation and update timestamps.

REST API, base path `/api/meetings`:

| Method | URL | Description |
|---|---|---|
| `GET` | `/api/meetings` | Lists meetings |
| `GET` | `/api/meetings/{id}` | Gets a meeting |
| `POST` | `/api/meetings` | Creates a meeting (201) |
| `PUT` | `/api/meetings/{id}` | Updates a meeting |
| `DELETE` | `/api/meetings/{id}` | Deletes a meeting |

The corresponding MCP tools are `create_client_meeting`,
`list_client_meetings`, `get_client_meeting`, `update_client_meeting`, and
`delete_client_meeting`. Dates use ISO-8601 format and tasks are referenced by
UUID; task data is not duplicated in the meeting. When a task is associated
with a meeting, its due date is automatically set to the meeting date.
Changing a meeting date also updates the due dates of its associated tasks.

Meetings are **local-only**, not synchronized or committed to the Task
repository. Their REST/MCP APIs do not require Task revisions. TaskService
fetches current Tasks, validates all associated IDs, then publishes their due
dates as one shared checkpoint before the local meeting is saved. A failed
shared checkpoint aborts the meeting operation. These are not a distributed
transaction: if the subsequent local meeting save fails, the error explicitly
reports that Task due dates were published but the meeting was not saved.

## MCP

The MCP server starts with the Spring Boot application and uses **Streamable
HTTP** at:

```text
http://localhost:8080/mcp
```

Configuration is in `application.properties`. Gradle imports the
`org.springframework.ai:spring-ai-bom:2.0.1` BOM and uses
`org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, which includes
the official MCP Java SDK.

Exposed tools:

| Tool | Business delegation |
|---|---|
| `create_task` | Builds the input with `source=COPILOT`, then calls `TaskService.create()` |
| `list_tasks` | `TaskService.getActiveTasks()` |
| `search_tasks` | `TaskService.search()` |
| `update_task` | Builds an `UpdateTaskRequest`, then calls `TaskService.patch()` |
| `archive_task` | `TaskService.archive()` |
| `change_status` | `TaskService.changeStatus()` |
| `change_work_status` | `TaskService.changeWorkStatus()` |
| `get_blocked_tasks` | `TaskService.getBlockedTasks()` |
| `get_tasks_by_server` | `TaskService.getTasksByServer()` |
| `refresh_tasks` | `TaskService.refresh()` |
| `create_client_meeting` | `ClientMeetingService.create()` |
| `list_client_meetings` | `ClientMeetingService.list()` |
| `get_client_meeting` | `ClientMeetingService.get()` |
| `update_client_meeting` | `ClientMeetingService.update()` |
| `delete_client_meeting` | `ClientMeetingService.delete()` |

`update_task` is a patch operation: only non-null fields are changed. A
`null` value means "do not modify this field". Existing errors (for example,
an unknown ID) are returned to the MCP client. Invalid UUIDs, statuses,
waiting states, and dates produce explicit messages.

Every tool that modifies an existing Task requires `expectedRevision` from a
previous list/search result. Agents must read before writing; on conflict,
refresh and read the Task again before deciding what to change. No Task
revision parameters are added to ClientMeeting tools.

Example configuration for an MCP Streamable HTTP-compatible client or agent:

```json
{
  "mcpServers": {
    "durga": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

An agent can then call `get_blocked_tasks`, for example, followed by
`change_status` with the selected UUID and its `expectedRevision`. The MCP HTTP server does not add
authentication by default; do not expose `/mcp` publicly without access
control in front of it.

## Frontend (SPA)

`src/main/resources/static/` contains `index.html`, `app.js`, and `style.css`.
The SPA uses no JavaScript framework and calls the API with `fetch()`. It
displays active tasks as stacked todo-list rows (`GET /api/tasks`). Clicking a
row opens a modal containing all task fields, including the Jira request link
when present. The modal closes through its close button, a background click,
or the `Escape` key.

Task descriptions are rendered as Markdown in the modal's read view, including
headings, emphasis, lists, links, blockquotes, code blocks and tables. Editing
still uses a plain textarea containing the original Markdown; saving preserves
that source in the REST API, MCP and JSON storage. Saving or cancelling returns
to the rendered read view. Existing descriptions are interpreted as Markdown
without a data migration. A single line break follows standard Markdown rules;
use a blank line for a new paragraph or two trailing spaces for a hard break.

Rendering uses **markdown-it 15.0.2**, whose unmodified browser bundle and MIT
license are stored in `static/vendor/markdown-it-15.0.2/` and served locally
(no CDN, frontend build step or backend dependency). Raw HTML is disabled and
shown as text; markdown-it's built-in unsafe-link filtering is retained.
There are no plugins, automatic URL linking, syntax highlighting or live preview.
Other task fields remain plain text.

The server badge is the leftmost fixed-width column in each task row. Finished
tasks also show an archive icon; selecting it calls
`POST /api/tasks/{id}/archive` and removes the task from the active list.
An icon before each task title copies its ID to the clipboard.

Each row exposes three player-style work-status buttons (▶ PLAY → `ON IT`,
❚❚ PAUSE → `PAUSE`, ■ FINISH → `FINISHED`) that call
`POST /api/tasks/{id}/work-status`. Each button is either active (highlighted
as the current state), clickable, or disabled:

| `workStatus` | PLAY | PAUSE | FINISH |
|---|---|---|---|
| `ON IT` | active | clickable | clickable |
| `PAUSE` | clickable | active | clickable |
| `TODO` | clickable | disabled | clickable |
| `FINISHED` | clickable | disabled | active |

PLAY and FINISH remain disabled while a prerequisite is unfinished.

A sidebar filters active tasks by status, work status, and server. Filters are
applied in the browser to the already-loaded tasks and can be extended later
with additional criteria.

The modal also edits a task's editable fields and submits changes through the
REST API. A monthly calendar above the filters loads client meetings, supports
month navigation, and colors each meeting by server. The **Add** button opens
a meeting-creation form. Clicking a calendar meeting opens the same
pre-populated modal, where the meeting can be edited or deleted after
confirmation.

Fields that reference other tasks (task prerequisites and tasks associated
with a meeting) use a dedicated selector: a check-list showing task subjects,
never UUIDs, filterable by server. Archived tasks are always excluded, as is
the task currently being edited when selecting prerequisites.

Tasks with a due date show a progress bar (except when finished). Its dark
track grows from its minimum width at seven days to its maximum width at
fourteen days; the width is capped beyond fourteen days. The colored fill stays
full until seven days remain, then shrinks toward the due date. Its color moves
from green to orange between seven and one calendar day, then is red on the
due date. The fill is a square on the due date, and overdue tasks show a purple
dot inside a purple-outlined track. The due date is treated as the end of that
day.
Tasks with at least one unfinished prerequisite remain visually grayed out and
receive diagonal strike-through lines. The lines disappear as soon as all
prerequisites are `FINISHED`.
Paused tasks without an active dependency never use diagonal lines: the
diagonal pattern is reserved for dependency-blocked rows. A paused row is
rendered in a "standby" style instead — a dashed amber border (thicker on the
left edge), a ⏸ glyph in the left gutter and dimmed content. Finished tasks take
precedence over these row styles: they use a light gray background with white
text, regardless of their due date or prerequisite state. The `FINISHED`
status badge and active FINISH button use the same gray color.

Rows can be reordered with native HTML5 drag and drop, without a framework.
When dropped, the moved task receives an intermediate `sortOrder` calculated
from its displayed neighbors, updating the view immediately. The screen is
then blocked by a full-screen overlay while
`POST /api/tasks/{id}/reorder` runs. The backend renumbers all active tasks
with ascending integer positions to avoid numeric drift, then the list is
reloaded and the overlay is removed. `sortOrder` has no business meaning,
unlike `priority`; it is used only for display order.

## Local-first storage and synchronization

Each user runs a separate Durga instance on their workstation, with a separate
local checkout of a **dedicated Task data repository**. The configured Git
remote (normally GitLab) is the shared source of truth. Durga reads locally:

```text
dedicated-task-checkout/
  tasks/
    <task-uuid>.json
    <task-uuid>.json

Durga working directory (local only):
  data/task-order.json
  data/meetings.json
  logs/task-actions.log
```

The document UUID must match its filename. TaskService supplies business fields;
storage does not make business decisions. There is no global Task list file or
global save operation, database, ORM, or automatic migration. Legacy
`data/tasks.json` migration is a separate operational concern; this version
does not import it.

Synchronization happens at startup, explicit refresh/browser reload, and before
each shared mutation. There is **no automatic background polling or scheduler**.
The SPA refreshes on reload and exposes a refresh button. Normal API reads use
the local checkout. With a valid checkout but unreachable remote, startup logs
a warning and local reads remain available; refresh and shared writes report
the synchronization error. Shared writes must synchronize successfully first.
Each load reads all Task blob revisions with a single Git tree command, not
one process per Task. The SPA uses the Tasks returned by refresh directly;
it requests a separate local read only when synchronization fails.

One business checkpoint creates at most one commit: create, update/patch,
status/work-status, archive, delete, milestone, or a meeting due-date batch.
Messages identify the UUID and action, for example
`task <uuid>: workStatus TODO -> ON_IT`. UI reorder only writes a local UUID
array; it never changes shared Task documents, timestamps, or Git commits.
Unknown Task IDs receive a deterministic UUID-based fallback order. Stale
ordering entries do not prevent Tasks from displaying.

Optimistic concurrency is **per Task blob ID, not repository HEAD**. Unrelated
Tasks changed remotely do not invalidate a Task's revision. Concurrent edits to
the same Task cause a conflict, never an automatic JSON or field-level merge.
A separate race is possible when someone pushes between Durga's fetch and
push: that non-fast-forward push also fails, even for unrelated Tasks. The
caller must refresh and retry deliberately. Durga never automatically merges,
rebases, force-pushes, or retries a mutation.

A definite failed checkpoint restores only its owned Task paths and local
commit/index state to the pre-checkpoint HEAD. After an ambiguous push error,
Durga fetches and checks whether the exact commit reached the configured
upstream. Confirmed publication succeeds; confirmed nonpublication restores
and reports failure. If verification cannot contact the remote, Durga returns
503 and leaves Git's own commit state intact. Use explicit refresh once
connectivity returns. If local history is still ahead/diverged, inspect it
manually: preserve any wanted unpublished work elsewhere, then reconcile the
dedicated branch with its remote before retrying. Durga refuses to silently
publish ahead history. There is no custom recovery journal or transaction log.

## Task action audit log

Every business action performed on a task (creation, full update, partial
patch, status change, work-status change, archive, deletion, or a due-date
change triggered by a meeting) is written to a dedicated file, separate from
the regular application logs:

```text
logs/task-actions.log
logs/task-actions-YYYY-MM-DD.log   (rolled files; one per day, retained for 90 days)
```

Configuration is in `src/main/resources/logback.xml` and uses a Logback
`RollingFileAppender` with a daily `TimeBasedRollingPolicy`. The
`nf.durga.audit.task` logger, used exclusively by `TaskService`, writes one
line per action with the task UUID, subject, and change details (for example,
`from=TODO to=ON IT`). The `logs/` directory is not versioned.

This log makes it possible to replay a day's history to estimate the tasks
that were actually worked on (status/work-status changes), independently of
the final state stored in shared Task documents. Successful entries include the
declared Git actor. Git history is document version history; it does not replace
this local business audit log. Failed/unconfirmed writes are not logged as
successful business actions.

## Running the project

```powershell
git clone <your-task-repository-url> C:\dev\analysis\_DATAS\durga_tasks
git -C C:\dev\analysis\_DATAS\durga_tasks config user.name "Your Name"
git -C C:\dev\analysis\_DATAS\durga_tasks config user.email "you@example.com"
$env:DURGA_TASKS_REPOSITORY = 'C:\dev\analysis\_DATAS\durga_tasks'
.\gradlew.bat bootRun
```

For the first installation with legacy Tasks and an empty remote, follow
[the migration and Git initialization procedure](docs/MIGRATION.md) instead
of cloning. The conversion script is in `scripts\Migrate-Tasks.ps1`.

The remote repository must already have an initial commit and a branch;
the clone must have its upstream configured. Alternatively set Spring property
`durga.tasks.repository` to the absolute checkout path. Git must be on PATH.
Keep the checkout clean, dedicated to Task data, and separate from Durga's
local `data/` and `logs/` locations. Missing configuration, invalid checkout,
missing upstream/initial commit, missing identity, or dirty state fails startup
clearly. A simple local file lock prevents two Durga processes from owning the
same checkout. Use separate checkouts for separate installations.

Durga uses existing Git/GitLab credential configuration and does not manage
credentials, create repositories, or call GitLab APIs. Configured `user.name`
and `user.email` are **declared attribution, not secure authentication**.
There are no Durga user accounts, sessions, roles, or permissions.

The application is available at `http://localhost:8080/` (SPA) and
`http://localhost:8080/api/tasks` (API).

### Debugging in IntelliJ IDEA

Shared configurations are in `.run`. Stop any existing Durga process first,
and ensure the IDE inherits `DURGA_TASKS_REPOSITORY`.

1. Select **Durga - Gradle bootRun (debug)** and click **Run** (not Debug).
   `bootRun --debug-jvm` waits at `Listening for transport dt_socket at address: 5005`.
2. Select **Durga - Attach debugger (localhost:5005)** and click **Debug**.
   The application starts and Java breakpoints become active.

The debug listener is local-only. Stopping the attached debugger does not
necessarily stop Gradle; stop the Gradle run separately when finished.

## Tests and build

```powershell
.\gradlew.bat compileJava
.\gradlew.bat test
.\gradlew.bat build
node --test .\src\test\js\app.test.cjs
```

Git tests use temporary local checkouts and bare remotes; they do not require
GitLab or network access. The optional Node command runs the lightweight SPA
adapter tests using Node's built-in test runner, with no packages or frontend
build step.

The focused suite covers Task business rules, local meetings and ordering,
REST/MCP metadata, audit logging, independent-client Git concurrency, rejected
pushes, and ambiguous-push verification. It uses local Git repositories rather
than a hosted integration environment.

## Potential future improvements

- Validate inputs (`spring-boot-starter-validation` is already present as a
  dependency).
- Add authentication and authorization in front of the MCP endpoint before
  exposing it outside a local environment.
- Add deletion from the SPA.
