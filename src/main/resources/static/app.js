const API_URL = "/api/tasks";

const taskListEl = document.getElementById("task-list");
const statusMessageEl = document.getElementById("status-message");
const taskModalEl = document.getElementById("task-modal");
const modalCloseEl = document.getElementById("modal-close");
const modalTitleEl = document.getElementById("modal-title");
const modalContentEl = document.getElementById("modal-content");
const statusFilterEl = document.getElementById("status-filter");
const workStatusFilterEl = document.getElementById("work-status-filter");
const serverFilterEl = document.getElementById("server-filter");
const resetFiltersEl = document.getElementById("reset-filters");
const filterCountEl = document.getElementById("filter-count");
const blockOverlayEl = document.getElementById("block-overlay");
const calendarEl = document.getElementById("calendar");
const calendarRangeEl = document.getElementById("calendar-range");
const previousWeekEl = document.getElementById("previous-week");
const nextWeekEl = document.getElementById("next-week");
const calendarTodayEl = document.getElementById("calendar-today");
const addMeetingEl = document.getElementById("add-meeting");
const meetingModalEl = document.getElementById("meeting-modal");
const meetingModalCloseEl = document.getElementById("meeting-modal-close");
const meetingModalContentEl = document.getElementById("meeting-modal-content");
const milestoneModalEl = document.getElementById("milestone-modal");
const milestoneModalCloseEl = document.getElementById("milestone-modal-close");
const milestoneFormEl = document.getElementById("milestone-form");
let milestoneTask = null;
let milestoneSourceElement = null;
let lastFocusedElement = null;
let allTasks = [];
let currentTask = null;
let meetings = [];
const SERVER_COLOR_COUNT = 12;
const MINUTE_MS = 60000;
const MINUTES_PER_DAY = 1440;
const DUE_PROGRESS_MIN_DAYS = 7;
const DUE_PROGRESS_MAX_DAYS = 14;
const DUE_PROGRESS_MIN_WIDTH = 40;
const DUE_PROGRESS_MAX_WIDTH = 80;
const DUE_PROGRESS_HEIGHT = 12;
const CALENDAR_WEEKS = 4;
const CALENDAR_WEEKS_BEFORE = 1;
let weekStart = defaultWeekStart();
let draggedTaskId = null;
let highlightedRequirementIds = new Set();

function startOfDay(date) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date, amount) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate() + amount);
}

function startOfWeek(date) {
    const day = startOfDay(date);
    return addDays(day, -((day.getDay() + 6) % 7));
}

function defaultWeekStart() {
    return addDays(startOfWeek(new Date()), -7 * CALENDAR_WEEKS_BEFORE);
}

function showError(message) {
    statusMessageEl.textContent = message;
    statusMessageEl.hidden = false;
}

function formatDate(isoDateTime) {
    if (!isoDateTime) {
        return "-";
    }
    const date = new Date(isoDateTime);
    if (Number.isNaN(date.getTime())) {
        return isoDateTime;
    }
    return date.toLocaleString("fr-FR");
}

function toDateTime(value) {
    if (Array.isArray(value)) {
        const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value;
        return new Date(year, month - 1, day, hour, minute, second, nano / 1000000);
    }
    return value ? new Date(value) : null;
}

function toLocalDate(value) {
    if (Array.isArray(value)) {
        const [year, month, day] = value;
        return new Date(year, month - 1, day);
    }
    if (typeof value === "string" && value) {
        const [year, month, day] = value.split("-").map(Number);
        if (year && month && day) {
            return new Date(year, month - 1, day);
        }
    }
    return null;
}

function formatDayCount(days) {
    return `${days} jour${days > 1 ? "s" : ""}`;
}

function interpolateColor(start, end, ratio) {
    const channels = start.map((channel, index) =>
        Math.round(channel + (end[index] - channel) * ratio));
    return `rgb(${channels.join(", ")})`;
}

function dueProgressColor(daysUntilDue) {
    const green = [34, 197, 94];
    const orange = [249, 115, 22];
    const red = [220, 38, 38];

    if (daysUntilDue >= DUE_PROGRESS_MIN_DAYS) {
        return `rgb(${green.join(", ")})`;
    }
    if (daysUntilDue > 1) {
        const ratio = (DUE_PROGRESS_MIN_DAYS - daysUntilDue) / (DUE_PROGRESS_MIN_DAYS - 1);
        return interpolateColor(green, orange, ratio);
    }
    return `rgb(${red.join(", ")})`;
}

