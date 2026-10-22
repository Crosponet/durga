const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

function application() {
    const calls = [];
    const elements = new Map();
    const element = () => ({
        hidden: false,
        textContent: "",
        addEventListener() {},
        elements: { text: { value: "", addEventListener() {} } },
        querySelector() { return element(); },
        appendChild() {},
        classList: { add() {}, remove() {} }
    });
    const context = vm.createContext({
        document: {
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, element());
                return elements.get(id);
            },
            addEventListener() {},
            body: element()
        },
        window: { markdownit: () => ({ render: (source) => source }) },
        fetch: async (url, options) => {
            calls.push({ url, options });
            return {
                ok: true,
                json: async () => ({ id: "task-id", revision: "b".repeat(40) }),
                text: async () => ""
            };
        },
        setTimeout() {},
        console
    });
    const source = fs.readFileSync(path.join(__dirname, "..", "..", "main", "resources", "static", "app.js"), "utf8");
    vm.runInContext(source.slice(0, source.lastIndexOf("\nloadTasks();")), context);
    vm.runInContext("applyFilters = () => {}; renderCalendar = () => {}; populateServerFilter = () => {};", context);
    return { context, calls, elements };
}

test("work status and archive carry the read revision as a quoted If-Match", async () => {
    const { context, calls } = application();
    await vm.runInContext(`changeTaskWorkStatus({id:"task-id",revision:"${"a".repeat(40)}"}, "ON IT")`, context);
    await vm.runInContext(`archiveTask({id:"task-id",revision:"${"b".repeat(40)}"})`, context);
    assert.equal(calls[0].options.headers["If-Match"], `"${"a".repeat(40)}"`);
    assert.equal(calls[1].options.headers["If-Match"], `"${"b".repeat(40)}"`);
    assert.equal(JSON.parse(calls[0].options.body).workStatus, "ON IT");
});

test("a conflict stays visible and does not retry the mutation", async () => {
    const { context, calls, elements } = application();
    context.fetch = async (url, options) => {
        calls.push({ url, options });
        return { ok: false, status: 409, text: async () => "Task changed remotely. Reload before editing." };
    };
    await vm.runInContext(`changeTaskWorkStatus({id:"task-id",revision:"${"a".repeat(40)}"}, "FINISHED")`, context);
    assert.equal(calls.length, 1);
    assert.match(elements.get("status-message").textContent, /Reload before editing/);
});

test("milestone uses the revision captured when its form was opened", async () => {
    const { context, calls } = application();
    vm.runInContext(`milestoneTask = {id:"task-id",revision:"${"a".repeat(40)}"};
        milestoneFormEl.elements.text.value = "Milestone";
        closeMilestoneModal = () => {};`, context);
    await vm.runInContext("submitMilestone({preventDefault(){}})", context);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].options.headers["If-Match"], `"${"a".repeat(40)}"`);
    assert.equal(JSON.parse(calls[0].options.body).text, "Milestone");
});

test("explicit reload reports synchronization failure and still reads local Tasks", async () => {
    const { context, calls, elements } = application();
    context.fetch = async (url, options) => {
        calls.push({ url, options });
        return url.endsWith("/refresh")
            ? { ok: false, text: async () => "Remote unavailable" }
            : { ok: true, json: async () => [] };
    };
    await vm.runInContext("loadTasks()", context);
    assert.equal(calls[0].url, "/api/tasks/refresh");
    assert.equal(calls[0].options.method, "POST");
    assert.equal(calls[1].url, "/api/tasks");
    assert.match(elements.get("status-message").textContent, /Remote unavailable/);
});

test("successful reload uses the refresh response without another Task request", async () => {
    const { context, calls } = application();
    context.fetch = async (url, options) => {
        calls.push({ url, options });
        return { ok: true, json: async () => [{ id: "refreshed-task", revision: "a".repeat(40) }] };
    };
    await vm.runInContext("loadTasks()", context);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/tasks/refresh");
    assert.equal(vm.runInContext("allTasks[0].id", context), "refreshed-task");
});

test("full update sends the form's revision, not local ordering metadata", async () => {
    const { context, calls } = application();
    let submit;
    context.fakeForm = {
        elements: { subject: { focus() {} } },
        requiresPicker: { getSelectedIds: () => [] },
        querySelector: () => ({ addEventListener() {}, hidden: true, disabled: false }),
        addEventListener: (name, handler) => { if (name === "submit") submit = handler; }
    };
    const values = { subject: "Updated", description: "**Markdown**", status: "FIXING", workStatus: "TODO", waitingFor: "NONE" };
    context.FormData = class { get(name) { return values[name] || ""; } };
    vm.runInContext(`createTaskForm = () => fakeForm; openTaskModal = () => {};
        showTaskEdit({id:"task-id",revision:"${"a".repeat(40)}",sortOrder:3});`, context);
    await submit({ preventDefault() {} });
    assert.equal(calls[0].options.method, "PUT");
    assert.equal(calls[0].options.headers["If-Match"], `"${"a".repeat(40)}"`);
    assert.equal(JSON.parse(calls[0].options.body).description, "**Markdown**");
    assert.equal("sortOrder" in JSON.parse(calls[0].options.body), false);
});

test("reorder calls only the local-order route and needs no Task revision", async () => {
    const { context, calls } = application();
    vm.runInContext(`allTasks = [{id:"a",sortOrder:1},{id:"b",sortOrder:2}];`, context);
    await vm.runInContext(`handleTaskDrop("b", "a", true)`, context);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/tasks/b/reorder");
    assert.equal(calls[0].options.headers["If-Match"], undefined);
});

test("meeting save remains revision-free and reloads affected Tasks", async () => {
    const { context, calls } = application();
    let submit;
    context.fakeForm = {
        elements: { serverKey: { focus() {} } },
        taskIdsPicker: { getSelectedIds: () => ["task-id"] },
        querySelector: () => ({ addEventListener() {}, hidden: true, disabled: false }),
        addEventListener: (name, handler) => { if (name === "submit") submit = handler; }
    };
    context.FormData = class { get(name) { return name === "serverKey" ? "server" : "2026-10-03T14:30"; } };
    context.fetch = async (url, options) => {
        calls.push({ url, options });
        return { ok: true, json: async () => url === "/api/meetings" ? { id: "meeting-id" } : [] };
    };
    vm.runInContext(`createMeetingForm = () => fakeForm; closeMeetingModal = () => {}; openMeetingModal();`, context);
    await submit({ preventDefault() {} });
    assert.deepEqual(calls.map((call) => call.url), ["/api/meetings", "/api/tasks/refresh"]);
    assert.deepEqual(JSON.parse(calls[0].options.body).taskIds, ["task-id"]);
    assert.equal(calls[0].options.headers["If-Match"], undefined);
});
