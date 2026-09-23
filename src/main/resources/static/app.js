const API_URL = "/api/tasks";

const taskListEl = document.getElementById("task-list");
const statusMessageEl = document.getElementById("status-message");

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

function badgeClass(prefix, value) {
    return `badge ${prefix}-${(value || "").toLowerCase()}`;
}

function createTaskCard(task) {
    const card = document.createElement("article");
    card.className = "task-card";

    const title = document.createElement("h2");
    title.textContent = task.subject || "(sans sujet)";
    card.appendChild(title);

    if (task.description) {
        const description = document.createElement("p");
        description.className = "description";
        description.textContent = task.description;
        card.appendChild(description);
    }

    const badgeRow = document.createElement("div");
    badgeRow.className = "badge-row";

    const statusBadge = document.createElement("span");
    statusBadge.className = badgeClass("status", task.status);
    statusBadge.textContent = task.status || "INCONNU";
    badgeRow.appendChild(statusBadge);

    const waitingLabel = task.waitingFor === "CUSTOM" && task.waitingForCustom
        ? task.waitingForCustom
        : task.waitingFor;

    if (waitingLabel && waitingLabel !== "NONE") {
        const waitingBadge = document.createElement("span");
        waitingBadge.className = badgeClass("waiting", task.waitingFor);
        waitingBadge.textContent = `Attente : ${waitingLabel}`;
        badgeRow.appendChild(waitingBadge);
    }

    card.appendChild(badgeRow);

    const meta = document.createElement("div");
    meta.className = "meta";
    meta.innerHTML = `
        <span><strong>Itération :</strong> ${task.iteration ?? "-"}</span>
        <span><strong>Serveur :</strong> ${task.serverKey ?? "-"}</span>
        <span><strong>Source :</strong> ${task.source ?? "-"}</span>
        <span><strong>Créée le :</strong> ${formatDate(task.createdAt)}</span>
        <span><strong>Mise à jour le :</strong> ${formatDate(task.updatedAt)}</span>
    `;
    card.appendChild(meta);

    return card;
}

function renderTasks(tasks) {
    taskListEl.innerHTML = "";

    if (!tasks || tasks.length === 0) {
        const empty = document.createElement("div");
        empty.className = "empty-state";
        empty.textContent = "Aucune tâche pour le moment.";
        taskListEl.appendChild(empty);
        return;
    }

    tasks.forEach((task) => {
        taskListEl.appendChild(createTaskCard(task));
    });
}

async function loadTasks() {
    try {
        const response = await fetch(API_URL);
        if (!response.ok) {
            throw new Error(`Erreur HTTP ${response.status}`);
        }
        const tasks = await response.json();
        renderTasks(tasks);
    } catch (error) {
        showError(`Impossible de charger les tâches : ${error.message}`);
    }
}

loadTasks();