function updateDueProgress(progress, task) {
    const dueDay = toLocalDate(task.dueDate);
    if (!dueDay || Number.isNaN(dueDay.getTime())) {
        return;
    }
    const due = new Date(dueDay.getFullYear(), dueDay.getMonth(), dueDay.getDate(), 23, 59, 59);
    const now = new Date();
    const remainingMinutes = (due - now) / MINUTE_MS;
    const remainingDays = remainingMinutes / MINUTES_PER_DAY;
    const today = startOfDay(now);
    const daysUntilDue = Math.round((startOfDay(dueDay) - today) / (MINUTES_PER_DAY * MINUTE_MS));
    const isDueToday = daysUntilDue === 0;
    const widthRatio = Math.max(0, Math.min(1,
        (daysUntilDue - DUE_PROGRESS_MIN_DAYS) / (DUE_PROGRESS_MAX_DAYS - DUE_PROGRESS_MIN_DAYS)));
    const trackWidth = DUE_PROGRESS_MIN_WIDTH
        + widthRatio * (DUE_PROGRESS_MAX_WIDTH - DUE_PROGRESS_MIN_WIDTH);
    const fillWidth = daysUntilDue <= 0
        ? DUE_PROGRESS_HEIGHT
        : daysUntilDue === 1
            ? DUE_PROGRESS_HEIGHT + 4
            : daysUntilDue < DUE_PROGRESS_MIN_DAYS
                ? DUE_PROGRESS_HEIGHT + 4
                    + ((daysUntilDue - 1) / (DUE_PROGRESS_MIN_DAYS - 1))
                        * (DUE_PROGRESS_MIN_WIDTH - DUE_PROGRESS_HEIGHT - 4)
                : trackWidth;

    progress.classList.toggle("due-progress-overdue", remainingMinutes <= 0);
    progress.classList.toggle("due-progress-today", isDueToday && remainingMinutes > 0);
    progress.style.width = `${trackWidth}px`;
    progress.setAttribute("role", "progressbar");
    progress.setAttribute("aria-valuemin", "0");
    progress.setAttribute("aria-valuemax", "100");
    progress.setAttribute("aria-valuenow", String(remainingMinutes <= 0
        ? 0
        : Math.round(fillWidth / trackWidth * 100)));
    const fill = progress.firstElementChild;
    fill.style.width = remainingMinutes <= 0 ? "6px" : `${fillWidth}px`;
    fill.style.backgroundColor = remainingMinutes <= 0 ? "#7e22ce" : dueProgressColor(daysUntilDue);

    const dueLabel = dueDay.toLocaleDateString("fr-FR");
    if (remainingMinutes <= 0) {
        const lateDays = Math.max(1, Math.ceil(-remainingMinutes / MINUTES_PER_DAY));
        progress.title = `En retard de ${formatDayCount(lateDays)} (échéance ${dueLabel})`;
    } else {
        const days = Math.floor(remainingMinutes / MINUTES_PER_DAY);
        progress.title = days < 1
            ? `Échéance aujourd'hui (${dueLabel})`
            : `Échéance dans ${formatDayCount(days)} (${dueLabel})`;
    }
    progress.setAttribute("aria-label", progress.title);
}

function createDueProgress(task) {
    const dueDay = toLocalDate(task.dueDate);
    if (!dueDay || Number.isNaN(dueDay.getTime())) {
        return null;
    }
    const progress = document.createElement("span");
    progress.className = "due-progress";
    progress.dataset.taskId = task.id;

    const fill = document.createElement("span");
    fill.className = "due-progress-fill";
    progress.appendChild(fill);
    updateDueProgress(progress, task);
    return progress;
}

function refreshDueProgresses() {
    const tasksById = new Map(allTasks.map((task) => [task.id, task]));
    taskListEl.querySelectorAll(".due-progress").forEach((progress) => {
        const task = tasksById.get(progress.dataset.taskId);
        if (task) {
            updateDueProgress(progress, task);
        }
    });
}

function scheduleDueProgressRefresh() {
    const now = new Date();
    const nextDay = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    setTimeout(() => {
        refreshDueProgresses();
        scheduleDueProgressRefresh();
    }, nextDay - now);
}

function formatDateKey(date) {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function meetingDayKey(meeting) {
    const date = toDateTime(meeting.meetingAt);
    if (!date || Number.isNaN(date.getTime())) {
        return "";
    }
    return formatDateKey(date);
}

function badgeClass(prefix, value) {
    return `badge ${prefix}-${(value || "").toLowerCase().replaceAll(" ", "-")}`;
}

function createBadge(prefix, value, fallback = "INCONNU") {
    const badge = document.createElement("span");
    badge.className = badgeClass(prefix, value);
    badge.textContent = value || fallback;
    return badge;
}

function serverBadgeClass(serverKey) {
    return `badge server-tag ${serverColorClass(serverKey)}`;
}

function serverColorClass(serverKey) {
    const servers = [...new Set([
        ...allTasks.map((task) => task.serverKey),
        ...meetings.map((meeting) => meeting.serverKey)
    ].filter(Boolean))].sort((left, right) => left.localeCompare(right, "fr"));
    const serverIndex = servers.indexOf(serverKey);
    return `server-color-${(serverIndex >= 0 ? serverIndex : 0) % SERVER_COLOR_COUNT}`;
}

function createServerBadge(serverKey) {
    const badge = document.createElement("span");
    badge.className = serverBadgeClass(serverKey);
    badge.textContent = serverKey || "SERVEUR INCONNU";
    return badge;
}

function hasUnfinishedRequirement(task) {
    if (!task.requires || task.requires.length === 0) {
        return false;
    }
    return task.requires.some((requiredId) => {
        const requiredTask = allTasks.find((item) => item.id === requiredId);
        return requiredTask ? (requiredTask.workStatus || "TODO") !== "FINISHED" : false;
    });
}

function unfinishedRequirementIds(task) {
    return (task.requires || []).filter((requiredId) => {
        const requiredTask = allTasks.find((item) => item.id === requiredId);
        return requiredTask && (requiredTask.workStatus || "TODO") !== "FINISHED";
    });
}

function highlightBlockingRequirements(task) {
    clearBlockingRequirementHighlight();
    const requirementIds = unfinishedRequirementIds(task);
    if (requirementIds.length === 0) {
        return;
    }
    highlightedRequirementIds = new Set(requirementIds);
    requirementIds.forEach((requiredId) => {
        const row = taskListEl.querySelector(`[data-task-id="${CSS.escape(requiredId)}"]`);
        if (row) {
            row.classList.remove("task-row-filtered-out");
            row.classList.add("task-row-highlighted");
        }
    });
}

function clearBlockingRequirementHighlight() {
    if (highlightedRequirementIds.size === 0) {
        return;
    }
    highlightedRequirementIds.forEach((requiredId) => {
        const row = taskListEl.querySelector(`[data-task-id="${CSS.escape(requiredId)}"]`);
        if (row) {
            row.classList.remove("task-row-highlighted");
            if (row.dataset.filteredOut === "true") {
                row.classList.add("task-row-filtered-out");
            }
        }
    });
    highlightedRequirementIds.clear();
}

const PLAYER_ICONS = {
    PLAY: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11l9-5.5z"/></svg>',
    PAUSE: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5h3v11H4zM9 2.5h3v11H9z"/></svg>',
    FINISH: '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3h10v10H3z"/></svg>'
};

const PLAYER_BUTTONS = [
    { key: "PLAY", workStatus: "ON IT", label: "Démarrer (ON IT)" },
    { key: "PAUSE", workStatus: "PAUSE", label: "Mettre en pause" },
    { key: "FINISH", workStatus: "FINISHED", label: "Terminer" }
];

/**
 * État des boutons du lecteur selon le workStatus :
 * "active" (surbrillance), "clickable" (défaut) ou "disabled" (grisé).
 */
function playerButtonStates(task) {
    const workStatus = task.workStatus || "TODO";
    const states = {
        "ON IT": { PLAY: "active", PAUSE: "clickable", FINISH: "clickable" },
        PAUSE: { PLAY: "clickable", PAUSE: "active", FINISH: "clickable" },
        TODO: { PLAY: "clickable", PAUSE: "disabled", FINISH: "clickable" },
        FINISHED: { PLAY: "clickable", PAUSE: "disabled", FINISH: "active" }
    }[workStatus] || { PLAY: "clickable", PAUSE: "disabled", FINISH: "clickable" };

    if (hasUnfinishedRequirement(task)) {
        ["PLAY", "FINISH"].forEach((key) => {
            if (states[key] === "clickable") {
                states[key] = "disabled";
            }
        });
    }
    return states;
}

function createPlayerControls(task) {
    const controls = document.createElement("div");
    controls.className = "player-controls";
    controls.setAttribute("role", "group");
    controls.setAttribute("aria-label", `État de travail : ${task.workStatus || "TODO"}`);
    const states = playerButtonStates(task);
    const blocked = hasUnfinishedRequirement(task);

    PLAYER_BUTTONS.forEach(({ key, workStatus, label }) => {
        const state = states[key];
        const button = document.createElement("button");
        button.type = "button";
        button.className = `player-button player-${key.toLowerCase()} player-${state}`;
        button.innerHTML = PLAYER_ICONS[key];
        button.draggable = false;
        button.setAttribute("aria-label", label);
        button.setAttribute("aria-pressed", String(state === "active"));
        button.title = state === "disabled" && blocked && key !== "PAUSE"
            ? `${label} — bloqué par une tâche préalable non terminée`
            : label;
        button.disabled = state === "disabled";
        button.addEventListener("click", (event) => {
            event.stopPropagation();
            if (state === "clickable") {
                changeTaskWorkStatus(task, workStatus);
            }
        });
        button.addEventListener("keydown", (event) => event.stopPropagation());
        controls.appendChild(button);
    });
    return controls;
}

async function changeTaskWorkStatus(task, workStatus) {
    blockScreen();
    try {
        const response = await fetch(`${API_URL}/${encodeURIComponent(task.id)}/work-status`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ workStatus })
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || `Erreur HTTP ${response.status}`);
        }
        const updatedTask = await response.json();
        allTasks = allTasks.map((item) => item.id === updatedTask.id ? updatedTask : item);
        applyFilters();
    } catch (error) {
        showError(`Impossible de changer l'état de travail : ${error.message}`);
    } finally {
        unblockScreen();
    }
}

