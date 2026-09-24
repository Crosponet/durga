const API_URL = "/api/tasks";

const taskListEl = document.getElementById("task-list");
const statusMessageEl = document.getElementById("status-message");
const taskModalEl = document.getElementById("task-modal");
const modalCloseEl = document.getElementById("modal-close");
const modalTitleEl = document.getElementById("modal-title");
const modalContentEl = document.getElementById("modal-content");
const statusFilterEl = document.getElementById("status-filter");
const serverFilterEl = document.getElementById("server-filter");
const resetFiltersEl = document.getElementById("reset-filters");
const filterCountEl = document.getElementById("filter-count");
const calendarEl = document.getElementById("calendar");
const calendarMonthEl = document.getElementById("calendar-month");
const previousMonthEl = document.getElementById("previous-month");
const nextMonthEl = document.getElementById("next-month");
const addMeetingEl = document.getElementById("add-meeting");
const meetingModalEl = document.getElementById("meeting-modal");
const meetingModalCloseEl = document.getElementById("meeting-modal-close");
const meetingModalContentEl = document.getElementById("meeting-modal-content");
let lastFocusedElement = null;
let allTasks = [];
let currentTask = null;
let meetings = [];
let displayedMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);

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

function meetingDayKey(meeting) {
    const date = toDateTime(meeting.meetingAt);
    if (!date || Number.isNaN(date.getTime())) {
        return "";
    }
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function badgeClass(prefix, value) {
    return `badge ${prefix}-${(value || "").toLowerCase()}`;
}

function createBadge(prefix, value, fallback = "INCONNU") {
    const badge = document.createElement("span");
    badge.className = badgeClass(prefix, value);
    badge.textContent = value || fallback;
    return badge;
}

function serverBadgeClass(serverKey) {
    const value = serverKey || "";
    const hash = [...value].reduce((sum, character) => sum + character.charCodeAt(0), 0);
    return `badge server-tag server-color-${hash % 6}`;
}

function serverColorClass(serverKey) {
    return serverBadgeClass(serverKey).split(" ").find((className) => className.startsWith("server-color-"));
}

function createServerBadge(serverKey) {
    const badge = document.createElement("span");
    badge.className = serverBadgeClass(serverKey);
    badge.textContent = serverKey || "SERVEUR INCONNU";
    return badge;
}

function createTaskRow(task) {
    const row = document.createElement("button");
    row.className = "task-row";
    row.type = "button";
    row.addEventListener("click", () => openTaskModal(task, row));

    const title = document.createElement("h2");
    title.textContent = task.subject || "(sans sujet)";
    row.appendChild(title);

    const badgeRow = document.createElement("div");
    badgeRow.className = "badge-row";
    badgeRow.appendChild(createBadge("status", task.status));
    badgeRow.appendChild(createServerBadge(task.serverKey));

    const waitingLabel = task.waitingFor === "CUSTOM" && task.waitingForCustom
        ? task.waitingForCustom
        : task.waitingFor;

    if (waitingLabel && waitingLabel !== "NONE") {
        const waitingBadge = createBadge("waiting", task.waitingFor);
        waitingBadge.textContent = `Attente : ${waitingLabel}`;
        badgeRow.appendChild(waitingBadge);
    }

    row.appendChild(badgeRow);

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
        <label>Tâches préalables (UUID, une par ligne ou séparées par des virgules)
            <textarea name="requires" rows="3"></textarea>
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
    fields.serverKey.value = task.serverKey || "";
    fields.iteration.value = task.iteration ?? "";
    fields.priority.value = task.priority ?? "";
    fields.dueDate.value = task.dueDate || "";
    fields.source.value = task.source || "";
    fields.waitingFor.value = task.waitingFor || "NONE";
    fields.waitingForCustom.value = task.waitingForCustom || "";
    fields.tags.value = task.tags || "";
    fields.jiraUrl.value = task.jiraUrl || "";
    fields.requires.value = (task.requires || []).join("\n");
    fields.archived.checked = Boolean(task.archived);
    return form;
}

function showTaskDetails(task) {
    const details = document.createElement("dl");
    details.className = "task-details";
    addDetail(details, "Description", task.description);
    addDetail(details, "Statut", task.status);
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
        ? task.requires.join(", ")
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
        const requires = data.get("requires").split(/[\s,]+/).filter(Boolean);
        const payload = {
            id: task.id,
            subject: data.get("subject").trim(),
            description: data.get("description"),
            iteration: data.get("iteration") ? Number(data.get("iteration")) : null,
            serverKey: data.get("serverKey").trim() || null,
            status: data.get("status"),
            waitingFor: data.get("waitingFor"),
            waitingForCustom: data.get("waitingForCustom").trim() || null,
            source: task.source,
            archived: data.get("archived") === "on",
            dueDate: data.get("dueDate") || null,
            tags: data.get("tags").trim() || null,
            priority: data.get("priority") ? Number(data.get("priority")) : null,
            jiraUrl: jiraUrl || null,
            requires
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
                throw new Error(`Erreur HTTP ${response.status}`);
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
    const year = displayedMonth.getFullYear();
    const month = displayedMonth.getMonth();
    calendarMonthEl.textContent = displayedMonth.toLocaleDateString("fr-FR", {
        month: "long", year: "numeric"
    });
    calendarEl.innerHTML = "";
    ["L", "M", "M", "J", "V", "S", "D"].forEach((day) => {
        const weekday = document.createElement("span");
        weekday.className = "calendar-weekday";
        weekday.textContent = day;
        calendarEl.appendChild(weekday);
    });

    const firstDay = new Date(year, month, 1);
    const offset = (firstDay.getDay() + 6) % 7;
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    for (let index = 0; index < offset + daysInMonth; index += 1) {
        const cell = document.createElement("div");
        cell.className = "calendar-day";
        if (index < offset) {
            cell.classList.add("calendar-day-empty");
        } else {
            const day = index - offset + 1;
            const key = `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
            const number = document.createElement("span");
            number.className = "calendar-day-number";
            number.textContent = day;
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
        }
        calendarEl.appendChild(cell);
    }
}

function showMeetingDetails(meeting) {
    const date = toDateTime(meeting.meetingAt);
    showError(`Réunion ${meeting.serverKey || "sans serveur"} : ${date.toLocaleString("fr-FR")}`);
}

function createMeetingForm() {
    const form = document.createElement("form");
    form.className = "task-form";
    form.innerHTML = `
        <label>Serveur<input name="serverKey" required></label>
        <label>Date et heure<input name="meetingAt" type="datetime-local" required></label>
        <label>Tâches associées (UUID, une par ligne ou séparées par des virgules)
            <textarea name="taskIds" rows="4"></textarea>
        </label>
        <p class="form-error" role="alert" hidden></p>
        <div class="form-actions">
            <button class="button button-secondary" type="button" data-cancel-meeting>Annuler</button>
            <button class="button button-primary" type="submit">Créer la réunion</button>
        </div>
    `;
    return form;
}

function openMeetingModal() {
    meetingModalContentEl.innerHTML = "";
    const form = createMeetingForm();
    const error = form.querySelector(".form-error");
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const data = new FormData(form);
        const taskIds = data.get("taskIds").split(/[\s,]+/).filter(Boolean);
        const payload = {
            serverKey: data.get("serverKey").trim(),
            meetingAt: data.get("meetingAt"),
            taskIds
        };
        const submitButton = form.querySelector("button[type=submit]");
        submitButton.disabled = true;
        try {
            const response = await fetch("/api/meetings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                const message = await response.text();
                throw new Error(message || `Erreur HTTP ${response.status}`);
            }
            meetings.push(await response.json());
            renderCalendar();
            closeMeetingModal();
        } catch (requestError) {
            error.textContent = `Impossible de créer la réunion : ${requestError.message}`;
            error.hidden = false;
            submitButton.disabled = false;
        }
    });
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

function renderTasks(tasks) {
    taskListEl.innerHTML = "";
    filterCountEl.textContent = `${tasks.length} tâche${tasks.length > 1 ? "s" : ""}`;

    if (!tasks || tasks.length === 0) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = allTasks.length === 0
            ? "Aucune tâche pour le moment."
            : "Aucune tâche ne correspond aux filtres.";
        taskListEl.appendChild(empty);
        return;
    }

    tasks.forEach((task) => {
        taskListEl.appendChild(createTaskRow(task));
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
    const filteredTasks = allTasks.filter((task) => {
        const matchesStatus = !statusFilterEl.value || task.status === statusFilterEl.value;
        const matchesServer = !serverFilterEl.value || task.serverKey === serverFilterEl.value;
        return matchesStatus && matchesServer;
    });
    renderTasks(filteredTasks);
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
    } catch (error) {
        showError(`Impossible de charger les réunions : ${error.message}`);
    }
}

statusFilterEl.addEventListener("change", applyFilters);
serverFilterEl.addEventListener("change", applyFilters);
resetFiltersEl.addEventListener("click", () => {
    statusFilterEl.value = "";
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
});

previousMonthEl.addEventListener("click", () => {
    displayedMonth = new Date(displayedMonth.getFullYear(), displayedMonth.getMonth() - 1, 1);
    renderCalendar();
});
nextMonthEl.addEventListener("click", () => {
    displayedMonth = new Date(displayedMonth.getFullYear(), displayedMonth.getMonth() + 1, 1);
    renderCalendar();
});
addMeetingEl.addEventListener("click", openMeetingModal);
meetingModalCloseEl.addEventListener("click", closeMeetingModal);
meetingModalEl.addEventListener("click", (event) => {
    if (event.target.matches("[data-close-meeting-modal]")) {
        closeMeetingModal();
    }
});

loadTasks();
renderCalendar();
loadMeetings();
