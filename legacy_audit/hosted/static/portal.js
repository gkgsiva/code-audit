/* legacy-audit hosted site: projects, status, logs and admin pages. Plain JavaScript, no build
   step, no network beyond this server. Each project opens in the project GUI under /p/<id>/. */
(function () {
  "use strict";

  // ---------------------------------------------------------------- helpers (as in the GUI)
  var screenEl = document.getElementById("screen");
  var state = { me: null, timer: null };

  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === "text") e.textContent = v;
        else if (k === "class") e.className = v;
        else if (k.slice(0, 2) === "on") e.addEventListener(k.slice(2), v);
        else if (v === true) e.setAttribute(k, "");
        else e.setAttribute(k, v);
      });
    }
    (children || []).forEach(function (c) {
      if (c === null || c === undefined) return;
      e.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
    });
    return e;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
  function fmt(n) { return typeof n === "number" ? n.toLocaleString("en-US") : (n === null || n === undefined ? "–" : String(n)); }
  function toast(message, isError) {
    var t = document.getElementById("toast");
    t.textContent = message;
    t.className = "toast" + (isError ? " error" : "");
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { t.hidden = true; }, isError ? 9000 : 4000);
  }
  function api(method, path, body) {
    var opts = { method: method, headers: {} };
    if (method !== "GET") opts.headers["X-Legacy-Audit"] = "1";
    if (body !== undefined) { opts.body = JSON.stringify(body); opts.headers["Content-Type"] = "application/json"; }
    return fetch(path, opts).then(function (r) {
      if (r.status === 401) { location.href = "/login?next=" + encodeURIComponent(location.pathname + location.hash); throw new Error("Log in first"); }
      return r.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        if (!r.ok) {
          var detail = data && data.detail ? data.detail : r.statusText;
          throw new Error(typeof detail === "string" ? detail : JSON.stringify(detail));
        }
        return data;
      });
    });
  }
  function fail(err) { toast(err.message || String(err), true); }
  function add(node, items) { items.forEach(function (i) { if (i) node.appendChild(i); }); }
  function heading(title, lead) { return [el("h1", { text: title }), lead ? el("p", { class: "lead", text: lead }) : null]; }
  function table(columns, rows) {
    var thead = el("tr", null, columns.map(function (c) { return el("th", { class: c.num ? "num" : null, text: c.label }); }));
    var tbody = el("tbody");
    rows.forEach(function (r) {
      var tr = el("tr");
      columns.forEach(function (c) {
        var v = c.value ? c.value(r) : r[c.key];
        var td = el("td", { class: c.num ? "num" : (c.code ? "code" : null) });
        if (v instanceof Node) td.appendChild(v); else td.textContent = c.num ? fmt(v) : (v === null || v === undefined ? "" : String(v));
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    return el("div", { class: "table-wrap" }, [el("table", { class: "data" }, [el("thead", null, [thead]), tbody])]);
  }
  function chip(value) { return el("span", { class: "chip " + String(value || "").toLowerCase().replace(/\s+/g, "-"), text: value }); }
  function tile(label, value) { return el("div", { class: "stat" }, [el("div", { class: "v", text: fmt(value) }), el("div", { class: "k", text: label })]); }
  function duration(sec) {
    if (sec === null || sec === undefined) return "";
    if (sec < 60) return "under a minute";
    var m = Math.round(sec / 60);
    return m < 60 ? "about " + m + " min" : "about " + Math.floor(m / 60) + " h " + (m % 60) + " min";
  }
  function bytes(n) {
    if (n === null || n === undefined) return "–";
    var u = ["B", "KB", "MB", "GB", "TB"], i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return n.toFixed(i ? 1 : 0) + " " + u[i];
  }
  function lines(text) { return text.split("\n").map(function (s) { return s.trim(); }).filter(Boolean); }
  function checkbox(label, checked, attrs) {
    var input = el("input", Object.assign({ type: "checkbox", checked: !!checked }, attrs || {}));
    return { input: input, node: el("label", null, [input, " " + label]) };
  }
  function field(form, label, input, hint) {
    var id = "f" + Math.random().toString(36).slice(2, 8);
    input.id = input.id || id;
    form.appendChild(el("label", { for: input.id, text: label }));
    form.appendChild(el("div", null, [input, hint ? el("p", { class: "hint", text: hint }) : null]));
    return input;
  }
  function textInput(value, attrs) { return el("input", Object.assign({ type: "text", value: value === null || value === undefined ? "" : value }, attrs || {})); }
  function select(options, value) {
    var s = el("select");
    options.forEach(function (o) {
      var pair = Array.isArray(o) ? o : [o, o];
      var opt = new Option(pair[1], pair[0]);
      if (pair[0] === value) opt.selected = true;
      s.appendChild(opt);
    });
    return s;
  }

  // dotted keys inside the audit.yaml defaults ("security.osv_scanner.offline")
  function getD(obj, key) {
    return key.split(".").reduce(function (o, k) { return o && typeof o === "object" && k in o ? o[k] : undefined; }, obj);
  }
  function setD(obj, key, value) {
    var parts = key.split("."), node = obj;
    for (var i = 0; i < parts.length - 1; i++) {
      if (!node[parts[i]] || typeof node[parts[i]] !== "object") node[parts[i]] = {};
      node = node[parts[i]];
    }
    if (value === undefined || value === null || value === "") delete node[parts[parts.length - 1]];
    else node[parts[parts.length - 1]] = value;
    prune(obj);
  }
  function prune(obj) {
    Object.keys(obj).forEach(function (k) {
      if (obj[k] && typeof obj[k] === "object" && !Array.isArray(obj[k])) {
        prune(obj[k]);
        if (!Object.keys(obj[k]).length) delete obj[k];
      }
    });
  }

  // ---------------------------------------------------------------- projects
  function screenProjects(node) {
    add(node, heading("Projects", "Each project is one application to audit. Open one to choose components, scan, and build reports."));
    node.appendChild(el("ol", { class: "steps" }, [
      el("li", { text: "Create a project and pick where the code is (a folder on the server or a Git address)." }),
      el("li", { text: "Open it and check the components (3. Components)." }),
      el("li", { text: "Run the scan (4. Run). Follow it here on the Status page." }),
      el("li", { text: "Build and download the reports (8. Reports)." })
    ]));
    var list = el("div");
    node.appendChild(el("h2", { text: "Your projects" }));
    node.appendChild(list);
    if (state.me.can_create) {
      node.appendChild(el("h2", { text: "Create a project" }));
      node.appendChild(createForm());
    } else {
      node.appendChild(el("p", { class: "hint", text: "Your role cannot create projects. Ask an admin to create one or add you to it." }));
    }
    api("GET", "/api/projects").then(function (projects) {
      if (!projects.length) {
        list.appendChild(el("div", { class: "empty", text: state.me.can_create ? "No project yet. Create the first one below." : "You are not on any project yet." }));
        return;
      }
      var access = el("div");
      list.appendChild(table([
        { label: "Project", value: function (p) { return el("a", { href: p.url, text: p.title }); } },
        { label: "Folder", value: function (p) { return el("span", { class: "code", text: p.id }); } },
        { key: "owner", label: "Owner" },
        { label: "Your role", value: function (p) { return chip(p.role); } },
        { key: "runs", label: "Runs", num: true },
        { label: "Last run", value: function (p) { return p.last_run || "none yet"; } },
        { label: "Now", value: function (p) {
          if (p.problem) return el("span", { class: "error-text", text: p.problem });
          if (p.archived) return chip("archived");
          return p.job && (p.job.state === "running" || p.job.state === "queued") ? el("a", { href: "#status", text: p.job.title + " (" + p.job.state + ")" }) : "";
        } },
        { label: "", value: function (p) {
          return el("span", { class: "inline" }, [
            el("a", { class: "btn small", href: p.url, text: "Open" }),
            p.role === "owner" || p.role === "admin" ? el("button", { class: "btn small", text: "Access and reports", onclick: function () { clear(access); access.appendChild(accessPanel(p)); access.scrollIntoView(); } }) : null
          ]);
        } }
      ], projects));
      list.appendChild(access);
    }).catch(fail);
  }

  function folderPicker(onPick) {
    var current = el("p", { class: "hint" });
    var box = el("div", { class: "folder-list" });
    var chosen = textInput("", { placeholder: "/sources/billing", "aria-label": "Folder holding the code" });
    function load(path) {
      api("GET", "/api/sources?path=" + encodeURIComponent(path || "")).then(function (d) {
        clear(box);
        current.textContent = d.path ? "In " + d.path : "Folders this site may scan:";
        if (d.parent !== null && d.path) box.appendChild(el("button", { type: "button", text: "⬑ up", onclick: function () { load(d.parent); } }));
        if (d.path) { chosen.value = d.path; onPick(d.path); }
        if (!d.folders.length) box.appendChild(el("p", { class: "hint", text: d.path ? "No sub-folders." : "No source folder is mounted. Ask the admin to mount the code under the sources folder." }));
        d.folders.forEach(function (f) { box.appendChild(el("button", { type: "button", text: "📁 " + f.name, onclick: function () { load(f.path); } })); });
      }).catch(fail);
    }
    chosen.addEventListener("change", function () { onPick(chosen.value); });
    load("");
    return el("div", null, [current, box, chosen, el("p", { class: "hint", text: "Click folders to go down; the folder shown in the box is the one scanned." })]);
  }

  function createForm() {
    var form = el("form", { class: "fields" });
    var title = field(form, "Name", textInput("", { required: true, placeholder: "e.g. Customer billing" }), "Shown in every report. The project folder name is made from it.");
    var id = field(form, "Folder name", textInput("", { placeholder: "made from the name" }), "Optional: letters, digits, dot, dash and underscore.");
    var desc = field(form, "Description", textInput("", { placeholder: "e.g. Struts 1 + Oracle billing web app" }));
    var kind = field(form, "Where is the code", select([["local", "A folder on this server"], ["git", "A Git repository"]], "local"));
    var localBox = el("div"), gitBox = el("div", { hidden: true });
    var picked = { path: "" };
    localBox.appendChild(folderPicker(function (p) { picked.path = p; }));
    var url = textInput("", { placeholder: "https://git.example.com/team/billing.git" });
    var ref = textInput("", { placeholder: "main" });
    var sub = textInput("", { placeholder: "billing-web, billing-batch" });
    var token = textInput("", { placeholder: "GIT_TOKEN" });
    var g = el("div", { class: "fields" });
    field(g, "Address", url);
    field(g, "Branch or tag", ref, "Empty: the default branch.");
    field(g, "Sub-folders", sub, "Optional, comma separated.");
    field(g, "Token variable", token, "Name of an environment variable on the server holding a read-only token. Never the token itself.");
    gitBox.appendChild(g);
    form.appendChild(el("span"));
    form.appendChild(el("div", null, [localBox, gitBox]));
    kind.addEventListener("change", function () { localBox.hidden = kind.value !== "local"; gitBox.hidden = kind.value !== "git"; });
    form.appendChild(el("span"));
    form.appendChild(el("div", null, [el("button", { type: "submit", class: "btn primary", text: "Create project" })]));
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var source = kind.value === "git"
        ? { type: "git", url: url.value.trim(), ref: ref.value.trim() || null, token_env: token.value.trim() || null, paths: sub.value.split(",").map(function (s) { return s.trim(); }).filter(Boolean) }
        : { type: "local", path: picked.path };
      api("POST", "/api/projects", { title: title.value, id: id.value.trim() || null, description: desc.value || null, source: source })
        .then(function (p) { toast("Project " + p.title + " created."); location.href = p.url + "#components"; })
        .catch(fail);
    });
    return form;
  }

  function accessPanel(p) {
    var panel = el("div", { class: "panel access" }, [el("h3", { text: "Access and reports: " + p.title })]);
    var members = Object.assign({}, p.members || {});
    var rows = el("div");
    var form = el("div", { class: "fields" });
    var titleIn = field(form, "Name", textInput(p.title));
    var owner = field(form, "Owner", textInput(p.owner || ""), "User name of the owner. The owner manages access.");
    panel.appendChild(form);
    function drawMembers() {
      clear(rows);
      var names = Object.keys(members);
      rows.appendChild(names.length ? table([
        { key: "name", label: "Member" },
        { label: "Role", value: function (r) { var s = select(["contributor", "viewer"], r.role); s.addEventListener("change", function () { members[r.name] = s.value; }); return s; } },
        { label: "", value: function (r) { return el("button", { class: "btn small danger", text: "Remove", onclick: function () { delete members[r.name]; drawMembers(); } }); } }
      ], names.map(function (n) { return { name: n, role: members[n] }; })) : el("p", { class: "hint", text: "No members yet: only the owner and admins see this project." }));
    }
    panel.appendChild(el("h4", { text: "Members" }));
    panel.appendChild(el("p", { class: "hint", text: "Contributors scan and build reports; viewers read results and download reports." }));
    panel.appendChild(rows);
    var who = el("input", { type: "text", placeholder: "user name", list: "user-names" });
    var role = select(["contributor", "viewer"], "contributor");
    var dl = el("datalist", { id: "user-names" });
    api("GET", "/api/users").then(function (names) { names.forEach(function (n) { dl.appendChild(new Option(n, n)); }); }).catch(function () { /* optional */ });
    panel.appendChild(el("div", { class: "row" }, [who, dl, role, el("button", { class: "btn", text: "Add member", onclick: function () { if (who.value.trim()) { members[who.value.trim()] = role.value; who.value = ""; drawMembers(); } } })]));
    drawMembers();

    panel.appendChild(el("h4", { text: "Hide reports on this project" }));
    panel.appendChild(el("p", { class: "hint", text: "Reports the admin switched off are never shown. You can hide more here." }));
    var hideBoxes = {}, secBoxes = {};
    var hide = el("div", { class: "checks" });
    var sec = el("div", { class: "checks" });
    panel.appendChild(hide);
    panel.appendChild(el("h4", { text: "Hide summary-report sections" }));
    panel.appendChild(sec);
    Object.keys(REPORT_LABELS).forEach(function (k) { var c = checkbox(REPORT_LABELS[k], (p.hidden_reports || []).indexOf(k) >= 0); hideBoxes[k] = c.input; hide.appendChild(c.node); });
    Object.keys(SECTION_LABELS).forEach(function (k) { var c = checkbox(SECTION_LABELS[k], (p.hidden_sections || []).indexOf(k) >= 0); secBoxes[k] = c.input; sec.appendChild(c.node); });
    var archived = checkbox("Archived (read-only for everyone but admins)", p.archived);
    panel.appendChild(el("div", { class: "row" }, [archived.node]));
    panel.appendChild(el("div", { class: "row" }, [el("button", { class: "btn primary", text: "Save", onclick: function () {
      var body = { title: titleIn.value, owner: owner.value.trim() || null, members: members, archived: archived.input.checked,
        hidden_reports: Object.keys(hideBoxes).filter(function (k) { return hideBoxes[k].checked; }),
        hidden_sections: Object.keys(secBoxes).filter(function (k) { return secBoxes[k].checked; }) };
      api("PUT", "/api/projects/" + encodeURIComponent(p.id), body).then(function () { toast("Saved."); route(); }).catch(fail);
    } })]));
    return panel;
  }
  var REPORT_LABELS = { summary: "Summary report", mgmt: "Management deck", pptx: "Management PowerPoint", functional: "Functional document", tech: "Technical report", pdf: "PDF copies", datapack: "Data pack" };
  var SECTION_LABELS = { project: "Project", data: "Database and queries", integrations: "Integrations", batch: "Batches", security: "Dependency vulnerabilities" };

  // ---------------------------------------------------------------- status
  function screenStatus(node) {
    add(node, heading("Status", "Scans, report builds and AI enrichment on your projects: what runs now, what waits for a free slot, and about how long is left. Refreshes every 3 seconds."));
    var host = el("div");
    node.appendChild(host);
    function draw() {
      api("GET", "/api/status").then(function (d) {
        clear(host);
        var active = d.jobs.filter(function (j) { return j.state === "running" || j.state === "queued"; });
        var recent = d.jobs.filter(function (j) { return !(j.state === "running" || j.state === "queued"); });
        host.appendChild(el("div", { class: "tiles" }, [tile("Running now", d.running), tile("Waiting", d.waiting), tile("Slots on this site", d.slots), tile("Finished (recent)", recent.length)]));
        host.appendChild(el("h2", { text: "Now" }));
        host.appendChild(active.length ? jobTable(active, true) : el("div", { class: "empty", text: "Nothing running. Start a scan from a project's Run page." }));
        host.appendChild(el("h2", { text: "Recently finished" }));
        host.appendChild(recent.length ? jobTable(recent.slice(0, 50), false) : el("p", { class: "muted", text: "Nothing yet since the server started." }));
      }).catch(fail);
    }
    draw();
    state.timer = setInterval(draw, 3000);
  }
  function jobTable(jobs, live) {
    var cols = [
      { label: "Project", value: function (j) { return el("a", { href: "p/" + encodeURIComponent(j.project) + "/#run", text: j.project }); } },
      { key: "title", label: "What" },
      { key: "user", label: "Started by" },
      { label: "State", value: function (j) { return chip(j.state === "queued" ? "queued, place " + j.queue_position : j.state); } }
    ];
    if (live) {
      cols.push({ label: "Progress", value: function (j) {
        var pct = j.percent !== null && j.percent !== undefined ? j.percent : j.stage_percent;
        return el("div", null, [
          el("div", { class: "bar", role: "progressbar", "aria-valuenow": pct === null ? null : Math.round(pct) }, [el("div", { style: "width:" + (pct || 0) + "%" })]),
          el("span", { class: "hint", text: (j.stage ? j.stage + (j.stage_total ? " " + fmt(j.stage_done) + "/" + fmt(j.stage_total) : "") : (j.state === "queued" ? "waiting" : "starting")) + (pct !== null && pct !== undefined ? ", " + Math.round(pct) + "%" : "") })
        ]);
      } });
      cols.push({ label: "Running for", value: function (j) { return duration(j.elapsed_seconds); } });
      cols.push({ label: "Time left", value: function (j) { return j.eta_seconds ? duration(j.eta_seconds) + " (" + j.eta_basis + ")" : (j.eta_basis || (j.state === "queued" ? "after the jobs ahead" : "estimating…")); } });
    } else {
      cols.push({ key: "finished_at", label: "Finished" });
      cols.push({ label: "Took", value: function (j) { return duration(j.elapsed_seconds); } });
      cols.push({ label: "Result", value: function (j) { return j.error ? el("span", { class: "error-text", text: j.error }) : (j.result && j.result.run_id ? "run " + j.result.run_id : ""); } });
    }
    return table(cols, jobs);
  }

  // ---------------------------------------------------------------- logs
  function screenLogs(node) {
    add(node, heading("Logs", "The log of every scan on your projects" + (state.me.role === "admin" ? ", and the server log." : ".") + " Pick one, filter it, or download it."));
    var params = new URLSearchParams(location.hash.split("?")[1] || "");
    var pick = el("select", { "aria-label": "Log file" });
    var level = select([["", "All lines"], ["warning", "Warnings and errors"], ["error", "Errors only"]], "");
    var q = textInput("", { placeholder: "search", "aria-label": "Search the log" });
    var tail = select([["200", "Last 200 lines"], ["500", "Last 500 lines"], ["2000", "Last 2000 lines"]], "500");
    var follow = checkbox("Follow (refresh every 3 s)", false);
    var info = el("p", { class: "hint" });
    var view = el("pre", { class: "logview", "aria-live": "off" });
    var download = el("a", { class: "btn", text: "Download", href: "#" });
    node.appendChild(el("div", { class: "controls row" }, [pick, level, q, tail, follow.node, download]));
    node.appendChild(info);
    node.appendChild(view);
    function show() {
      if (!pick.value) return;
      download.href = "/api/logs/download?id=" + encodeURIComponent(pick.value);
      api("GET", "/api/logs/view?id=" + encodeURIComponent(pick.value) + "&level=" + level.value + "&q=" + encodeURIComponent(q.value) + "&tail=" + tail.value).then(function (d) {
        clear(view);
        d.lines.forEach(function (ln) {
          var cls = / (ERROR|CRITICAL) /.test(ln) ? "lv-error" : / WARNING /.test(ln) ? "lv-warning" : null;
          view.appendChild(el("span", { class: cls, text: ln + "\n" }));
        });
        info.textContent = fmt(d.lines.length) + " of " + fmt(d.total) + " matching lines.";
        if (follow.input.checked) view.scrollTop = view.scrollHeight;
      }).catch(fail);
    }
    [pick, level, tail].forEach(function (c) { c.addEventListener("change", show); });
    q.addEventListener("input", function () { clearTimeout(q.t); q.t = setTimeout(show, 300); });
    follow.input.addEventListener("change", function () {
      clearInterval(state.timer);
      if (follow.input.checked) state.timer = setInterval(show, 3000);
    });
    api("GET", "/api/logs").then(function (files) {
      if (!files.length) { view.textContent = "No logs yet: a scan writes one."; return; }
      files.forEach(function (f) { pick.appendChild(new Option(f.label + " · " + bytes(f.size), f.id)); });
      if (params.get("id")) pick.value = params.get("id");
      show();
    }).catch(fail);
  }

  // ---------------------------------------------------------------- admin
  var ADMIN_TABS = [["dashboard", "Dashboard"], ["reports", "Reports"], ["paths", "Paths"], ["defaults", "Project defaults"], ["users", "Users and roles"], ["projects", "Projects"],
    ["ai", "AI"], ["scanners", "Scanners"], ["limits", "Limits"], ["activity", "Activity"], ["health", "Health"]];

  function screenAdmin(node) {
    if (state.me.role !== "admin") { node.appendChild(el("div", { class: "empty", text: "Admins only." })); return; }
    var tab = (location.hash.split("?")[1] || "").replace("tab=", "") || "dashboard";
    add(node, heading("Admin", "Settings for the whole site. They are saved in the audits folder (.legacy-audit/admin.yaml) and apply at once. Values set by the server's environment are shown locked."));
    node.appendChild(el("nav", { class: "tabs" }, ADMIN_TABS.map(function (t) {
      return el("a", { href: "#admin?tab=" + t[0], class: t[0] === tab ? "current" : null, text: t[1] });
    })));
    var host = el("div");
    node.appendChild(host);
    api("GET", "/api/admin").then(function (a) {
      state.admin = a;
      var fn = { dashboard: tabDashboard, reports: tabReports, paths: tabPaths, defaults: tabDefaults, users: tabUsers, projects: tabAllProjects, ai: tabAi,
        scanners: tabScanners, limits: tabLimits, activity: tabActivity, health: tabHealth }[tab] || tabReports;
      fn(host, a, JSON.parse(JSON.stringify(a.settings)));
    }).catch(fail);
  }
  function saveAll(s, message) {
    return api("PUT", "/api/admin", s).then(function (a) { state.admin = a; toast(message || "Saved."); route(); }).catch(fail);
  }
  function saveButton(s, before) {
    return el("div", { class: "row" }, [el("button", { class: "btn primary", text: "Save", onclick: function () { if (before) before(); saveAll(s); } })]);
  }
  function lockBox(s, key) {
    var c = checkbox("lock", s.locked.indexOf(key) >= 0, { title: "Projects cannot change " + key });
    c.input.addEventListener("change", function () {
      s.locked = s.locked.filter(function (k) { return k !== key; });
      if (c.input.checked) s.locked.push(key);
    });
    c.node.className = "lock";
    return c.node;
  }
  function envNote(a, key) { return key in a.env_locked ? el("span", { class: "locked-note", text: " set by the server environment" }) : null; }

  function tabReports(host, a, s) {
    host.appendChild(el("h2", { text: "Reports people can build and see" }));
    var boxes = el("div", { class: "checks" });
    Object.keys(a.reports).forEach(function (k) {
      var c = checkbox(a.reports[k], s.reports.enabled[k] !== false);
      c.input.addEventListener("change", function () { s.reports.enabled[k] = c.input.checked; });
      boxes.appendChild(c.node);
    });
    host.appendChild(boxes);
    host.appendChild(el("p", { class: "hint", text: "A report switched off disappears from every project: its build button, its link and its files." }));
    host.appendChild(el("h2", { text: "Sections of the summary report" }));
    var secs = el("div", { class: "checks" });
    Object.keys(a.summary_sections).forEach(function (k) {
      var c = checkbox(a.summary_sections[k], s.reports.summary_sections[k] !== false);
      c.input.addEventListener("change", function () { s.reports.summary_sections[k] = c.input.checked; });
      secs.appendChild(c.node);
    });
    host.appendChild(secs);
    host.appendChild(el("p", { class: "hint", text: "Applies the next time a summary report is built." }));
    var own = checkbox("Project owners may hide more reports and sections on their project (they can never show one switched off here)", s.reports.owners_may_hide);
    own.input.addEventListener("change", function () { s.reports.owners_may_hide = own.input.checked; });
    host.appendChild(el("div", { class: "row" }, [own.node]));
    host.appendChild(saveButton(s));
  }

  function tabPaths(host, a, s) {
    host.appendChild(el("h2", { text: "Folders" }));
    host.appendChild(table([{ key: "k", label: "Folder" }, { label: "Path", value: function (r) { return el("span", { class: "code", text: r.v }); } }, { key: "h", label: "Set by" }], [
      { k: "Audits folder (every project lives here)", v: a.host.audits_root, h: "LEGACY_AUDIT_AUDITS_ROOT" },
      { k: "Sources folder (code to scan)", v: a.host.sources_roots.join(", "), h: "LEGACY_AUDIT_SOURCES_ROOT" },
      { k: "Site settings and logs", v: a.host.admin_dir, h: "inside the audits folder" },
      { k: "Git clone cache (in use)", v: a.host.git_cache, h: "below, or the default" }
    ]));
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    var extra = field(form, "More source folders", el("textarea", { rows: 3 }), "One per line. Projects may scan folders under these too (for example a second mounted disk).");
    extra.value = (s.paths.extra_source_roots || []).join("\n");
    var git = field(form, "Git clone cache", textInput(s.paths.git_cache_dir, { placeholder: a.host.admin_dir + "/git" }));
    var tpl = field(form, "PowerPoint templates folder", textInput(s.paths.templates_dir, { disabled: "paths.templates_dir" in a.env_locked, placeholder: "built-in corporate templates" }), "Folder holding the corporate .potx files.");
    var chrome = field(form, "Chrome or Edge for PDFs", textInput(getD(s.defaults, "pdf.browser_path")), "Empty: found on the server's PATH (the Docker image has Chromium).");
    var osv = field(form, "OSV-Scanner program", textInput(getD(s.defaults, "security.osv_scanner.path")), "Empty: found on PATH.");
    var osvDb = field(form, "OSV database folder", textInput(getD(s.defaults, "security.osv_scanner.data_dir")), "For offline scans.");
    var dc = field(form, "Dependency-Check script", textInput(getD(s.defaults, "security.dependency_check.path")), "Empty: found on PATH.");
    var dcDb = field(form, "Dependency-Check NVD folder", textInput(getD(s.defaults, "security.dependency_check.data_dir")));
    host.appendChild(el("h2", { text: "Paths you can change" }));
    host.appendChild(form);
    host.appendChild(saveButton(s, function () {
      s.paths.extra_source_roots = lines(extra.value);
      s.paths.git_cache_dir = git.value.trim() || null;
      if (!("paths.templates_dir" in a.env_locked)) s.paths.templates_dir = tpl.value.trim() || null;
      setD(s.defaults, "pdf.browser_path", chrome.value.trim());
      setD(s.defaults, "security.osv_scanner.path", osv.value.trim());
      setD(s.defaults, "security.osv_scanner.data_dir", osvDb.value.trim());
      setD(s.defaults, "security.dependency_check.path", dc.value.trim());
      setD(s.defaults, "security.dependency_check.data_dir", dcDb.value.trim());
    }));
  }

  function tabDefaults(host, a, s) {
    host.appendChild(el("p", { class: "lead", text: "Common audit.yaml settings. Every project inherits them on each scan; a project's own audit.yaml can change a setting unless it is locked here." }));
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    function row(label, key, input, hint) {
      field(form, label, input, hint);
      form.lastChild.insertBefore(lockBox(s, key), form.lastChild.firstChild.nextSibling);
      return input;
    }
    var profile = row("Migration target", "target.profile", select([["", "(built-in: spring-boot-angular)"], ["spring-boot-angular", "Spring Boot + Angular (rewrite)"], ["wildfly-gke", "WildFly on GKE (lift as is)"], ["none", "None (audit only)"]], getD(s.defaults, "target.profile") || ""));
    var excl = row("Exclude patterns", "exclude", el("textarea", { rows: 4 }), "One per line, .gitignore style, e.g. **/test/**");
    excl.value = (getD(s.defaults, "exclude") || []).join("\n");
    var dialect = row("SQL dialect in code", "sql.dialect", select([["", "(built-in: oracle)"], "oracle", "db2", "sqlserver", "sybase", "mysql", "postgresql", "generic"], getD(s.defaults, "sql.dialect") || ""));
    var grouping = row("Component grouping", "inventory.component_grouping", select([["", "(built-in: auto)"], ["auto", "auto (per deployable)"], ["module", "module (one per folder)"]], getD(s.defaults, "inventory.component_grouping") || ""));
    var tpl = row("PowerPoint template", "branding.pptx_template", textInput(getD(s.defaults, "branding.pptx_template"), { placeholder: "blue" }), "blue, orange or purple (the corporate templates).");
    var effort = row("Effort estimate", "effort.enabled", select([["", "(built-in: on)"], ["true", "on"], ["false", "off"]], getD(s.defaults, "effort.enabled") === undefined ? "" : String(getD(s.defaults, "effort.enabled"))));
    var pdf = row("PDF copies with 'Build all'", "pdf.enabled", select([["", "(built-in)"], ["true", "on"], ["false", "off"]], getD(s.defaults, "pdf.enabled") === undefined ? "" : String(getD(s.defaults, "pdf.enabled"))));
    var rulesOff = row("Rules switched off", "rules.disabled", el("textarea", { rows: 2 }), "Rule ids, one per line.");
    rulesOff.value = (getD(s.defaults, "rules.disabled") || []).join("\n");
    host.appendChild(form);
    host.appendChild(saveButton(s, function () {
      setD(s.defaults, "target.profile", profile.value);
      setD(s.defaults, "exclude", lines(excl.value).length ? lines(excl.value) : null);
      setD(s.defaults, "sql.dialect", dialect.value);
      setD(s.defaults, "inventory.component_grouping", grouping.value);
      setD(s.defaults, "branding.pptx_template", tpl.value.trim());
      setD(s.defaults, "effort.enabled", effort.value === "" ? null : effort.value === "true");
      setD(s.defaults, "pdf.enabled", pdf.value === "" ? null : pdf.value === "true");
      setD(s.defaults, "rules.disabled", lines(rulesOff.value).length ? lines(rulesOff.value) : null);
    }));
    host.appendChild(el("h2", { text: "Advanced: every default as YAML" }));
    host.appendChild(el("p", { class: "hint", text: "Any audit.yaml section except application, source, components, inputs_dir and output_dir (those are per project). Checked before it is saved." }));
    var raw = el("textarea", { class: "config", spellcheck: "false" });
    raw.value = a.defaults_yaml;
    host.appendChild(raw);
    var locked = el("textarea", { rows: 3, spellcheck: "false" });
    locked.value = s.locked.join("\n");
    host.appendChild(el("p", { class: "hint", text: "Locked keys, one per line (dotted, e.g. security.osv_scanner):" }));
    host.appendChild(locked);
    host.appendChild(el("div", { class: "row" }, [el("button", { class: "btn", text: "Save YAML", onclick: function () {
      api("PUT", "/api/admin/defaults", { text: raw.value }).then(function (fresh) {
        fresh.settings.locked = lines(locked.value);
        return saveAll(fresh.settings, "Defaults saved.");
      }).catch(fail);
    } })]));
  }

  function tabUsers(host, a, s) {
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    var open = checkbox("Anyone who logs in with a new name becomes a user", s.users.open_signup);
    field(form, "Sign-up", open.node);
    var role = field(form, "Role of new users", select(["owner", "contributor", "viewer"], s.users.signup_role));
    var create = field(form, "Who can create projects", select([["owners", "Owners and admins"], ["admins", "Admins only"]], s.users.who_can_create));
    host.appendChild(el("h2", { text: "Sign-up and roles" }));
    host.appendChild(el("p", { class: "hint", text: "Login: " + a.host.auth + (a.host.auth === "dummy" ? " (test login, name only). Okta replaces it later with LEGACY_AUDIT_AUTH=okta." : ".") + " Always admin (LEGACY_AUDIT_ADMINS): " + a.host.admins.join(", ") + "." }));
    host.appendChild(form);
    host.appendChild(saveButton(s, function () { s.users.open_signup = open.input.checked; s.users.signup_role = role.value; s.users.who_can_create = create.value; }));
    host.appendChild(table([{ key: "r", label: "Role" }, { key: "d", label: "Can do" }], [
      { r: "admin", d: "Everything, the admin page and every project" },
      { r: "owner", d: "Create projects; scan, enrich, build reports, edit settings and manage members on their projects" },
      { r: "contributor", d: "Scan and build reports on projects they are added to" },
      { r: "viewer", d: "Read results and download reports on projects they are added to" }]));
    host.appendChild(el("h2", { text: "Users" }));
    var list = el("div");
    host.appendChild(list);
    var name = textInput("", { placeholder: "user name" });
    var newRole = select(a.roles, "owner");
    host.appendChild(el("div", { class: "row" }, [name, newRole, el("button", { class: "btn", text: "Add user", onclick: function () {
      api("PUT", "/api/admin/users", { name: name.value.trim(), role: newRole.value, disabled: false }).then(function () { toast("User added."); route(); }).catch(fail);
    } })]));
    api("GET", "/api/admin/users").then(function (users) {
      list.appendChild(table([
        { key: "name", label: "User" },
        { label: "Role", value: function (u) {
          if (u.fixed) return chip("admin");
          var sel = select(a.roles, u.role);
          sel.addEventListener("change", function () { api("PUT", "/api/admin/users", { name: u.name, role: sel.value, disabled: u.disabled }).then(function () { toast("Saved."); }).catch(fail); });
          return sel;
        } },
        { label: "Disabled", value: function (u) {
          if (u.fixed) return "";
          var c = el("input", { type: "checkbox", checked: u.disabled, "aria-label": "Disable " + u.name });
          c.addEventListener("change", function () { api("PUT", "/api/admin/users", { name: u.name, role: u.role, disabled: c.checked }).then(function () { toast("Saved."); }).catch(fail); });
          return c;
        } },
        { key: "projects", label: "Owns", num: true },
        { key: "last_login", label: "Last login" },
        { label: "", value: function (u) { return u.fixed ? "" : el("button", { class: "btn small danger", text: "Remove", onclick: function () { api("DELETE", "/api/admin/users/" + encodeURIComponent(u.name)).then(function () { toast("Removed."); route(); }).catch(fail); } }); } }
      ], users));
    }).catch(fail);
  }

  function tabAllProjects(host) {
    host.appendChild(el("p", { class: "lead", text: "Every project folder in the audits folder. A folder copied in by hand has no owner until you set one. Deleting removes the folder with all its runs and cannot be undone." }));
    var access = el("div");
    api("GET", "/api/admin/projects").then(function (projects) {
      host.appendChild(projects.length ? table([
        { label: "Project", value: function (p) { return el("a", { href: p.url, text: p.title }); } },
        { label: "Folder", value: function (p) { return el("span", { class: "code", text: p.id }); } },
        { label: "Owner", value: function (p) { return p.owner || chip("no owner"); } },
        { label: "Members", value: function (p) { return Object.keys(p.members || {}).length; } },
        { key: "runs", label: "Runs", num: true },
        { label: "State", value: function (p) { return p.archived ? chip("archived") : (p.problem ? el("span", { class: "error-text", text: p.problem }) : "active"); } },
        { label: "", value: function (p) {
          var confirmIn = textInput("", { placeholder: "type " + p.id + " to delete", "aria-label": "Confirm delete", size: 18 });
          return el("span", { class: "inline" }, [
            el("button", { class: "btn small", text: "Access", onclick: function () { clear(access); access.appendChild(accessPanel(p)); access.scrollIntoView(); } }),
            confirmIn,
            el("button", { class: "btn small danger", text: "Delete", onclick: function () {
              api("POST", "/api/projects/" + encodeURIComponent(p.id) + "/delete", { confirm: confirmIn.value.trim() }).then(function () { toast("Deleted " + p.id + "."); route(); }).catch(fail);
            } })
          ]);
        } }
      ], projects) : el("div", { class: "empty", text: "No project yet." }));
      host.appendChild(access);
    }).catch(fail);
  }

  function tabAi(host, a, s) {
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    var allowed = checkbox("Allow 'Run AI enrichment' on this site", s.ai.allowed);
    field(form, "Enrichment", allowed.node, "Off: the AI enrichment buttons are hidden and refused. Scans and reports never call a model.");
    function row(label, key, input, hint) { field(form, label, input, hint); form.lastChild.insertBefore(lockBox(s, key), form.lastChild.firstChild.nextSibling); return input; }
    var provider = row("Provider", "ai.provider", select([["", "(built-in: copilot, VS Code only)"], ["openai", "OpenAI-compatible endpoint"], ["azure-openai", "Azure OpenAI"], ["anthropic", "Anthropic"], ["ollama", "Ollama (local)"], ["copilot", "Copilot (VS Code only)"]], getD(s.defaults, "ai.provider") || ""));
    var model = row("Model", "ai.model", textInput(getD(s.defaults, "ai.model")), "Model name, or the deployment name for Azure OpenAI.");
    var base = row("Endpoint", "ai.base_url", textInput(getD(s.defaults, "ai.base_url")), "Empty: the provider's default.");
    var key = row("API key variable", "ai.api_key_env", textInput(getD(s.defaults, "ai.api_key_env"), { placeholder: "OPENAI_API_KEY" }), "The NAME of the environment variable on the server holding the key (set it in docker-compose.yml). Never the key.");
    var ca = row("Corporate CA file", "ai.ca_bundle", textInput(getD(s.defaults, "ai.ca_bundle")), "PEM file inside the container, if your proxy needs one.");
    host.appendChild(form);
    host.appendChild(saveButton(s, function () {
      s.ai.allowed = allowed.input.checked;
      setD(s.defaults, "ai.provider", provider.value);
      setD(s.defaults, "ai.model", model.value.trim());
      setD(s.defaults, "ai.base_url", base.value.trim());
      setD(s.defaults, "ai.api_key_env", key.value.trim());
      setD(s.defaults, "ai.ca_bundle", ca.value.trim());
    }));
  }

  function tabScanners(host, a, s) {
    host.appendChild(el("p", { class: "lead", text: "Vulnerability scanners run during each scan when found. Lock a scanner's settings to make every project use them." }));
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    function tri(key, def) { var v = getD(s.defaults, key); return select([["", "(built-in: " + def + ")"], ["true", "yes"], ["false", "no"]], v === undefined ? "" : String(v)); }
    function row(label, key, input, hint, lockKey) { field(form, label, input, hint); if (lockKey) form.lastChild.insertBefore(lockBox(s, lockKey), form.lastChild.firstChild.nextSibling); return input; }
    var osvOn = row("OSV-Scanner", "security.osv_scanner.enabled", tri("security.osv_scanner.enabled", "yes"), null, "security.osv_scanner");
    var osvOff = row("OSV-Scanner offline", "", tri("security.osv_scanner.offline", "no, asks osv.dev"), "No: the server needs internet access to osv.dev.");
    var osvT = row("OSV-Scanner timeout (min)", "", el("input", { type: "number", min: 1, value: getD(s.defaults, "security.osv_scanner.timeout_minutes") || "" }));
    var dcOn = row("Dependency-Check", "", tri("security.dependency_check.enabled", "yes"), null, "security.dependency_check");
    var dcOff = row("Dependency-Check offline", "", tri("security.dependency_check.offline", "yes"));
    var mirror = row("NVD mirror address", "", textInput(getD(s.defaults, "security.dependency_check.nvd_mirror_url")));
    var dcT = row("Dependency-Check timeout (min)", "", el("input", { type: "number", min: 1, value: getD(s.defaults, "security.dependency_check.timeout_minutes") || "" }));
    var jdk = row("JDK tools (jdeps)", "", tri("security.jdk_tools.enabled", "no"), "Slow; checks jars for Java 25 internal-API use.", "security.jdk_tools");
    host.appendChild(form);
    host.appendChild(el("p", { class: "hint", text: "Program and database folders are on the Paths tab. The Health tab shows whether each scanner was found." }));
    host.appendChild(saveButton(s, function () {
      function b(sel) { return sel.value === "" ? null : sel.value === "true"; }
      function n(inp) { return inp.value ? Number(inp.value) : null; }
      setD(s.defaults, "security.osv_scanner.enabled", b(osvOn));
      setD(s.defaults, "security.osv_scanner.offline", b(osvOff));
      setD(s.defaults, "security.osv_scanner.timeout_minutes", n(osvT));
      setD(s.defaults, "security.dependency_check.enabled", b(dcOn));
      setD(s.defaults, "security.dependency_check.offline", b(dcOff));
      setD(s.defaults, "security.dependency_check.nvd_mirror_url", mirror.value.trim());
      setD(s.defaults, "security.dependency_check.timeout_minutes", n(dcT));
      setD(s.defaults, "security.jdk_tools.enabled", b(jdk));
    }));
  }

  function tabLimits(host, a, s) {
    var form = el("form", { class: "fields", onsubmit: function (e) { e.preventDefault(); } });
    function num(label, key, value, hint, min, max) {
      var input = el("input", { type: "number", min: min, max: max, value: value, disabled: ("limits." + key) in a.env_locked });
      field(form, label, input, hint);
      var note = envNote(a, "limits." + key);
      if (note) form.lastChild.appendChild(note);
      return input;
    }
    var scans = num("Jobs at once", "max_scans", s.limits.max_scans, "Scans, report builds and enrichment running at the same time across all projects. Others wait in line (see Status).", 1, 32);
    var keep = num("Runs kept per project", "keep_runs", s.limits.keep_runs, "Older runs are deleted after each scan; the latest is always kept.", 1, 500);
    var timeout = num("Scan time limit (minutes)", "scan_timeout_minutes", s.limits.scan_timeout_minutes, "A scan running longer is stopped.", 5, 10000);
    var keepClones = num("Keep Git clones (days)", "clone_keep_days", s.limits.clone_keep_days, "A clone not scanned for this many days is deleted (checked every hour and after each scan). The next scan clones it again.", 1, 3650);
    var minFree = num("Keep this much disk free (GB)", "min_free_gb", s.limits.min_free_gb, "When the disk has less free space, the least recently used clones are deleted first. 0 turns this off.", 0, 100000);
    host.appendChild(form);
    host.appendChild(saveButton(s, function () {
      s.limits.max_scans = Number(scans.value);
      s.limits.keep_runs = Number(keep.value);
      s.limits.scan_timeout_minutes = Number(timeout.value);
      s.limits.clone_keep_days = Number(keepClones.value);
      s.limits.min_free_gb = Number(minFree.value);
    }));
  }

  function tabActivity(host) {
    var proj = textInput("", { placeholder: "project folder (optional)" });
    var list = el("div");
    function load() {
      api("GET", "/api/admin/activity?limit=500&project=" + encodeURIComponent(proj.value.trim())).then(function (rows) {
        clear(list);
        list.appendChild(rows.length ? table([
          { key: "time", label: "Time (UTC)" }, { key: "user", label: "User" }, { key: "action", label: "Action" }, { key: "project", label: "Project" },
          { label: "Detail", value: function (e) { return Object.keys(e).filter(function (k) { return ["time", "user", "action", "project"].indexOf(k) < 0; }).map(function (k) { return k + ": " + (typeof e[k] === "object" ? JSON.stringify(e[k]) : e[k]); }).join("; "); } }
        ], rows) : el("p", { class: "muted", text: "Nothing yet." }));
      }).catch(fail);
    }
    host.appendChild(el("div", { class: "row" }, [proj, el("button", { class: "btn", text: "Filter", onclick: load }), el("a", { class: "btn", href: "/api/admin/activity.csv", text: "Download CSV" })]));
    host.appendChild(list);
    load();
  }

  function tabHealth(host) {
    api("GET", "/api/admin/health").then(function (h) {
      host.appendChild(el("div", { class: "tiles" }, [tile("Version", h.version), tile("Disk free", bytes(h.disk.free)), tile("Disk used", bytes(h.disk.used)),
        tile("Jobs running", h.queue.running), tile("Jobs waiting", h.queue.waiting)]));
      host.appendChild(el("h2", { text: "Tools on the server" }));
      host.appendChild(table([{ key: "name", label: "Check" }, { label: "Status", value: function (c) { return chip(c.status); } }, { key: "detail", label: "Detail" }, { key: "needed_for", label: "Needed for" }], h.checks));
      host.appendChild(el("h2", { text: "Space per project" }));
      host.appendChild(table([{ key: "project", label: "Project" }, { label: "Size", value: function (p) { return bytes(p.bytes); } }], h.projects));
      var clonesBox = el("div");
      host.appendChild(el("h2", { text: "Git clones" }));
      host.appendChild(clonesBox);
      loadClones(clonesBox);
    }).catch(fail);
  }
  function loadClones(box) {
    api("GET", "/api/admin/clones").then(function (c) {
      clear(box);
      box.appendChild(el("p", { class: "hint", text: "Projects with a Git source scan a copy (clone) kept in " + c.cache + ". Each scan updates it to the latest commit of its branch. A clone not used for " + c.keep_days + " days is deleted, and so are the oldest when less than " + c.min_free_gb + " GB is free (change both on Limits). Deleting a project deletes the clones only it used. A deleted clone is simply cloned again by the next scan." }));
      box.appendChild(el("div", { class: "tiles" }, [tile("Clones", c.clones.length), tile("Space used", bytes(c.total)), tile("Disk free", bytes(c.free))]));
      box.appendChild(el("div", { class: "row" }, [el("button", { class: "btn", text: "Clean up now", onclick: function () {
        api("POST", "/api/admin/clones/prune").then(function (r) { toast(r.removed.length ? "Deleted " + r.removed.length + " clone(s)." : "Nothing to clean up."); loadClones(box); }).catch(fail);
      } })]));
      if (!c.clones.length) { box.appendChild(el("p", { class: "muted", text: "No clones yet." })); return; }
      box.appendChild(table([
        { key: "url", label: "Repository" }, { label: "Branch", value: function (r) { return r.ref || "default"; } }, { key: "commit", label: "Commit", code: true },
        { label: "Projects", value: function (r) { return r.projects.join(", ") || "none"; } },
        { key: "last_used", label: "Last used (UTC)" }, { label: "Size", value: function (r) { return bytes(r.size); } },
        { label: "", value: function (r) {
          return el("button", { class: "btn small danger", text: "Delete", onclick: function () {
            api("POST", "/api/admin/clones/delete", { key: r.key }).then(function () { toast("Deleted the clone of " + (r.url || r.key) + "."); loadClones(box); }).catch(fail);
          } });
        } }
      ], c.clones));
    }).catch(fail);
  }

  // ---------------------------------------------------------------- admin dashboard
  // Charts use the vendored Chart.js and the report's colour tokens (see report.js drawCharts):
  // one accent series, status colours where a bar is a job's outcome, a legend when there are
  // two or more series, values in the tooltip and in the tables below each chart.
  function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function chartBox(title, note) {
    var canvas = el("canvas", { role: "img", "aria-label": title });
    var tableBox = el("details", { class: "chart-table" }, [el("summary", { text: "Show as a table" })]);
    var panel = el("section", { class: "panel" }, [el("h3", { text: title }), note ? el("p", { class: "hint", text: note }) : null,
      el("div", { class: "chart-box" }, [canvas]), tableBox]);
    return { panel: panel, canvas: canvas, table: tableBox, chart: null };
  }
  function drawChart(box, cfg, columns, rows) {
    while (box.table.childNodes.length > 1) box.table.removeChild(box.table.lastChild);
    box.table.appendChild(table(columns, rows));
    if (!window.Chart) { box.canvas.replaceWith(el("p", { class: "muted", text: "Charts could not be loaded; see the table." })); return; }
    if (box.chart) {
      box.chart.data = cfg.data;
      box.chart.update("none");
      return;
    }
    var ink = cssVar("--text-2"), grid = cssVar("--grid"), axis = cssVar("--axis");
    Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
    Chart.defaults.color = ink;
    cfg.options = Object.assign({ maintainAspectRatio: false, animation: false, interaction: { mode: "index", intersect: false } }, cfg.options || {});
    var scales = cfg.options.scales || {};
    Object.keys(scales).forEach(function (k) {
      var s = scales[k];
      s.border = { color: axis };
      if (s.grid !== false) s.grid = { color: grid }; else s.grid = { display: false };
    });
    box.chart = new Chart(box.canvas, cfg);
  }
  function shortDay(d) { var p = d.split("-"); return p[2] + "/" + p[1]; }
  function clock(iso) { return iso ? iso.slice(11, 16) : ""; }
  function short(sec) {
    if (sec === null || sec === undefined) return "–";
    if (sec < 90) return Math.round(sec) + " s";
    var m = Math.round(sec / 60);
    return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + (m % 60) + " min";
  }
  function mins(sec) { return sec === null || sec === undefined ? null : Math.round(sec / 6) / 10; }
  function download(name, text) {
    var a = el("a", { href: URL.createObjectURL(new Blob([text], { type: "text/csv" })), download: name });
    document.body.appendChild(a); a.click(); a.remove();
  }

  function tabDashboard(host) {
    var days = el("select", null, [["7", "Last 7 days"], ["30", "Last 30 days"], ["90", "Last 90 days"]].map(function (o) {
      return el("option", { value: o[0], text: o[1], selected: o[0] === "30" });
    }));
    var csv = el("button", { class: "btn", text: "Download CSV" });
    host.appendChild(el("div", { class: "row" }, [days, csv, el("span", { class: "hint", text: "Usage refreshes every minute, the server every 10 seconds. Times in UTC." })]));

    var usageTiles = el("div", { class: "tiles" });
    var serverTiles = el("div", { class: "tiles" });
    var scans = chartBox("Scans per day", "Finished, failed and stopped scans.");
    var people = chartBox("People per day", "Logins, and the people who did anything on the site.");
    var work = chartBox("Reports and AI enrichment per day");
    var busiest = chartBox("Busiest projects", "Scans in the period.");
    var memChart = chartBox("Memory, last hour", "Used by the container, against its limit.");
    var cpuChart = chartBox("CPU, last hour", "Share of the CPUs the container may use.");
    var nowBox = el("div"), failBox = el("div"), usersBox = el("div"), reportsBox = el("div");
    add(host, [el("h2", { text: "Usage" }), usageTiles,
      el("div", { class: "grid-2" }, [scans.panel, people.panel, work.panel, busiest.panel]),
      el("div", { class: "grid-2" }, [el("div", null, [el("h3", { text: "Reports built" }), reportsBox]), el("div", null, [el("h3", { text: "Most active people" }), usersBox])]),
      el("h3", { text: "Recent failures" }), failBox,
      el("h2", { text: "Server" }), serverTiles,
      el("div", { class: "grid-2" }, [memChart.panel, cpuChart.panel]),
      el("h3", { text: "Running and waiting now" }), nowBox]);

    var last = null;
    function loadUsage() {
      api("GET", "/api/admin/dashboard/usage?days=" + days.value).then(function (u) {
        last = u;
        var t = u.totals;
        clear(usageTiles);
        add(usageTiles, [tile("People with access", t.users), tile("Active people", t.active_users), tile("Projects", t.projects),
          tile("Logins", t.logins), tile("Scans", t.scans), tile("Scans that finished", t.success_percent === null ? "–" : t.success_percent + "%"),
          tile("Average scan", short(t.scan_seconds_avg)), tile("Longest scan", short(t.scan_seconds_max)),
          tile("Average wait in line", short(t.wait_seconds_avg)),
          tile("Reports built", t.reports), tile("AI enrichment runs", t.enrich), tile("Archived projects", t.archived_projects)]);
        var labels = u.series.map(function (r) { return shortDay(r.day); });
        var dayCol = { key: "day", label: "Day" };
        drawChart(scans, { type: "bar", data: { labels: labels, datasets: [
          { label: "Finished", data: u.series.map(function (r) { return r.scans_done; }), backgroundColor: cssVar("--good") },
          { label: "Failed", data: u.series.map(function (r) { return r.scans_failed; }), backgroundColor: cssVar("--critical") },
          { label: "Stopped", data: u.series.map(function (r) { return r.scans_cancelled; }), backgroundColor: cssVar("--info") }
        ].map(function (d) { return Object.assign(d, { borderColor: cssVar("--surface"), borderWidth: { top: 2 }, borderSkipped: false, maxBarThickness: 28 }); }) },
          options: { plugins: { legend: { position: "top", align: "start" } },
            scales: { x: { stacked: true, grid: false }, y: { stacked: true, beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: "scans" } } } } },
          [dayCol, { key: "scans_done", label: "Finished", num: true }, { key: "scans_failed", label: "Failed", num: true }, { key: "scans_cancelled", label: "Stopped", num: true }], u.series);
        function line(label, key, color) {
          return { label: label, data: u.series.map(function (r) { return r[key]; }), borderColor: cssVar(color), backgroundColor: cssVar(color),
            borderWidth: 2, pointRadius: u.days > 31 ? 0 : 3, pointHoverRadius: 5, tension: 0.25 };
        }
        drawChart(people, { type: "line", data: { labels: labels, datasets: [line("Logins", "logins", "--series-1"), line("Active people", "active_users", "--series-2")] },
          options: { plugins: { legend: { position: "top", align: "start" } }, scales: { x: { grid: false }, y: { beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: "people / logins" } } } } },
          [dayCol, { key: "logins", label: "Logins", num: true }, { key: "active_users", label: "Active people", num: true }], u.series);
        drawChart(work, { type: "bar", data: { labels: labels, datasets: [
          { label: "Reports built", data: u.series.map(function (r) { return r.reports; }), backgroundColor: cssVar("--series-1") },
          { label: "AI enrichment", data: u.series.map(function (r) { return r.enrich; }), backgroundColor: cssVar("--series-2") }
        ].map(function (d) { return Object.assign(d, { borderColor: cssVar("--surface"), borderWidth: { top: 2 }, borderSkipped: false, maxBarThickness: 28 }); }) },
          options: { plugins: { legend: { position: "top", align: "start" } },
            scales: { x: { stacked: true, grid: false }, y: { stacked: true, beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: "jobs" } } } } },
          [dayCol, { key: "reports", label: "Reports built", num: true }, { key: "enrich", label: "AI enrichment", num: true }], u.series);
        drawChart(busiest, { type: "bar", data: { labels: u.top_projects.map(function (p) { return p.name; }), datasets: [
          { label: "Scans", data: u.top_projects.map(function (p) { return p.scans; }), backgroundColor: cssVar("--accent"), borderRadius: 4, borderSkipped: "start", maxBarThickness: 22 }] },
          options: { indexAxis: "y", interaction: { mode: "nearest", intersect: true }, plugins: { legend: { display: false } },
            scales: { x: { beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: "scans" } }, y: { grid: false } } } },
          [{ key: "name", label: "Project" }, { key: "scans", label: "Scans", num: true }, { label: "Scan time (min)", num: true, value: function (p) { return mins(p.scan_seconds); } }], u.top_projects);
        clear(reportsBox).appendChild(u.reports_by_type.length ? table([{ key: "name", label: "Job" }, { key: "count", label: "Times", num: true }], u.reports_by_type) : el("p", { class: "muted", text: "None in this period." }));
        clear(usersBox).appendChild(u.top_users.length ? table([{ key: "name", label: "Person" }, { key: "actions", label: "Actions", num: true }], u.top_users) : el("p", { class: "muted", text: "Nobody yet." }));
        clear(failBox).appendChild(u.failures.length ? table([{ key: "time", label: "Time (UTC)" }, { key: "project", label: "Project" }, { key: "title", label: "Job" }, { key: "error", label: "Error" }], u.failures)
          : el("p", { class: "muted", text: "No failed jobs in this period." }));
      }).catch(fail);
    }
    function loadServer() {
      api("GET", "/api/admin/dashboard/system").then(function (s) {
        var m = s.memory;
        clear(serverTiles);
        add(serverTiles, [tile("Memory used", m.used === null ? "–" : bytes(m.used)), tile("Memory limit", m.limit === null ? "–" : bytes(m.limit)),
          tile("This server's memory", m.process === null ? "–" : bytes(m.process)),
          tile("CPU now", s.cpu.percent === null ? "–" : s.cpu.percent + "%"), tile("CPUs", s.cpu.cpus),
          tile("Disk free", bytes(s.disk.free)), tile("Disk used", bytes(s.disk.used)),
          tile("Jobs running", s.jobs.running + " of " + s.slots), tile("Jobs waiting", s.jobs.waiting),
          tile("Up for", short(s.uptime_seconds)), tile("Version", s.version), tile("Python", s.python)]);
        var h = s.history;
        var times = h.map(function (p) { return clock(p.time); });
        var dot = h.length < 3 ? 3 : 0;
        var mb = function (v) { return v === null ? null : Math.round(v / 1048576); };
        drawChart(memChart, { type: "line", data: { labels: times, datasets: [
          { label: "Used", data: h.map(function (p) { return mb(p.memory_used); }), borderColor: cssVar("--series-1"), backgroundColor: cssVar("--series-1"), borderWidth: 2, pointRadius: dot, pointHoverRadius: 4 },
          { label: "Limit", data: h.map(function (p) { return mb(p.memory_limit); }), borderColor: cssVar("--muted"), backgroundColor: cssVar("--muted"), borderWidth: 2, borderDash: [6, 4], pointRadius: dot, pointHoverRadius: 4 }] },
          options: { plugins: { legend: { position: "top", align: "start" } }, scales: { x: { grid: false, ticks: { maxTicksLimit: 8 } }, y: { beginAtZero: true, title: { display: true, text: "MB" } } } } },
          [{ key: "time", label: "Time (UTC)" }, { label: "Used (MB)", num: true, value: function (p) { return mb(p.memory_used); } }, { label: "Limit (MB)", num: true, value: function (p) { return mb(p.memory_limit); } }], h.slice(-30).reverse());
        drawChart(cpuChart, { type: "line", data: { labels: times, datasets: [
          { label: "CPU", data: h.map(function (p) { return p.cpu_percent; }), borderColor: cssVar("--series-1"), backgroundColor: cssVar("--series-1"), borderWidth: 2, pointRadius: dot, pointHoverRadius: 4 }] },
          options: { plugins: { legend: { display: false } }, scales: { x: { grid: false, ticks: { maxTicksLimit: 8 } }, y: { beginAtZero: true, max: 100, title: { display: true, text: "% of " + s.cpu.cpus + " CPUs" } } } } },
          [{ key: "time", label: "Time (UTC)" }, { key: "cpu_percent", label: "CPU %", num: true }], h.slice(-30).reverse());
        clear(nowBox).appendChild(s.now.length ? table([{ key: "project", label: "Project" }, { key: "title", label: "Job" }, { key: "user", label: "Started by" },
          { label: "State", value: function (j) { return chip(j.state); } }, { label: "Time left", value: function (j) { return j.eta_seconds ? duration(j.eta_seconds) : ""; } }], s.now)
          : el("p", { class: "muted", text: "Nothing running." }));
      }).catch(fail);
    }
    days.addEventListener("change", loadUsage);
    csv.addEventListener("click", function () {
      if (!last) return;
      var cols = ["day", "logins", "active_users", "scans_done", "scans_failed", "scans_cancelled", "reports", "enrich"];
      download("legacy-audit-usage-" + last.from + "-to-" + last.to + ".csv",
        [cols.join(",")].concat(last.series.map(function (r) { return cols.map(function (c) { return r[c]; }).join(","); })).join("\n") + "\n");
    });
    loadUsage();
    loadServer();
    var ticks = 0;
    state.timer = setInterval(function () {
      if (!document.body.contains(host)) return;
      ticks++;
      loadServer();
      if (ticks % 6 === 0) loadUsage();
    }, 10000);
  }

  // ---------------------------------------------------------------- routing
  var SCREENS = { projects: screenProjects, status: screenStatus, logs: screenLogs, admin: screenAdmin };
  function route() {
    clearInterval(state.timer);
    var name = location.hash.slice(1).split("?")[0] || "projects";
    var fn = SCREENS[name] || screenProjects;
    Array.prototype.forEach.call(document.querySelectorAll("#nav a"), function (a) {
      var cur = a.getAttribute("data-screen") === name;
      a.classList.toggle("current", cur);
      if (cur) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    });
    clear(screenEl);
    fn(screenEl);
  }
  window.addEventListener("hashchange", route);

  (function () {
    var KEY = "la-theme";
    var saved = null;
    try { saved = localStorage.getItem(KEY); } catch (e) { saved = null; }
    if (saved) document.documentElement.setAttribute("data-theme", saved);
    document.getElementById("theme-toggle").addEventListener("click", function () {
      var dark = document.documentElement.getAttribute("data-theme") === "dark" ||
        (!document.documentElement.getAttribute("data-theme") && matchMedia("(prefers-color-scheme: dark)").matches);
      var next = dark ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      try { localStorage.setItem(KEY, next); } catch (e) { /* private window */ }
      if (document.querySelector("canvas")) route(); // charts re-read the colours
    });
  })();

  api("GET", "/api/me").then(function (me) {
    state.me = me;
    document.getElementById("who").textContent = me.name + " (" + me.role + ")";
    document.getElementById("version").textContent = me.version;
    document.getElementById("nav-admin").hidden = me.role !== "admin";
    if (me.banner) { var b = document.getElementById("banner"); b.textContent = me.banner; b.hidden = false; }
    route();
  }).catch(fail);
})();