async function archiveTask(task) {
    blockScreen();
    try {
        const response = await fetch(`${API_URL}/${encodeURIComponent(task.id)}/archive`, {
            method: "POST"
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || `Erreur HTTP ${response.status}`);
        }
        allTasks = allTasks.filter((item) => item.id !== task.id);
        applyFilters();
    } catch (error) {
        showError(`Impossible d'archiver la tâche : ${error.message}`);
    } finally {
        unblockScreen();
    }
}

async function copyTaskId(task, button) {
    try {
        await navigator.clipboard.writeText(task.id);
        button.title = "ID copié";
        window.setTimeout(() => {
            button.title = "Copier l'identifiant";
        }, 1500);
    } catch (error) {
        showError(`Impossible de copier l'identifiant : ${error.message}`);
    }
}

function createTaskRow(task) {
    const row = document.createElement("div");
    row.className = "task-row";
    row.tabIndex = 0;
    row.setAttribute("role", "button");
    row.classList.add(serverColorClass(task.serverKey));
    if (task.workStatus === "FINISHED") {
        row.classList.add("task-row-finished");
        row.title = "Cette tâche est terminée.";
        row.style.setProperty("--task-row-bg", "#858b94");
        row.style.setProperty("--task-row-color", "#ffffff");
        row.style.background = "#858b94";
        row.style.color = "#ffffff";
        row.style.borderColor = "#747b85";
    } else if (hasUnfinishedRequirement(task)) {
        row.classList.add("task-row-dependent");
        row.title = "Cette tâche dépend d'autres tâches non terminées.";
    } else if (task.workStatus === "PAUSE") {
        row.classList.add("task-row-paused");
        row.title = "Cette tâche est en pause.";
    }
    row.draggable = true;
    row.dataset.taskId = task.id;
    row.addEventListener("mouseenter", () => highlightBlockingRequirements(task));
    row.addEventListener("mouseleave", clearBlockingRequirementHighlight);
    row.addEventListener("focusin", () => highlightBlockingRequirements(task));
    row.addEventListener("focusout", (event) => {
        if (!row.contains(event.relatedTarget)) {
            clearBlockingRequirementHighlight();
        }
    });
    row.addEventListener("click", () => {
        if (!row.classList.contains("dragging")) {
            openTaskModal(task, row);
        }
    });
    row.addEventListener("keydown", (event) => {
        if (event.target === row && (event.key === "Enter" || event.key === " ")) {
            event.preventDefault();
            openTaskModal(task, row);
        }
    });
    row.addEventListener("dragstart", (event) => {
        draggedTaskId = task.id;
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData("text/plain", task.id);
        row.classList.add("dragging");
    });
    row.addEventListener("dragend", () => {
        row.classList.remove("dragging");
        clearDropIndicators();
        draggedTaskId = null;
    });
    row.addEventListener("dragover", (event) => {
        if (!draggedTaskId || draggedTaskId === task.id) {
            return;
        }
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
        const isBefore = isDropBefore(event, row);
        clearDropIndicators();
        row.classList.add(isBefore ? "drop-before" : "drop-after");
    });
    row.addEventListener("drop", (event) => {
        event.preventDefault();
        const isBefore = isDropBefore(event, row);
        clearDropIndicators();
        if (draggedTaskId && draggedTaskId !== task.id) {
            handleTaskDrop(draggedTaskId, task.id, isBefore);
        }
        draggedTaskId = null;
    });

    const titleColumn = document.createElement("div");
    titleColumn.className = "task-title-column";
    const copyIdButton = document.createElement("button");
    copyIdButton.type = "button";
    copyIdButton.className = "task-copy-id-button";
    copyIdButton.setAttribute("aria-label", "Copier l'identifiant");
    copyIdButton.title = "Copier l'identifiant";
    copyIdButton.draggable = false;
    copyIdButton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 3h11a2 2 0 0 1 2 2v13h-2V5H8zm-3 4h11a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2m0 2v11h11V9z"/></svg>';
    copyIdButton.addEventListener("click", (event) => {
        event.stopPropagation();
        copyTaskId(task, copyIdButton);
    });
    copyIdButton.addEventListener("keydown", (event) => event.stopPropagation());
    const title = document.createElement("h2");
    title.textContent = task.subject || "(sans sujet)";
    titleColumn.append(copyIdButton, title);

    const workStatusColumn = document.createElement("div");
    workStatusColumn.className = "task-work-status-column";
    const milestoneButton = document.createElement("button");
    milestoneButton.type = "button";
    milestoneButton.className = "task-milestone-button";
    milestoneButton.setAttribute("aria-label", "Ajouter un jalon");
    milestoneButton.title = "Ajouter un jalon";
    milestoneButton.draggable = false;
    milestoneButton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 2h2v20H5zm3 1h11l-2.5 4.5L19 12H8z"/></svg>';
    milestoneButton.addEventListener("click", (event) => {
        event.stopPropagation();
        openMilestoneModal(task, milestoneButton);
    });
    milestoneButton.addEventListener("keydown", (event) => event.stopPropagation());
    workStatusColumn.append(createPlayerControls(task), milestoneButton);

    const detailsColumn = document.createElement("div");
    detailsColumn.className = "task-details-column";
    detailsColumn.appendChild(createBadge("status", task.status));
    if (task.workStatus !== "FINISHED") {
        const dueProgress = createDueProgress(task);
        if (dueProgress) {
            detailsColumn.appendChild(dueProgress);
        }
    }

    const waitingLabel = task.waitingFor === "CUSTOM" && task.waitingForCustom
        ? task.waitingForCustom
        : task.waitingFor;

    if (waitingLabel && waitingLabel !== "NONE") {
        const waitingBadge = createBadge("waiting", task.waitingFor);
        waitingBadge.textContent = `Attente : ${waitingLabel}`;
        detailsColumn.appendChild(waitingBadge);
    }

    if (task.tags) {
        task.tags.split(",")
            .map((tag) => tag.trim())
            .filter(Boolean)
            .forEach((tag) => {
                const tagBadge = createBadge("tag", tag);
                tagBadge.classList.add("tag");
                detailsColumn.appendChild(tagBadge);
            });
    }

    const clientColumn = document.createElement("div");
    clientColumn.className = "task-client-column";
    clientColumn.appendChild(createServerBadge(task.serverKey));

    row.append(clientColumn, titleColumn, workStatusColumn, detailsColumn);

    if (task.workStatus === "FINISHED") {
        const archiveButton = document.createElement("button");
        archiveButton.type = "button";
        archiveButton.className = "task-archive-button";
        archiveButton.setAttribute("aria-label", "Archiver la tâche");
        archiveButton.title = "Archiver la tâche";
        archiveButton.draggable = false;
        archiveButton.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 4h18v4H3zM5 10h14v10H5zM9 13v2h6v-2z"/></svg>';
        archiveButton.addEventListener("click", (event) => {
            event.stopPropagation();
            archiveTask(task);
        });
        archiveButton.addEventListener("keydown", (event) => event.stopPropagation());
        row.appendChild(archiveButton);
    } else {
        const archivePlaceholder = document.createElement("span");
        archivePlaceholder.className = "task-archive-placeholder";
        row.appendChild(archivePlaceholder);
    }

    return row;
}

