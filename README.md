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
- Storage: local JSON files (`data/tasks.json`), with **no ORM or database**
- Frontend: framework-free HTML/CSS/JS SPA, served statically by Spring Boot
  from `src/main/resources/static`

## Architecture

```text
src/main/java/nf/durga/
├── model/       Task, Status, WorkStatus, WaitingFor, TaskSource (enums + Lombok POJOs)
├── storage/     JsonTaskStorage (reads/writes data/tasks.json with Jackson)
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
- **`storage`** (`JsonTaskStorage`) only reads and writes the JSON file. It
  must not contain business logic or business-related Spring annotations
  (`@Component` is used only for wiring and injection). It exposes `load()` and
  `save(List<Task>)`.
- **`service`** (`TaskService`) contains all business logic, including UUID
  generation, `createdAt`/`updatedAt` handling, and future validation rules.
  It calls `storage.load()` at startup (`@PostConstruct`) and
  `storage.save()` after every modification.
- **`web`** (`TaskController`) contains no business logic and only delegates to
  `TaskService`. `ApiExceptionHandler` maps business exceptions such as
  `NoSuchElementException` to HTTP responses.
- **`mcp`** (`TaskMcpTools`) adapts MCP parameters and delegates exclusively to
  `TaskService`. MCP tools contain no business logic and never access storage
  directly.

```text
SPA -> REST ----\
                 -> TaskService -> JsonTaskStorage
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
| `sortOrder` | `Double` | Display position for drag-and-drop; unrelated to `priority` and renumbered by `TaskService.reorder()` |

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
| `POST` | `/api/tasks/{id}/reorder` | Moves a task to a given position; body `{"sortOrder": 2.5}`; recalculates and returns all active tasks with normalized positions (200 on success, 404 if absent) |

These endpoints only delegate to `TaskService`; `TaskController` contains no
business logic.

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
| `create_client_meeting` | `ClientMeetingService.create()` |
| `list_client_meetings` | `ClientMeetingService.list()` |
| `get_client_meeting` | `ClientMeetingService.get()` |
| `update_client_meeting` | `ClientMeetingService.update()` |
| `delete_client_meeting` | `ClientMeetingService.delete()` |

`update_task` is a patch operation: only non-null fields are changed. A
`null` value means "do not modify this field". Existing errors (for example,
an unknown ID) are returned to the MCP client. Invalid UUIDs, statuses,
waiting states, and dates produce explicit messages.

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
`change_status` with the selected UUID. The MCP HTTP server does not add
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

## Storage

`data/tasks.json` is created automatically on the first `save()` call. The
`data/` directory is not versioned. There is currently no schema migration or
versioning.

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
the final state stored in `data/tasks.json`.

## Running the project

```powershell
.\gradlew.bat bootRun
```

The application is available at `http://localhost:8080/` (SPA) and
`http://localhost:8080/api/tasks` (API).

## Tests and build

```powershell
.\gradlew.bat compileJava
.\gradlew.bat test
```

## Why are there so few tests?

Durga is developed in real time and used in production: when a feature is
needed, it is implemented and put to use immediately. When a bug appears, it
is fixed immediately.

Durga is intentionally simple and lightweight. A more comprehensive testing
system will be introduced if the software's complexity increases.

## Potential future improvements

- Validate inputs (`spring-boot-starter-validation` is already present as a
  dependency).
- Add authentication and authorization in front of the MCP endpoint before
  exposing it outside a local environment.
- Add deletion from the SPA.