function addDetail(parent, label, value) {
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    description.textContent = value ?? "-";
    parent.append(term, description);
}

function addLinkDetail(parent, label, url) {
    const term = document.createElement("dt");
    term.textContent = label;
    const description = document.createElement("dd");
    if (url && /^https?:\/\//i.test(url)) {
        const link = document.createElement("a");
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = url;
        description.appendChild(link);
    } else {
        description.textContent = url || "-";
    }
    parent.append(term, description);
}

function taskSubjectById(id) {
    const task = allTasks.find((item) => item.id === id);
    return task ? (task.subject || "(sans sujet)") : id;
}

function createTaskPicker(selectedIds, excludeTaskId, defaultServerKey) {
    const wrapper = document.createElement("div");
    wrapper.className = "task-picker";
    const selected = new Set(selectedIds || []);
    const servers = [...new Set(allTasks.filter((item) => !item.archived).map((item) => item.serverKey).filter(Boolean))].sort();
    const serverOptions = servers.map((server) => `<option value="${server}">${server}</option>`).join("");
    wrapper.innerHTML = `
        <select class="task-picker-server">
            <option value="">Tous les serveurs</option>
            ${serverOptions}
        </select>
        <div class="task-picker-list"></div>
    `;
    const serverSelect = wrapper.querySelector(".task-picker-server");
    const listEl = wrapper.querySelector(".task-picker-list");
    if (defaultServerKey && servers.includes(defaultServerKey)) {
        serverSelect.value = defaultServerKey;
    }
    function renderList() {
        listEl.innerHTML = "";
        const server = serverSelect.value;
        const candidates = allTasks.filter((item) => !item.archived
            && item.id !== excludeTaskId
            && (!server || item.serverKey === server));
        if (!candidates.length) {
            const empty = document.createElement("p");
            empty.className = "task-picker-empty";
            empty.textContent = "Aucune tâche disponible.";
            listEl.appendChild(empty);
            return;
        }
        candidates.forEach((item) => {
            const label = document.createElement("label");
            label.className = "task-picker-item";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.dataset.taskId = item.id;
            checkbox.checked = selected.has(item.id);
            const text = document.createElement("span");
            text.textContent = item.subject || "(sans sujet)";
            label.append(checkbox, text);
            listEl.appendChild(label);
        });
    }
    listEl.addEventListener("change", (event) => {
        const checkbox = event.target.closest("input[type=checkbox]");
        if (!checkbox) {
            return;
        }
        if (checkbox.checked) {
            selected.add(checkbox.dataset.taskId);
        } else {
            selected.delete(checkbox.dataset.taskId);
        }
    });
    serverSelect.addEventListener("change", renderList);
    renderList();
    wrapper.getSelectedIds = () => Array.from(selected);
    return wrapper;
}

function createTaskForm(task) {
    const form = document.createElement("form");
    form.className = "task-form";
    form.innerHTML = `
        <label>Sujet<input name="subject" required maxlength="500"></label>
        <label>Description<textarea name="description" rows="4"></textarea></label>
        <div class="form-grid">
            <label>Statut<select name="status" required>
                <option value="DATA_GRABBING">Collecte de données</option>
                <option value="INVESTIGATION">Investigation</option>
                <option value="FIXING">Correction</option>
                <option value="REPORT">Rapport</option>
            </select></label>
            <label>État de travail<select name="workStatus" required>
                <option value="TODO">À faire</option>
                <option value="ON IT">En cours</option>
                <option value="PAUSE">En pause</option>
                <option value="FINISHED">Terminée</option>
            </select></label>
            <label>Serveur<input name="serverKey"></label>
            <label>Itération<input name="iteration" type="number" min="0"></label>
            <label>Priorité<input name="priority" type="number" min="1" max="4"></label>
            <label>Échéance<input name="dueDate" type="date"></label>
            <label>Source<input name="source" readonly></label>
        </div>
        <div class="form-grid">
            <label>Attente<select name="waitingFor" required>
                <option value="NONE">Aucune</option>
                <option value="CLOUDOPS">CloudOps</option>
                <option value="RND">R&amp;D</option>
                <option value="CUSTOM">Personnalisée</option>
            </select></label>
            <label>Libellé d'attente<input name="waitingForCustom"></label>
        </div>
        <label>Tags<input name="tags"></label>
        <label>Lien Jira<input name="jiraUrl" type="url" placeholder="https://..."></label>
        <label>Tâches préalables
            <div data-requires-picker></div>
        </label>
        <label class="checkbox-field">
            <input name="archived" type="checkbox"> Tâche archivée
        </label>
        <p class="form-error" role="alert" hidden></p>
        <div class="form-actions">
            <button class="button button-secondary" type="button" data-cancel-edit>Annuler</button>
            <button class="button button-primary" type="submit">Enregistrer</button>
        </div>
    `;

    const fields = form.elements;
    fields.subject.value = task.subject || "";
    fields.description.value = task.description || "";
    fields.status.value = task.status || "INVESTIGATION";
    fields.workStatus.value = task.workStatus || "TODO";
    fields.serverKey.value = task.serverKey || "";
    fields.iteration.value = task.iteration ?? "";
    fields.priority.value = task.priority ?? "";
    fields.dueDate.value = task.dueDate || "";
    fields.source.value = task.source || "";
    fields.waitingFor.value = task.waitingFor || "NONE";
    fields.waitingForCustom.value = task.waitingForCustom || "";
    fields.tags.value = task.tags || "";
    fields.jiraUrl.value = task.jiraUrl || "";
    fields.archived.checked = Boolean(task.archived);
    const requiresPicker = createTaskPicker(task.requires || [], task.id, task.serverKey);
    form.querySelector("[data-requires-picker]").appendChild(requiresPicker);
    form.requiresPicker = requiresPicker;
    return form;
}

function showTaskDetails(task) {
    const details = document.createElement("dl");
    details.className = "task-details";
    addDetail(details, "Description", task.description);
    addDetail(details, "Statut", task.status);
    addDetail(details, "État de travail", task.workStatus || "TODO");
    addDetail(details, "Priorité", task.priority);
    addDetail(details, "Serveur", task.serverKey);
    addDetail(details, "Itération", task.iteration);
    addDetail(details, "Attente", task.waitingFor === "CUSTOM" && task.waitingForCustom
        ? task.waitingForCustom
        : task.waitingFor);
    addDetail(details, "Source", task.source);
    addDetail(details, "Tags", task.tags);
    addDetail(details, "Échéance", task.dueDate);
    addDetail(details, "Créée le", formatDate(task.createdAt));
    addDetail(details, "Mise à jour le", formatDate(task.updatedAt));
    addDetail(details, "Archivée", task.archived ? "Oui" : "Non");
    addLinkDetail(details, "Demande Jira", task.jiraUrl);
    addDetail(details, "Tâches préalables", task.requires?.length
        ? task.requires.map(taskSubjectById).join(", ")
        : "-");
    addDetail(details, "Identifiant", task.id);
    return details;
}

function openTaskModal(task, sourceElement) {
    currentTask = task;
    lastFocusedElement = sourceElement;
    modalTitleEl.textContent = task.subject || "(sans sujet)";
    modalContentEl.innerHTML = "";
    const details = showTaskDetails(task);
    const editButton = document.createElement("button");
    editButton.className = "button button-primary";
    editButton.type = "button";
    editButton.textContent = "Modifier";
    editButton.addEventListener("click", () => showTaskEdit(task));
    modalContentEl.append(details, editButton);
    taskModalEl.hidden = false;
    document.body.classList.add("modal-open");
    modalCloseEl.focus();
}

function showTaskEdit(task) {
    modalContentEl.innerHTML = "";
    const form = createTaskForm(task);
    const error = form.querySelector(".form-error");
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        error.hidden = true;
        const data = new FormData(form);
        const jiraUrl = data.get("jiraUrl").trim();
        if (jiraUrl && !/^https?:\/\//i.test(jiraUrl)) {
            error.textContent = "Le lien Jira doit commencer par http:// ou https://.";
            error.hidden = false;
            return;
        }
        const requires = form.requiresPicker.getSelectedIds();
        const payload = {
            id: task.id,
            subject: data.get("subject").trim(),
            description: data.get("description"),
            iteration: data.get("iteration") ? Number(data.get("iteration")) : null,
            serverKey: data.get("serverKey").trim() || null,
            status: data.get("status"),
            workStatus: data.get("workStatus"),
            waitingFor: data.get("waitingFor"),
            waitingForCustom: data.get("waitingForCustom").trim() || null,
            source: task.source,
            archived: data.get("archived") === "on",
            dueDate: data.get("dueDate") || null,
            tags: data.get("tags").trim() || null,
            priority: data.get("priority") ? Number(data.get("priority")) : null,
            jiraUrl: jiraUrl || null,
            requires,
            sortOrder: task.sortOrder
        };
        const submitButton = form.querySelector("button[type=submit]");
        submitButton.disabled = true;
        try {
            const response = await fetch(`${API_URL}/${encodeURIComponent(task.id)}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || `Erreur HTTP ${response.status}`);
            }
            const updatedTask = await response.json();
            allTasks = allTasks.map((item) => item.id === updatedTask.id ? updatedTask : item);
            populateServerFilter(allTasks);
            applyFilters();
            openTaskModal(updatedTask, lastFocusedElement);
        } catch (requestError) {
            error.textContent = `Impossible d'enregistrer la tâche : ${requestError.message}`;
            error.hidden = false;
            submitButton.disabled = false;
        }
    });
    form.querySelector("[data-cancel-edit]").addEventListener("click", () => {
        openTaskModal(task, lastFocusedElement);
    });
    modalContentEl.appendChild(form);
    form.elements.subject.focus();
}

function closeTaskModal() {
    if (taskModalEl.hidden) {
        return;
    }
    taskModalEl.hidden = true;
    document.body.classList.remove("modal-open");
    currentTask = null;
    lastFocusedElement?.focus();
}

function renderCalendar() {
    const totalDays = CALENDAR_WEEKS * 7;
    const lastDay = addDays(weekStart, totalDays - 1);
    const rangeOptions = { day: "numeric", month: "short" };
    const startLabel = weekStart.toLocaleDateString("fr-FR", rangeOptions);
    const endLabel = lastDay.toLocaleDateString("fr-FR", rangeOptions);
    calendarRangeEl.textContent = `${startLabel} – ${endLabel} ${lastDay.getFullYear()}`;
    calendarEl.innerHTML = "";
    ["L", "M", "M", "J", "V", "S", "D"].forEach((day) => {
        const weekday = document.createElement("span");
        weekday.className = "calendar-weekday";
        weekday.textContent = day;
        calendarEl.appendChild(weekday);
    });

    const today = startOfDay(new Date());
    for (let index = 0; index < totalDays; index += 1) {
        const current = addDays(weekStart, index);
        const cell = document.createElement("div");
        cell.className = "calendar-day";
        if (current.getTime() === today.getTime()) {
            cell.classList.add("is-today");
        } else if (current < today) {
            cell.classList.add("is-past");
        }
        if (current.getDay() === 0 || current.getDay() === 6) {
            cell.classList.add("is-weekend");
        }

        const key = formatDateKey(current);
        const number = document.createElement("span");
        number.className = "calendar-day-number";
        number.textContent = current.getDate() === 1
            ? current.toLocaleDateString("fr-FR", { day: "numeric", month: "short" })
            : current.getDate();
        cell.appendChild(number);
        meetings.filter((meeting) => meetingDayKey(meeting) === key).forEach((meeting) => {
            const meetingLink = document.createElement("button");
            meetingLink.type = "button";
            meetingLink.className = `calendar-meeting ${serverColorClass(meeting.serverKey)}`;
            const date = toDateTime(meeting.meetingAt);
            meetingLink.textContent = `${date.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })} ${meeting.serverKey || "?"}`;
            meetingLink.title = `Réunion ${meeting.serverKey || "sans serveur"}`;
            meetingLink.addEventListener("click", () => showMeetingDetails(meeting));
            cell.appendChild(meetingLink);
        });
        calendarEl.appendChild(cell);
    }
}

function showMeetingDetails(meeting) {
    openMeetingModal(meeting);
}

function toDatetimeLocalValue(date) {
    if (!date || Number.isNaN(date.getTime())) {
        return "";
    }
    const pad = (value) => String(value).padStart(2, "0");
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function createMeetingForm(meeting) {
    const form = document.createElement("form");
    form.className = "task-form";
    const meetingAtValue = meeting ? toDatetimeLocalValue(toDateTime(meeting.meetingAt)) : "";
    form.innerHTML = `
        <label>Serveur<input name="serverKey" required></label>
        <label>Date et heure<input name="meetingAt" type="datetime-local" required></label>
        <label>Tâches associées
            <div data-task-ids-picker></div>
        </label>
        <p class="form-error" role="alert" hidden></p>
        <div class="form-actions">
            ${meeting ? '<button class="button button-danger" type="button" data-delete-meeting>Supprimer</button>' : ""}
            <button class="button button-secondary" type="button" data-cancel-meeting>Annuler</button>
            <button class="button button-primary" type="submit">${meeting ? "Enregistrer" : "Créer la réunion"}</button>
        </div>
    `;
    form.elements.serverKey.value = meeting ? meeting.serverKey || "" : "";
    form.elements.meetingAt.value = meetingAtValue;
    const taskIdsPicker = createTaskPicker(meeting ? meeting.taskIds || [] : [], null, meeting ? meeting.serverKey : null);
    form.querySelector("[data-task-ids-picker]").appendChild(taskIdsPicker);
    form.taskIdsPicker = taskIdsPicker;
    return form;
}

function openMeetingModal(meeting) {
    meetingModalContentEl.innerHTML = "";
    const form = createMeetingForm(meeting);
    const error = form.querySelector(".form-error");
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const data = new FormData(form);
        const taskIds = form.taskIdsPicker.getSelectedIds();
        const payload = {
            serverKey: data.get("serverKey").trim(),
            meetingAt: data.get("meetingAt"),
            taskIds
        };
        const submitButton = form.querySelector("button[type=submit]");
        submitButton.disabled = true;
        try {
            const url = meeting ? `/api/meetings/${meeting.id}` : "/api/meetings";
            const response = await fetch(url, {
                method: meeting ? "PUT" : "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || `Erreur HTTP ${response.status}`);
            }
            const saved = await response.json();
            if (meeting) {
                const index = meetings.findIndex((item) => item.id === meeting.id);
                if (index >= 0) {
                    meetings[index] = saved;
                }
            } else {
                meetings.push(saved);
            }
            renderCalendar();
            closeMeetingModal();
        } catch (requestError) {
            error.textContent = `Impossible d'enregistrer la réunion : ${requestError.message}`;
            error.hidden = false;
            submitButton.disabled = false;
        }
    });
    const deleteButton = form.querySelector("[data-delete-meeting]");
    if (deleteButton) {
        deleteButton.addEventListener("click", async () => {
            if (!window.confirm("Supprimer cette réunion ?")) {
                return;
            }
            deleteButton.disabled = true;
            try {
                const response = await fetch(`/api/meetings/${meeting.id}`, { method: "DELETE" });
                if (!response.ok && response.status !== 204) {
                    const message = await response.text();
                    throw new Error(message || `Erreur HTTP ${response.status}`);
                }
                meetings = meetings.filter((item) => item.id !== meeting.id);
                renderCalendar();
                closeMeetingModal();
            } catch (requestError) {
                error.textContent = `Impossible de supprimer la réunion : ${requestError.message}`;
                error.hidden = false;
                deleteButton.disabled = false;
            }
        });
    }
    form.querySelector("[data-cancel-meeting]").addEventListener("click", closeMeetingModal);
    meetingModalContentEl.appendChild(form);
    meetingModalEl.hidden = false;
    document.body.classList.add("modal-open");
    form.elements.serverKey.focus();
}

function closeMeetingModal() {
    meetingModalEl.hidden = true;
    document.body.classList.remove("modal-open");
}

function openMilestoneModal(task, sourceElement) {
    milestoneTask = task;
    milestoneSourceElement = sourceElement;
    milestoneFormEl.reset();
    const error = milestoneFormEl.querySelector(".form-error");
    error.hidden = true;
    error.textContent = "";
    milestoneFormEl.querySelector("button[type=submit]").disabled = false;
    milestoneModalEl.hidden = false;
    document.body.classList.add("modal-open");
    milestoneFormEl.elements.text.focus();
}

function closeMilestoneModal() {
    if (milestoneModalEl.hidden) {
        return;
    }
    milestoneModalEl.hidden = true;
    document.body.classList.remove("modal-open");
    milestoneTask = null;
    milestoneSourceElement?.focus();
    milestoneSourceElement = null;
}

async function submitMilestone(event) {
    event.preventDefault();
    const text = milestoneFormEl.elements.text.value.trim();
    const error = milestoneFormEl.querySelector(".form-error");
    if (!text || !milestoneTask) {
        error.textContent = "Le texte du jalon est obligatoire.";
        error.hidden = false;
        return;
    }
    const submitButton = milestoneFormEl.querySelector("button[type=submit]");
    submitButton.disabled = true;
    try {
        const response = await fetch(`${API_URL}/${encodeURIComponent(milestoneTask.id)}/milestone`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text })
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || `Erreur HTTP ${response.status}`);
        }
        const updatedTask = await response.json();
        allTasks = allTasks.map((item) => item.id === updatedTask.id ? updatedTask : item);
        milestoneSourceElement = null;
        closeMilestoneModal();
        applyFilters();
    } catch (requestError) {
        error.textContent = `Impossible d'ajouter le jalon : ${requestError.message}`;
        error.hidden = false;
        submitButton.disabled = false;
    }
}

function renderTasks(tasks) {
    clearBlockingRequirementHighlight();
    taskListEl.innerHTML = "";
    filterCountEl.textContent = `${tasks.length} tâche${tasks.length > 1 ? "s" : ""}`;

    const visibleTaskIds = new Set((tasks || []).map((task) => task.id));
    const sortedTasks = [...allTasks].sort((left, right) =>
        (left.sortOrder ?? Infinity) - (right.sortOrder ?? Infinity));

    if (!tasks || tasks.length === 0) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = allTasks.length === 0
            ? "Aucune tâche pour le moment."
            : "Aucune tâche ne correspond aux filtres.";
        taskListEl.appendChild(empty);
    }

    sortedTasks.forEach((task) => {
        const row = createTaskRow(task);
        if (!visibleTaskIds.has(task.id)) {
            row.dataset.filteredOut = "true";
            row.classList.add("task-row-filtered-out");
        }
        taskListEl.appendChild(row);
    });
}

function populateServerFilter(tasks) {
    const servers = [...new Set(tasks
        .map((task) => task.serverKey)
        .filter(Boolean))]
        .sort((left, right) => left.localeCompare(right, "fr"));

    serverFilterEl.replaceChildren(new Option("Tous les serveurs", ""));
    servers.forEach((server) => {
        serverFilterEl.add(new Option(server, server));
    });
}

function applyFilters() {
    const filteredTasks = allTasks
        .filter((task) => {
            const matchesStatus = !statusFilterEl.value || task.status === statusFilterEl.value;
            const matchesWorkStatus = !workStatusFilterEl.value
                || (task.workStatus || "TODO") === workStatusFilterEl.value;
            const matchesServer = !serverFilterEl.value || task.serverKey === serverFilterEl.value;
            return matchesStatus && matchesWorkStatus && matchesServer;
        })
        .sort((left, right) => (left.sortOrder ?? Infinity) - (right.sortOrder ?? Infinity));
    renderTasks(filteredTasks);
}

function isDropBefore(event, row) {
    const bounds = row.getBoundingClientRect();
    return event.clientY < bounds.top + bounds.height / 2;
}

function clearDropIndicators() {
    taskListEl.querySelectorAll(".drop-before, .drop-after").forEach((row) => {
        row.classList.remove("drop-before", "drop-after");
    });
}

function blockScreen() {
    blockOverlayEl.hidden = false;
}

function unblockScreen() {
    blockOverlayEl.hidden = true;
}

/**
 * Calcule un sortOrder intermédiaire pour la tâche déplacée en fonction de
 * ses nouvelles voisines dans la liste actuellement affichée (après
 * filtrage), applique ce changement localement pour un retour visuel
 * immédiat, puis envoie la nouvelle position au backend qui recalcule et
 * renumbote l'ensemble des tâches.
 */
async function handleTaskDrop(draggedId, targetId, dropBefore) {
    const displayedTasks = allTasks
        .filter((task) => task.id !== draggedId)
        .filter((task) => {
            const matchesStatus = !statusFilterEl.value || task.status === statusFilterEl.value;
            const matchesWorkStatus = !workStatusFilterEl.value
                || (task.workStatus || "TODO") === workStatusFilterEl.value;
            const matchesServer = !serverFilterEl.value || task.serverKey === serverFilterEl.value;
            return matchesStatus && matchesWorkStatus && matchesServer;
        })
        .sort((left, right) => (left.sortOrder ?? Infinity) - (right.sortOrder ?? Infinity));

    const targetIndex = displayedTasks.findIndex((task) => task.id === targetId);
    const insertIndex = dropBefore ? targetIndex : targetIndex + 1;
    const previousTask = displayedTasks[insertIndex - 1];
    const nextTask = displayedTasks[insertIndex];
    const previousOrder = previousTask ? previousTask.sortOrder ?? 0 : 0;
    const nextOrder = nextTask ? nextTask.sortOrder ?? previousOrder + 2 : previousOrder + 2;
    const intermediateSortOrder = (previousOrder + nextOrder) / 2;

    const draggedTask = allTasks.find((task) => task.id === draggedId);
    if (!draggedTask) {
        return;
    }
    draggedTask.sortOrder = intermediateSortOrder;
    applyFilters();

    blockScreen();
    try {
        const response = await fetch(`${API_URL}/${encodeURIComponent(draggedId)}/reorder`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ sortOrder: intermediateSortOrder })
        });
        if (!response.ok) {
            const message = await response.text();
            throw new Error(message || `Erreur HTTP ${response.status}`);
        }
        allTasks = await response.json();
        applyFilters();
    } catch (error) {
        showError(`Impossible de réorganiser les tâches : ${error.message}`);
        await loadTasks();
    } finally {
        unblockScreen();
    }
}

async function loadTasks() {
    try {
        const response = await fetch(API_URL);
        if (!response.ok) {
            throw new Error(`Erreur HTTP ${response.status}`);
        }
        allTasks = await response.json();
        populateServerFilter(allTasks);
        applyFilters();
        renderCalendar();
    } catch (error) {
        showError(`Impossible de charger les tâches : ${error.message}`);
    }
}

async function loadMeetings() {
    try {
        const response = await fetch("/api/meetings");
        if (!response.ok) {
            throw new Error(`Erreur HTTP ${response.status}`);
        }
        meetings = await response.json();
        renderCalendar();
        applyFilters();
    } catch (error) {
        showError(`Impossible de charger les réunions : ${error.message}`);
    }
}

statusFilterEl.addEventListener("change", applyFilters);
workStatusFilterEl.addEventListener("change", applyFilters);
serverFilterEl.addEventListener("change", applyFilters);
resetFiltersEl.addEventListener("click", () => {
    statusFilterEl.value = "";
    workStatusFilterEl.value = "";
    serverFilterEl.value = "";
    applyFilters();
});
modalCloseEl.addEventListener("click", closeTaskModal);
taskModalEl.addEventListener("click", (event) => {
    if (event.target.matches("[data-close-modal]")) {
        closeTaskModal();
    }
});
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        closeTaskModal();
    }
    if (event.key === "Escape" && !meetingModalEl.hidden) {
        closeMeetingModal();
    }
    if (event.key === "Escape" && !milestoneModalEl.hidden) {
        closeMilestoneModal();
    }
});

previousWeekEl.addEventListener("click", () => {
    weekStart = addDays(weekStart, -7);
    renderCalendar();
});
nextWeekEl.addEventListener("click", () => {
    weekStart = addDays(weekStart, 7);
    renderCalendar();
});
calendarTodayEl.addEventListener("click", () => {
    weekStart = defaultWeekStart();
    renderCalendar();
});
addMeetingEl.addEventListener("click", () => openMeetingModal());
meetingModalCloseEl.addEventListener("click", closeMeetingModal);
meetingModalEl.addEventListener("click", (event) => {
    if (event.target.matches("[data-close-meeting-modal]")) {
        closeMeetingModal();
    }
});
milestoneModalCloseEl.addEventListener("click", closeMilestoneModal);
milestoneModalEl.addEventListener("click", (event) => {
    if (event.target.matches("[data-close-milestone-modal]")) {
        closeMilestoneModal();
    }
});
milestoneFormEl.addEventListener("submit", submitMilestone);
milestoneFormEl.elements.text.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
        milestoneFormEl.requestSubmit();
    }
});

loadTasks();
renderCalendar();
loadMeetings();
scheduleDueProgressRefresh();
