/* legacy-audit GUI. Plain JavaScript, no build step, no network beyond this local server.
   Every number shown comes from the API (the scan's facts); this file only lays it out. */
(function () {
  "use strict";

  // ---------------------------------------------------------------- helpers
  var screenEl = document.getElementById("screen");
  var state = { ws: null, runs: [], job: null, jobSource: null, charts: [] };

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
  function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

  function toast(message, isError) {
    var t = document.getElementById("toast");
    t.textContent = message;
    t.className = "toast" + (isError ? " error" : "");
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { t.hidden = true; }, isError ? 9000 : 4000);
  }

  function api(method, path, body, raw) {
    var opts = { method: method, headers: {} };
    if (method !== "GET") opts.headers["X-Legacy-Audit"] = "1";
    if (body !== undefined) {
      if (raw) { opts.body = body; opts.headers["Content-Type"] = "application/octet-stream"; }
      else { opts.body = JSON.stringify(body); opts.headers["Content-Type"] = "application/json"; }
    }
    return fetch(path, opts).then(function (r) {
      return r.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        if (!r.ok) {
          var detail = data && data.detail ? data.detail : r.statusText;
          if (typeof detail !== "string") detail = JSON.stringify(detail);
          var err = new Error(detail);
          err.status = r.status;
          throw err;
        }
        return data;
      });
    });
  }
  function fail(err) { toast(err.message || String(err), true); }
  function jobActive(j) { return !!(j && (j.state === "running" || j.state === "queued")); }
  function hosted() { return state.ws && state.ws.hosted; }
  function reportAllowed(k) { var h = hosted(); return !h || h.reports.indexOf(k) >= 0; }
  function duration(sec) {
    if (sec === null || sec === undefined) return "";
    if (sec < 60) return "under a minute";
    var m = Math.round(sec / 60);
    return m < 60 ? "about " + m + " min" : "about " + Math.floor(m / 60) + " h " + (m % 60) + " min";
  }

  function add(node, items) { items.forEach(function (i) { if (i) node.appendChild(i); }); }
  function heading(title, lead) {
    return [el("h1", { text: title }), lead ? el("p", { class: "lead", text: lead }) : null];
  }
  function table(columns, rows, opts) {
    opts = opts || {};
    var thead = el("tr", null, columns.map(function (c) {
      return el("th", { class: c.num ? "num" : null, text: c.label });
    }));
    var tbody = el("tbody");
    rows.forEach(function (r) {
      var tr = el("tr", { class: opts.onRow ? "clickable" : null });
      columns.forEach(function (c) {
        var v = c.value ? c.value(r) : r[c.key];
        var td = el("td", { class: c.num ? "num" : (c.code ? "code" : null) });
        if (v instanceof Node) td.appendChild(v); else td.textContent = c.num ? fmt(v) : (v === null || v === undefined ? "" : (typeof v === "object" ? JSON.stringify(v) : String(v)));
        tr.appendChild(td);
      });
      if (opts.onRow) {
        tr.tabIndex = 0;
        tr.addEventListener("click", function () { opts.onRow(r, tr); });
        tr.addEventListener("keydown", function (e) { if (e.key === "Enter") opts.onRow(r, tr); });
      }
      tbody.appendChild(tr);
    });
    return el("div", { class: "table-wrap" }, [el("table", { class: "data" }, [el("thead", null, [thead]), tbody])]);
  }
  function chip(value) {
    var cls = String(value || "").toLowerCase().replace(/\s+/g, "-");
    return el("span", { class: "chip " + cls, text: value });
  }
  function needConfig(node) {
    if (state.ws && state.ws.config) return false;
    node.appendChild(el("div", { class: "empty" }, [
      "This workspace has no audit.yaml yet. ",
      el("a", { href: "#setup", text: "Set up the project first." })
    ]));
    return true;
  }
  function needRun(node) {
    if (state.runs.length) return false;
    node.appendChild(el("div", { class: "empty" }, [
      "No scan yet. ", el("a", { href: "#run", text: "Run the first scan." })
    ]));
    return true;
  }
  function runSelect(selected, onChange, label) {
    var sel = el("select", { "aria-label": label || "Run" });
    state.runs.forEach(function (r) {
      var o = new Option(r.run_id + (r.latest ? " (latest)" : ""), r.run_id);
      if (r.run_id === selected) o.selected = true;
      sel.appendChild(o);
    });
    sel.addEventListener("change", function () { onChange(sel.value); });
    return sel;
  }
  function currentRun() {
    var m = location.hash.match(/run=([0-9a-f-]+)/);
    if (m && state.runs.some(function (r) { return r.run_id === m[1]; })) return m[1];
    var latest = state.runs.filter(function (r) { return r.latest; })[0];
    return latest ? latest.run_id : (state.runs[0] && state.runs[0].run_id);
  }
  function setRunInHash(screen, run) { location.hash = "#" + screen + "?run=" + run; }

  function refreshWorkspace() {
    return api("GET", "api/workspace").then(function (ws) {
      state.ws = ws;
      document.getElementById("app-name").textContent = ws.application || "legacy-audit";
      document.getElementById("app-folder").textContent = ws.folder;
      var version = document.getElementById("version");
      if (version) version.textContent = ws.version;
      if (ws.hosted) {
        var who = document.getElementById("hosted-user");
        if (!who) {
          var bar = document.querySelector("header.top .app");
          bar.insertBefore(el("a", { class: "btn small", href: ws.hosted.home, text: "← All projects" }), bar.firstChild);
          bar.appendChild(el("span", { id: "hosted-user", class: "meta", text: ws.hosted.user.name + " (" + ws.hosted.user.role + ")" }));
          bar.appendChild(el("a", { class: "btn small", href: ws.hosted.home + "auth/logout", text: "Log out" }));
          var foot = document.querySelector("footer");
          if (foot) foot.textContent = "legacy-audit " + ws.version + ". Hosted site: project " + ws.hosted.project + ". Scripts count, AI interprets.";
        }
      }
      if (ws.config_error) toast(ws.config_error, true);
      if (!ws.config || ws.config_error) { state.runs = []; return ws; }
      return api("GET", "api/runs").then(function (runs) { state.runs = runs; return ws; });
    });
  }

  // ---------------------------------------------------------------- charts
  function destroyCharts() { state.charts.forEach(function (c) { c.destroy(); }); state.charts = []; }
  var STATUS = { critical: "--critical", high: "--serious", medium: "--warning", low: "--info", info: "--info",
    auto: "--good", review: "--warning", manual: "--critical",
    simple: "--good", moderate: "--warning", complex: "--serious", "very complex": "--critical" };

  function barChart(title, data, opts) {
    opts = opts || {};
    var labels = Object.keys(data);
    var values = labels.map(function (k) { return data[k]; });
    var canvas = el("canvas", { role: "img", "aria-label": title });
    var box = el("div", { class: "chart-box" }, [canvas]);
    var rows = labels.map(function (k) { return { label: k, value: data[k] }; });
    var panel = el("div", { class: "panel" }, [
      el("h3", { text: title }),
      labels.length ? box : el("p", { class: "muted", text: "Nothing to show for this run." }),
      labels.length ? el("details", { class: "as-table" }, [el("summary", { text: "Show as table" }),
        table([{ key: "label", label: opts.keyLabel || "Item" }, { key: "value", label: opts.unit || "Count", num: true }], rows)]) : null
    ]);
    if (!labels.length || typeof Chart === "undefined") return panel;
    setTimeout(function () {
      var colours = labels.map(function (k) {
        var v = opts.status && STATUS[String(k).toLowerCase()];
        return v ? cssVar(v) : cssVar("--series-1");
      });
      var ink = cssVar("--text-2"), grid = cssVar("--grid");
      state.charts.push(new Chart(canvas, {
        type: "bar",
        data: { labels: labels, datasets: [{ data: values, backgroundColor: colours, borderRadius: 4, maxBarThickness: 28 }] },
        options: {
          indexAxis: opts.horizontal ? "y" : "x", responsive: true, maintainAspectRatio: false, animation: false,
          plugins: { legend: { display: false }, tooltip: { callbacks: { label: function (c) { return fmt(c.raw) + (opts.unit ? " " + opts.unit : ""); } } } },
          scales: {
            x: { ticks: { color: ink }, grid: { color: opts.horizontal ? grid : "transparent" } },
            y: { ticks: { color: ink }, grid: { color: opts.horizontal ? "transparent" : grid }, beginAtZero: true }
          }
        }
      }));
    }, 0);
    return panel;
  }

  // ---------------------------------------------------------------- jobs (scan and report builds)
  var STAGE_ORDER = ["inventory", "analysis", "components", "link", "metrics", "assess", "export"];
  var STAGE_LABEL = { inventory: "Inventory", analysis: "Analysis", components: "Components", link: "Linking",
    metrics: "Metrics", assess: "Rules and grades", "export": "Writing facts" };
  var jobListeners = [];

  function watchJob(job) {
    state.job = job;
    var ind = document.getElementById("job-indicator");
    ind.hidden = false;
    ind.textContent = job.title + "…";
    if (state.jobSource) state.jobSource.close();
    var src = new EventSource("api/jobs/" + job.id + "/events");
    state.jobSource = src;
    clearInterval(state.etaTimer);
    state.etaTimer = setInterval(function () {
      api("GET", "api/jobs/" + job.id).then(function (j) {
        job.state = j.state;
        state.eta = j;
        jobListeners.forEach(function (fn) { fn({ type: "eta", job: j }, job); });
        if (j.state === "running" && j.eta_seconds) ind.textContent = job.title + (j.stage ? ": " + (STAGE_LABEL[j.stage] || j.stage) : "") + ", " + duration(j.eta_seconds) + " left";
      }).catch(function () { /* the next tick tries again */ });
    }, 3000);
    src.onmessage = function (m) {
      var ev = JSON.parse(m.data);
      jobListeners.forEach(function (fn) { fn(ev, job); });
      if (ev.type === "queued") ind.textContent = job.title + ": waiting, place " + ev.position + " in the queue";
      if (ev.type === "started") job.state = "running";
      if (ev.type === "progress") {
        ind.textContent = job.title + ": " + (STAGE_LABEL[ev.stage] || ev.stage) + (ev.total ? " " + Math.round(100 * ev.n / ev.total) + "%" : "");
      }
      if (ev.type === "end") {
        src.close();
        clearInterval(state.etaTimer);
        state.jobSource = null;
        ind.hidden = true;
        job.state = ev.state;
        if (ev.state === "done") toast(job.title + " finished.");
        else if (ev.state === "cancelled") toast(job.title + " cancelled.");
        else toast(job.title + " failed: " + ev.error, true);
        refreshWorkspace().then(function () { route(); });
      }
    };
  }
  function startJob(promise) {
    return promise.then(function (job) { watchJob(job); route(); return job; }).catch(fail);
  }

  // ---------------------------------------------------------------- 1. project setup
  function screenSetup(node) {
    node.appendChild(el("h1", { text: "Project setup" }));
    if (!state.ws.config) {
      var name = el("input", { type: "text", id: "init-name", placeholder: "e.g. Customer billing" });
      node.appendChild(el("p", { class: "lead", text: "No audit.yaml in " + state.ws.folder + ". Create the starter files (audit.yaml, audit-overrides.yaml, audit-inputs/ and the Copilot agent files) here." }));
      node.appendChild(el("form", { class: "fields", onsubmit: function (e) {
        e.preventDefault();
        api("POST", "api/init", { name: name.value || null }).then(function () {
          toast("Starter files created."); return refreshWorkspace();
        }).then(route).catch(fail);
      } }, [el("label", { for: "init-name", text: "Application name" }), name,
        el("span"), el("div", null, [el("button", { class: "btn primary", type: "submit", text: "Create audit.yaml" })])]));
      node.appendChild(doctorPanel());
      return;
    }
    api("GET", "api/config").then(function (cfg) {
      var f = cfg.fields;
      var src = f.source;
      var appName = el("input", { type: "text", id: "f-name", value: f.application.name });
      var appDesc = el("input", { type: "text", id: "f-desc", value: f.application.description || "" });
      var srcType = el("select", { id: "f-src" }, [new Option("Local folders", "local"), new Option("Git repositories", "git")]);
      srcType.value = src.type;
      var paths = el("textarea", { id: "f-paths", rows: 3 });
      paths.value = (src.paths || ["."]).join("\n");
      var repos = el("textarea", { id: "f-repos", rows: 4 });
      repos.value = (src.repos || []).map(function (r) {
        return [r.url, r.ref || "", (r.paths || []).join(","), r.token_env || ""].join(" | ");
      }).join("\n");
      var localRow = [el("label", { for: "f-paths", text: "Folders" }), el("div", null, [paths, el("p", { class: "hint", text: "One per line, relative to this workspace or absolute." })])];
      var gitRow = [el("label", { for: "f-repos", text: "Repositories" }), el("div", null, [repos,
        el("p", { class: "hint", text: "One per line: URL | branch or tag | sub-paths (comma separated) | name of the environment variable holding a read-only token. The token itself is never stored or typed here." })])];
      var target = f.target;
      var profile = el("select", { id: "t-profile" }, [
        new Option("Spring Boot and Angular on Google Cloud (default)", "spring-boot-angular"),
        new Option("WildFly on GKE (code kept as it is, database to PostgreSQL)", "wildfly-gke"),
        new Option("None: audit only, no migration target", "none")]);
      profile.value = target.profile || "spring-boot-angular";
      var tgt = {};
      // the versions each target uses; "none" uses none
      var VERSIONS = { "spring-boot-angular": ["java", "spring_boot", "angular", "database", "cloud"],
        "wildfly-gke": ["java", "wildfly", "jakarta_ee", "database"], "none": [] };
      ["java", "spring_boot", "angular", "wildfly", "jakarta_ee", "database", "cloud"].forEach(function (k) {
        tgt[k] = el("input", { type: "text", id: "t-" + k, value: target[k] || "" });
      });
      var versionRow = el("div", { class: "row" }, Object.keys(tgt).map(function (k) {
        return el("label", { for: "t-" + k, text: k.replace("_", " ") + " " }, [tgt[k]]);
      }));
      function drawTarget() {
        var shown = VERSIONS[profile.value] || [];
        Array.prototype.forEach.call(versionRow.children, function (lab, i) {
          lab.hidden = shown.indexOf(Object.keys(tgt)[i]) < 0;
        });
      }
      profile.addEventListener("change", drawTarget);
      var include = el("textarea", { id: "f-include", rows: 2 }); include.value = (f.include || []).join("\n");
      var exclude = el("textarea", { id: "f-exclude", rows: 3 }); exclude.value = (f.exclude || []).join("\n");
      var form = el("form", { class: "fields" });
      function lines(t) { return t.value.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean); }
      function drawSource() {
        localRow.concat(gitRow).forEach(function (n) { n.hidden = false; });
        (srcType.value === "git" ? localRow : gitRow).forEach(function (n) { n.hidden = true; });
      }
      srcType.addEventListener("change", drawSource);
      [el("label", { for: "f-name", text: "Application name" }), appName,
        el("label", { for: "f-desc", text: "Description" }), appDesc,
        el("label", { for: "f-src", text: "Source" }), srcType]
        .concat(localRow, gitRow, [
          el("label", { for: "t-profile", text: "Migration target" }),
          el("div", null, [profile, versionRow, el("p", { class: "hint", text: "What the reports and the AI analysis suggest the application moves to. Changing it needs a new scan; then run /audit-enrich stale." })]),
          el("label", { for: "f-include", text: "Include" }), include,
          el("label", { for: "f-exclude", text: "Exclude" }), el("div", null, [exclude, el("p", { class: "hint", text: ".gitignore syntax, one pattern per line." })]),
          el("span"), el("div", null, [el("button", { class: "btn primary", type: "submit", text: "Save audit.yaml" })])
        ]).forEach(function (n) { form.appendChild(n); });
      drawSource();
      drawTarget();
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        var app = Object.assign({}, f.application, { name: appName.value, description: appDesc.value || null });
        var source;
        if (srcType.value === "git") {
          source = Object.assign({}, src.type === "git" ? src : {}, { type: "git", repos: lines(repos).map(function (line) {
            var p = line.split("|").map(function (s) { return s.trim(); });
            var r = { url: p[0] };
            if (p[1]) r.ref = p[1];
            if (p[2]) r.paths = p[2].split(",").map(function (s) { return s.trim(); }).filter(Boolean);
            if (p[3]) r.token_env = p[3];
            return r;
          }) });
          delete source.paths;
        } else {
          source = { type: "local", paths: lines(paths) };
        }
        var t = Object.assign({}, target, { profile: profile.value });
        Object.keys(tgt).forEach(function (k) { t[k] = tgt[k].value; });
        api("PUT", "api/config/fields", { application: app, source: source, target: t, include: lines(include), exclude: lines(exclude) })
          .then(function () { toast("audit.yaml saved."); return refreshWorkspace(); }).then(route).catch(fail);
      });
      node.appendChild(el("p", { class: "lead", text: "Settings in " + cfg.path + ". Saving keeps every comment and setting this form does not show." }));
      node.appendChild(form);
      var raw = el("textarea", { class: "config", "aria-label": "audit.yaml" });
      raw.value = cfg.text;
      node.appendChild(el("details", null, [el("summary", { text: "Advanced: edit audit.yaml as text" }), raw,
        el("div", { class: "row" }, [el("button", { class: "btn", type: "button", text: "Check and save", onclick: function () {
          api("PUT", "api/config/text", { text: raw.value }).then(function () { toast("audit.yaml saved."); return refreshWorkspace(); }).then(route).catch(fail);
        } })])]));
      node.appendChild(doctorPanel());
    }).catch(fail);
  }
  function doctorPanel() {
    var host = el("div", null, [el("h2", { text: "Tools on this computer" }), el("p", { class: "muted", text: "Checking…" })]);
    api("GET", "api/doctor").then(function (checks) {
      host.removeChild(host.lastChild);
      host.appendChild(table([
        { key: "name", label: "Tool" },
        { label: "Status", value: function (c) { return el("span", { class: "state " + (c.status === "ok" ? "valid" : c.required ? "invalid" : "stale"), text: c.status }); } },
        { label: "", value: function (c) { return c.required ? "required" : "optional"; } },
        { key: "detail", label: "Detail", code: true },
        { key: "needed_for", label: "Needed for" }
      ], checks));
    }).catch(fail);
    return host;
  }

  // ---------------------------------------------------------------- 2. inputs
  function screenInputs(node) {
    add(node, heading("Inputs", "Exports that arrive after the code. Missing ones are reported as coverage gaps, not errors; add a file and re-run the scan to include it."));
    if (needConfig(node)) return;
    api("GET", "api/inputs").then(function (data) {
      node.appendChild(el("div", { class: "row" }, [
        hosted() ? null : el("button", { class: "btn", text: "Open inputs folder", onclick: function () { api("POST", "api/open", { target: "inputs" }).catch(fail); } }),
        el("a", { class: "btn primary", href: "#run", text: "Re-run the scan" })
      ]));
      var grid = el("div", { class: "grid2" });
      data.kinds.forEach(function (k) {
        var file = el("input", { type: "file", multiple: true, "aria-label": "Add " + k.label });
        file.addEventListener("change", function () {
          var uploads = Array.prototype.map.call(file.files, function (f) {
            return f.arrayBuffer().then(function (buf) { return api("PUT", "api/inputs/" + k.kind + "/" + encodeURIComponent(f.name), buf, true); });
          });
          Promise.all(uploads).then(function () { toast("Added to " + k.label + ". Re-run the scan to include it."); route(); }).catch(fail);
        });
        var list = k.files.length ? el("ul", { class: "compact" }, k.files.map(function (f) {
          return el("li", null, [f.name + " (" + fmt(f.size) + " bytes) ",
            el("button", { class: "btn small danger", text: "Remove", onclick: function () {
              if (!confirm("Remove " + f.name + " from " + k.label + "?")) return;
              api("DELETE", "api/inputs/" + k.kind + "/" + encodeURIComponent(f.name)).then(route).catch(fail);
            } })]);
        })) : el("p", { class: "muted", text: "Not provided" });
        grid.appendChild(el("div", { class: "panel" }, [
          el("h3", null, [el("span", { class: "badge " + (k.files.length ? "present" : "missing"), text: k.files.length ? "present" : "missing" }), " ", k.label]),
          list, el("p", { class: "hint", text: k.folder }), file
        ]));
      });
      node.appendChild(grid);
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 3. components
  var TYPES = ["web", "frontend", "api", "desktop", "batch", "online-mainframe", "cft", "script", "config", "report", "library", "db", "unknown"];
  function screenComponents(node) {
    add(node, heading("Components", "Detected from build files and folders (only pom.xml and settings.gradle are read, so a Maven parent or aggregator is a group of modules, not a component); the type is the last scan's when there is one. Library modules are grouped into the war, ear or batch that uses them, and part folders of one technology into one component (inventory.component_grouping). Untick a component to leave its folders out; pick a type to override the detection. Choices are saved to audit-overrides.yaml and applied on the next run."));
    if (needConfig(node)) return;
    var host = el("div", null, [el("p", { class: "muted", text: "Pre-scanning…" })]);
    node.appendChild(host);
    api("GET", "api/components").then(function (data) {
      clear(host);
      host.appendChild(el("p", { class: "muted", text: fmt(data.files) + " files in " + data.roots.map(function (r) { return r.name; }).join(", ") + "." }));
      var ticks = {}, types = {};
      var rows = data.components;
      host.appendChild(table([
        { label: "Scan", value: function (c) {
          var cb = el("input", { type: "checkbox", "aria-label": "Scan " + c.name });
          cb.checked = c.included; ticks[(c.folders || [c.path]).join("|")] = cb; return cb; } },
        { key: "name", label: "Component" },
        { label: "Folder", value: function (c) { return (c.parts && c.parts.length ? c.parts : [c.path || "(root)"]).join(", "); }, code: true },
        { label: "Type", value: function (c) { return c.scan_type || c.detected_type; } },
        { label: "Type override", value: function (c) {
          var sel = el("select", { "aria-label": "Type of " + c.name }, [new Option("(as detected)", "")].concat(TYPES.map(function (t) { return new Option(t, t); })));
          sel.value = c.type_override || ""; types[c.name] = sel; return sel; } },
        { key: "files", label: "Files", num: true },
        { label: "Languages", value: function (c) { return Object.keys(c.languages).map(function (l) { return l + " " + c.languages[l]; }).join(", "); } },
        { label: "Maven group", value: function (c) { return (c.build_group || []).join(" > ") + (c.packaging ? (c.build_group && c.build_group.length ? " · " : "") + c.packaging : ""); } },
        { key: "detected_by", label: "Detected by" }
      ], rows));
      if (data.skipped && data.skipped.length) {
        host.appendChild(el("p", { class: "hint", text: "Left out as build output or generated files: " + data.skipped.map(function (k) { return k.path + " (" + fmt(k.files) + " files)"; }).join(", ") + ". Set inventory.skip_build_output: false in audit.yaml to scan them." }));
      }
      host.appendChild(el("div", { class: "row" }, [
        el("button", { class: "btn primary", text: "Save choices", onclick: function () {
          var inc = {}, ty = {};
          Object.keys(ticks).forEach(function (p) { inc[p] = ticks[p].checked; });
          Object.keys(types).forEach(function (n) { ty[n] = types[n].value || null; });
          api("PUT", "api/components", { included: inc, types: ty }).then(function () { toast("Saved to audit-overrides.yaml. Re-run to apply."); route(); }).catch(fail);
        } }),
        el("a", { class: "btn", href: "#run", text: "Go to Run" })
      ]));
    }).catch(function (e) { clear(host); fail(e); });
  }

  // ---------------------------------------------------------------- 4. run
  function screenRun(node) {
    add(node, heading("Run", "Re-run analyses only changed files (the rest comes from the cache); linking, metrics, rules and totals are always recomputed. Full rescan ignores the cache."));
    if (needConfig(node)) return;
    var running = jobActive(state.job);
    node.appendChild(el("div", { class: "row" }, [
      el("button", { class: "btn primary", text: "Re-run", disabled: running, onclick: function () { startJob(api("POST", "api/scan", { full: false })); } }),
      el("button", { class: "btn", text: "Full rescan", disabled: running, onclick: function () { startJob(api("POST", "api/scan", { full: true })); } }),
      el("button", { class: "btn danger", text: "Cancel", disabled: !running, onclick: function () { api("POST", "api/jobs/" + state.job.id + "/cancel").catch(fail); } })
    ]));
    var bar = el("div", { class: "progress indeterminate", role: "progressbar", "aria-label": "Scan progress" }, [el("div")]);
    var label = el("p", { class: "muted", text: running ? "Starting…" : "No scan running." });
    var eta = el("p", { class: "muted", hidden: true });
    var stages = el("div", { class: "stages" }, STAGE_ORDER.map(function (s) { return el("span", { "data-stage": s, text: STAGE_LABEL[s] }); }));
    var log = el("ul", { class: "log" });
    var panel = el("div", { class: "panel" }, [label, bar, eta, stages, log]);
    if (!running) bar.hidden = true;
    node.appendChild(panel);

    function onEvent(ev, job) {
      if (ev.type === "queued") { label.textContent = "Waiting for a free slot: place " + ev.position + " in the queue."; bar.hidden = true; return; }
      if (ev.type === "eta") {
        var j = ev.job;
        var parts = [];
        if (j.elapsed_seconds !== null) parts.push("Running for " + duration(j.elapsed_seconds));
        if (j.percent !== null && j.percent !== undefined) parts.push(Math.round(j.percent) + "% done");
        if (j.eta_seconds) parts.push(duration(j.eta_seconds) + " left (" + j.eta_basis + ")");
        else if (j.eta_basis) parts.push(j.eta_basis);
        eta.textContent = parts.join(", ") + ".";
        eta.hidden = !parts.length || j.state !== "running";
        return;
      }
      if (job.kind !== "scan" && ev.type !== "end") { label.textContent = job.title + "…"; return; }
      if (ev.type === "progress") {
        label.textContent = (STAGE_LABEL[ev.stage] || ev.stage) + (ev.total ? ": " + fmt(ev.n) + " of " + fmt(ev.total) + " files" : "");
        bar.hidden = false;
        bar.className = "progress" + (ev.total ? "" : " indeterminate");
        bar.firstChild.style.width = ev.total ? (100 * ev.n / ev.total).toFixed(1) + "%" : "";
        bar.setAttribute("aria-valuenow", ev.total ? Math.round(100 * ev.n / ev.total) : "");
        var idx = STAGE_ORDER.indexOf(ev.stage);
        Array.prototype.forEach.call(stages.children, function (s, i) { s.className = i < idx ? "done" : i === idx ? "now" : ""; });
      } else if (ev.type === "warning") {
        log.appendChild(el("li", { class: "warning", text: ev.message }));
      } else if (ev.type === "end") {
        bar.hidden = true;
        eta.hidden = true;
        label.textContent = ev.state === "done" && ev.result && ev.result.run_id
          ? "Run " + ev.result.run_id + " finished: " + fmt(ev.result.files) + " files, " + fmt(ev.result.analyzed) + " analysed, " + fmt(ev.result.cached) + " from cache."
          : (ev.state === "failed" ? "Failed: " + ev.error : "Scan " + ev.state + ".");
      }
    }
    jobListeners = [onEvent];
    if (running) api("GET", "api/jobs/" + state.job.id).then(function (j) { j.log.forEach(function (ev) { onEvent(ev, state.job); }); });

    node.appendChild(el("h2", { text: "Runs" }));
    if (!state.runs.length) { node.appendChild(el("p", { class: "muted", text: "No runs yet." })); return; }
    node.appendChild(table([
      { label: "Run", value: function (r) { return el("a", { href: "#dashboard?run=" + r.run_id, text: r.run_id + (r.latest ? " (latest)" : "") }); } },
      { key: "started_at", label: "Started" },
      { label: "Kind", value: function (r) { return r.full_scan ? "full" : "re-run"; } },
      { key: "files", label: "Files", num: true },
      { key: "analyzed", label: "Analysed", num: true },
      { key: "cached", label: "From cache", num: true },
      { key: "loc_code", label: "LOC", num: true },
      { key: "findings", label: "Findings", num: true },
      { key: "warnings", label: "Warnings", num: true },
      { key: "seconds", label: "Seconds", num: true }
    ], state.runs));
  }

  // ---------------------------------------------------------------- 5. dashboard
  function screenDashboard(node) {
    if (needConfig(node) || needRun(node)) return;
    var run = currentRun();
    api("GET", "api/runs/" + run + "/dashboard").then(function (d) {
      node.appendChild(el("h1", { text: "Results dashboard" }));
      node.appendChild(el("div", { class: "controls" }, [el("label", null, ["Run ", runSelect(run, function (v) { setRunInHash("dashboard", v); })]),
        el("a", { class: "btn", href: "#reports?run=" + run, text: "Reports" }), el("a", { class: "btn", href: "#explorer?run=" + run, text: "Explore" })]));
      node.appendChild(el("div", { class: "tiles" }, d.kpis.map(function (k) {
        return el("div", { class: "tile", title: "From " + k.metric }, [el("div", { class: "value", text: fmt(k.value) }), el("div", { class: "label", text: k.label })]);
      })));
      node.appendChild(el("div", { class: "badges" }, Object.keys(d.coverage).map(function (k) {
        var text = d.coverage[k];
        return el("span", { class: "badge " + (/not provided|not installed/.test(text) ? "missing" : "present"), text: text });
      })));
      var charts = el("div", { class: "grid2" }, [
        barChart("Lines of code by language", d.charts.technology, { horizontal: true, unit: "LOC", keyLabel: "Language" }),
        barChart("Complexity (services, screens, batch jobs)", d.charts.complexity, { status: true, keyLabel: "Band" }),
        barChart("SQL statements by PostgreSQL grade", d.charts.pg_grade, { status: true, keyLabel: "Grade" }),
        barChart("Findings by severity", d.charts.severity, { status: true, keyLabel: "Severity" })
      ]);
      node.appendChild(el("h2", { text: "Charts" }));
      node.appendChild(charts);
      if (d.warnings.length) {
        node.appendChild(el("h2", { text: "Warnings" }));
        node.appendChild(el("ul", { class: "compact" }, d.warnings.map(function (w) { return el("li", { text: w }); })));
      }
      node.appendChild(el("h2", { text: "Top findings" }));
      node.appendChild(table([
        { label: "Severity", value: function (f) { return chip(f.severity); } },
        { key: "rule", label: "Rule" }, { key: "message", label: "Message" },
        { label: "Where", value: function (f) { return evidenceLink(run, f.file, f.line); } }
      ], d.top_findings));
      node.appendChild(el("h2", { text: "Components" }));
      node.appendChild(table([
        { label: "Component", value: function (c) { return el("a", { href: "#explorer?run=" + run + "&component=" + encodeURIComponent(c.name), text: c.name }); } },
        { key: "type", label: "Type" }, { key: "files", label: "Files", num: true }, { key: "loc_code", label: "LOC", num: true }
      ], d.components));
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 6. explorer
  var evidencePanel = null;
  function evidenceLink(run, file, line) {
    if (!file) return "";
    return el("a", { href: "#", text: file + (line ? ":" + line : ""), onclick: function (e) { e.preventDefault(); showEvidence(run, file, line); } });
  }
  function showEvidence(run, file, line) {
    var target = evidencePanel && document.body.contains(evidencePanel) ? evidencePanel : null;
    api("GET", "api/runs/" + run + "/evidence?file=" + encodeURIComponent(file) + (line ? "&line=" + line : "")).then(function (ev) {
      var body = [el("h3", { text: ev.file + (ev.line ? ":" + ev.line : "") }),
        ev.vscode ? el("a", { class: "btn small", href: ev.vscode, text: "Open in VS Code" }) : null,
        el("pre", { class: "block" }, ev.text.split("\n").map(function (t, i) {
          var n = ev.start + i;
          var span = el("span", { text: String(n).padStart(5, " ") + "  " + t + "\n" });
          if (n === ev.line) span.style.background = cssVar("--grid");
          return span;
        }))];
      if (target) { clear(target); body.forEach(function (b) { if (b) target.appendChild(b); }); }
      else {
        var dlg = el("dialog", { class: "panel" }, body.concat([el("form", { method: "dialog" }, [el("button", { class: "btn", text: "Close" })])]));
        document.body.appendChild(dlg);
        dlg.addEventListener("close", function () { dlg.remove(); });
        dlg.showModal();
      }
    }).catch(fail);
  }
  var EXPLORE_COLS = {
    components: ["name", "type", "files", "loc_code", "technology", "detected_by"],
    services: ["name", "kind", "component", "complexity", "tables", "file"],
    screens: ["name", "component", "partial", "complexity", "file"],
    sql_statements: ["type", "pg_grade", "tables", "component", "file", "text"],
    batch_jobs: ["name", "scheduler", "component", "command", "file"],
    integrations: ["type", "target", "direction", "components", "usages"],
    libraries: ["artifact", "version", "group", "eol", "vulnerabilities", "recommendation"],
    findings: ["severity", "rule_id", "message", "file"],
    inventory: ["path", "language", "category", "component", "loc_code", "size"]
  };
  function screenExplorer(node) {
    if (needConfig(node) || needRun(node)) return;
    var run = currentRun();
    var params = new URLSearchParams((location.hash.split("?")[1] || ""));
    var comp = params.get("component");
    node.appendChild(el("h1", { text: "Explorer" }));
    var kinds = el("select", { "aria-label": "Kind" });
    var search = el("input", { type: "search", placeholder: "Search any column", "aria-label": "Search" });
    var host = el("div");
    evidencePanel = el("div", { class: "panel" }, [el("p", { class: "muted", text: "Pick a row to see its source, or a component to drill down." })]);
    node.appendChild(el("div", { class: "controls" }, [el("label", null, ["Run ", runSelect(run, function (v) { setRunInHash("explorer", v); })]), kinds, search]));
    node.appendChild(el("div", { class: "split" }, [host, evidencePanel]));
    var page = { offset: 0, limit: 50 };
    api("GET", "api/kinds").then(function (all) {
      Object.keys(EXPLORE_COLS).concat(Object.keys(all).filter(function (k) { return !EXPLORE_COLS[k]; })).forEach(function (k) {
        if (all[k]) kinds.appendChild(new Option(k.replace(/_/g, " "), k));
      });
      kinds.value = params.get("kind") || "components";
      function load() {
        var kind = kinds.value;
        var q = "api/runs/" + run + "/facts/" + kind + "?limit=" + page.limit + "&offset=" + page.offset + "&q=" + encodeURIComponent(search.value);
        api("GET", q).then(function (res) {
          clear(host);
          var cols = (EXPLORE_COLS[kind] || all[kind].filter(function (c) { return c !== "evidence"; }).slice(0, 7)).filter(function (c) { return all[kind].indexOf(c) >= 0; });
          host.appendChild(table(cols.map(function (c) {
            return { label: c.replace(/_/g, " "), num: ["files", "loc_code", "line", "usages", "size"].indexOf(c) >= 0, code: c === "text" || c === "path",
              value: function (r) {
                var v = r[c];
                if (c === "severity" || c === "pg_grade" || c === "complexity") return v ? chip(v) : "";
                if (c === "file" && v) return el("span", { title: v, text: v.split("/").pop() + (r.line ? ":" + r.line : "") });
                if (Array.isArray(v)) return v.map(function (x) { return typeof x === "object" ? JSON.stringify(x) : x; }).join(", ");
                if (c === "text" && v && v.length > 160) return v.slice(0, 160) + "…";
                return v;
              } };
          }), res.rows, { onRow: function (r, tr) {
            Array.prototype.forEach.call(tr.parentNode.children, function (x) { x.classList.remove("selected"); });
            tr.classList.add("selected");
            if (kind === "components") drill(r.name);
            else if (r.file) showEvidence(run, r.file, r.line);
            else if (r.path) showEvidence(run, r.path, null);
            else if (r.evidence && r.evidence.length) showEvidence(run, r.evidence[0].file, r.evidence[0].line);
          } }));
          var pages = el("div", { class: "pager" }, [
            el("button", { text: "Previous", disabled: page.offset === 0, onclick: function () { page.offset = Math.max(0, page.offset - page.limit); load(); } }),
            el("span", { text: res.total ? fmt(page.offset + 1) + "–" + fmt(page.offset + res.returned) + " of " + fmt(res.total) : "No rows" }),
            el("button", { text: "Next", disabled: page.offset + res.returned >= res.total, onclick: function () { page.offset += page.limit; load(); } })
          ]);
          host.appendChild(pages);
        }).catch(fail);
      }
      kinds.addEventListener("change", function () { page.offset = 0; search.value = ""; load(); });
      var t;
      search.addEventListener("input", function () { clearTimeout(t); t = setTimeout(function () { page.offset = 0; load(); }, 250); });
      load();
      if (comp) drill(comp);
    }).catch(fail);

    function drill(name) {
      api("GET", "api/runs/" + run + "/component/" + encodeURIComponent(name)).then(function (d) {
        clear(evidencePanel);
        evidencePanel.appendChild(el("h3", { text: d.component.name + " (" + d.component.type + ")" }));
        evidencePanel.appendChild(el("p", { class: "muted", text: fmt(d.component.files) + " files, " + fmt(d.component.loc_code) + " lines of code" }));
        [["services", "Services", ["name", "kind", "complexity"]], ["screens", "Screens", ["name", "complexity"]],
          ["sql_statements", "SQL", ["type", "pg_grade", "file", "line"]], ["batch_jobs", "Batch jobs", ["name", "scheduler"]],
          ["integrations", "Integrations", ["type", "target", "direction"]]].forEach(function (spec) {
          var res = d[spec[0]];
          if (!res || !res.total) return;
          evidencePanel.appendChild(el("h3", { text: spec[1] + " (" + fmt(res.total) + ")" }));
          evidencePanel.appendChild(table(spec[2].map(function (c) {
            return { label: c.replace(/_/g, " "), value: function (r) { return c === "pg_grade" || c === "complexity" ? (r[c] ? chip(r[c]) : "") : r[c]; } };
          }), res.rows, { onRow: function (r) {
            if (spec[0] === "sql_statements") showEvidence(run, r.file, r.line);
            else { kinds.value = spec[0]; search.value = r.name || r.target || ""; kinds.dispatchEvent(new Event("change")); search.value = r.name || r.target || ""; search.dispatchEvent(new Event("input")); }
          } }));
        });
      }).catch(fail);
    }
  }

  // ---------------------------------------------------------------- 7. enrichment
  function screenEnrichment(node) {
    add(node, heading("AI enrichment", "An AI model writes the enrichment: Copilot in VS Code by default, or any model set under ai: in audit.yaml. Each stage is checked against the scan: numbers must match, claims need evidence."));
    if (needConfig(node) || needRun(node)) return;
    var run = currentRun();
    var host = el("div");
    node.appendChild(el("div", { class: "controls" }, [el("label", null, ["Run ", runSelect(run, function (v) { setRunInHash("enrichment", v); })]),
      el("button", { class: "btn", text: "Validate now", onclick: function () {
        api("POST", "api/runs/" + run + "/validate").then(function (d) { draw(d); toast(d.messages.filter(function (m) { return m.level === "error"; }).length + " error(s), " + d.checked.length + " stage file(s) checked."); }).catch(fail);
      } })]));
    node.appendChild(host);
    function draw(d) {
      clear(host);
      var ai = d.ai || { provider: "copilot" };
      if (d.ai_allowed === false) {
        host.appendChild(el("p", { class: "hint", text: "The admin has switched off AI enrichment on this site." }));
      } else if (ai.provider !== "copilot") {
        var running = jobActive(state.job);
        host.appendChild(el("div", { class: "panel" }, [el("h3", { text: "Run here with " + ai.label }),
          ai.problem ? el("p", { class: "hint", text: ai.problem }) : el("p", { class: "hint", text: "The tool sends the facts of each stage to this model, checks the answer and sends any errors back to fix. The API key is read from an environment variable." }),
          el("div", { class: "controls" }, [
            el("button", { class: "btn primary", text: "Run missing and stale stages", disabled: running || !!ai.problem, onclick: function () { startJob(api("POST", "api/runs/" + run + "/enrich", { stale: true })); } }),
            el("button", { class: "btn", text: "Run all stages", disabled: running || !!ai.problem, onclick: function () { startJob(api("POST", "api/runs/" + run + "/enrich", { stale: false })); } })])]));
      }
      if (d.next_prompt) {
        var code = el("code", { text: d.next_prompt });
        host.appendChild(el("div", { class: "panel" }, [el("h3", { text: (ai.provider !== "copilot" ? "Or in " : "Next step in ") + "Copilot Chat (legacy-auditor agent)" }),
          el("div", { class: "prompt" }, [code, el("button", { class: "btn small", text: "Copy", onclick: function () {
            (navigator.clipboard ? navigator.clipboard.writeText(d.next_prompt) : Promise.reject(new Error("Clipboard not available"))).then(function () { toast("Copied. Paste it into Copilot Chat."); }).catch(fail);
          } })]),
          el("p", { class: "hint", text: "Then come back and press Validate now (or run legacy-audit validate)." })]));
      } else {
        host.appendChild(el("p", { class: "muted", text: "Every stage is present and up to date." }));
      }
      host.appendChild(el("h2", { text: "Stages" }));
      host.appendChild(table([
        { key: "number", label: "#", num: true }, { key: "title", label: "Stage" }, { key: "file", label: "File", code: true },
        { label: "State", value: function (s) { return el("span", { class: "state " + s.state.replace(/\s+/g, "-"), text: s.state }); } },
        { label: "Why", value: function (s) { return s.why.join(", "); } }
      ], d.stages));
      if (d.messages && d.messages.length) {
        host.appendChild(el("h2", { text: "Validation messages" }));
        host.appendChild(table([{ key: "level", label: "Level" }, { key: "stage", label: "Stage" }, { key: "where", label: "Where", code: true }, { key: "text", label: "Message" }], d.messages));
      }
    }
    api("GET", "api/runs/" + run + "/enrichment").then(draw).catch(fail);
  }

  // The PowerPoint template picked on the Reports page: remembered in this browser, else the
  // one audit.yaml names (branding.pptx_template), else the first in templates/.
  var TPL_KEY = "legacy-audit.pptx-template";
  var templateChoice = null;
  function chosenTemplate() { return templateChoice; }
  function drawTemplates(host) {
    api("GET", "api/pptx-templates").then(function (d) {
      if (!d.templates.length) {
        host.appendChild(el("p", { class: "hint", text: "No template found: put the corporate .potx files in the templates folder. The PowerPoint is skipped until then." }));
        return;
      }
      var saved = null;
      try { saved = localStorage.getItem(TPL_KEY); } catch (e) { saved = null; }
      var ids = d.templates.map(function (t) { return t.id; });
      templateChoice = ids.indexOf(saved) >= 0 ? saved : (d["default"] || ids[0]);
      var grid = el("div", { class: "tpl-grid", role: "radiogroup", "aria-label": "PowerPoint template" });
      d.templates.forEach(function (t) {
        var input = el("input", { type: "radio", name: "pptx-template", value: t.id, checked: t.id === templateChoice,
          onchange: function () { templateChoice = t.id; try { localStorage.setItem(TPL_KEY, t.id); } catch (e) { /* private window */ } } });
        grid.appendChild(el("label", { class: "tpl", title: t.file }, [
          el("img", { src: t.thumbnail, alt: t.label + " template preview", loading: "lazy" }),
          el("span", null, [input, " " + t.label])
        ]));
      });
      host.appendChild(grid);
      host.appendChild(el("p", { class: "hint", text: "Every slide of the PowerPoint uses this template's own layouts, colours and fonts. Your choice is remembered in this browser." }));
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 8. reports
  function screenReports(node) {
    add(node, heading("Reports", "Built from the selected run into its reports/ folder. The PowerPoint is built on the corporate template you pick below. PDFs are printed by " + (hosted() ? "Chromium on the server." : "Microsoft Edge or Chrome on this computer.")));
    if (needConfig(node) || needRun(node)) return;
    var run = currentRun();
    var running = jobActive(state.job);
    node.appendChild(el("div", { class: "controls" }, [el("label", null, ["Run ", runSelect(run, function (v) { setRunInHash("reports", v); })])]));
    var tplHost = el("fieldset", { class: "templates" }, [el("legend", { text: "PowerPoint template" })]);
    node.appendChild(tplHost);
    node.appendChild(el("div", { class: "row" }, [["tech", "Build technical report"], ["functional", "Build functional document"], ["mgmt", "Build management deck"], ["pptx", "Build PowerPoint"], ["summary", "Build summary report"], ["pdf", "Build PDFs"], ["all", "Build all"]].filter(function (b) { return b[0] === "all" || reportAllowed(b[0]); }).map(function (b) {
      return el("button", { class: "btn" + (b[0] === "all" ? " primary" : ""), text: b[1], disabled: running, onclick: function () { startJob(api("POST", "api/runs/" + run + "/reports", { type: b[0], template: chosenTemplate() })); } });
    })));
    drawTemplates(tplHost);
    var host = el("div");
    node.appendChild(host);
    jobListeners = [function (ev) { if (ev.type === "end" && ev.result && ev.result.messages) ev.result.messages.forEach(function (m) { toast(m, !/written/.test(m)); }); }];
    api("GET", "api/runs/" + run + "/reports").then(function (d) {
      var names = { tech: "Technical report (HTML)", functional: "Functional document (HTML)", mgmt: "Management deck (HTML)", pptx: "PowerPoint (PPTX)" + (d.pptx_template ? ", " + d.pptx_template + " template" : ""), summary: "Summary report (HTML)", summary_pptx: "Summary PowerPoint (PPTX)",
        pdf_summary: "Summary report (PDF)", pdf_mgmt: "Management deck (PDF)", pdf_functional: "Functional document (PDF)", pdf_tech: "Technical report (PDF)" };
      host.appendChild(table([
        { label: "Report", value: function (r) { return names[r.k]; } },
        { label: "", value: function (r) { return r.url ? el("a", { class: "btn small", href: r.url, target: "_blank", rel: "noopener", text: /pptx$/.test(r.k) ? "Download" : "Open" }) : el("span", { class: "muted", text: "not built yet" }); } }
      ], Object.keys(names).filter(function (k) { return k in d.reports; }).map(function (k) { return { k: k, url: d.reports[k] }; })));
      host.appendChild(el("div", { class: "row" }, [
        reportAllowed("datapack") ? el("a", { class: "btn", href: "api/runs/" + run + "/datapack.zip", text: "Download data pack (ZIP)" }) : null,
        hosted() ? null : el("button", { class: "btn", text: "Open run folder", onclick: function () { api("POST", "api/runs/" + run + "/open").catch(fail); } })
      ]));
      host.appendChild(el("p", { class: "hint", text: "The data pack holds run.json, every fact file (JSON, CSV, SQLite), the SBOM and the enrichment." }));
    }).catch(fail);
  }

  // ---------------------------------------------------------------- 9. compare
  function screenCompare(node) {
    add(node, heading("Compare runs", "What changed between two runs: counts and added, removed or changed records per kind."));
    if (needConfig(node) || needRun(node)) return;
    if (state.runs.length < 2) { node.appendChild(el("div", { class: "empty", text: "Compare needs two runs; re-run the scan once more." })); return; }
    var params = new URLSearchParams((location.hash.split("?")[1] || ""));
    var a = params.get("a") || state.runs[1].run_id, b = params.get("b") || state.runs[0].run_id;
    var host = el("div");
    function go(na, nb) { location.hash = "#compare?a=" + na + "&b=" + nb; }
    node.appendChild(el("div", { class: "controls" }, [el("label", null, ["Before ", runSelect(a, function (v) { go(v, b); }, "Before")]),
      el("label", null, ["After ", runSelect(b, function (v) { go(a, v); }, "After")])]));
    node.appendChild(host);
    api("GET", "api/compare?a=" + a + "&b=" + b).then(function (d) {
      var rows = Object.keys(d.entities).map(function (k) { return Object.assign({ entity: k }, d.entities[k]); })
        .filter(function (r) { return r.count_a || r.count_b; });
      var changedOnly = rows.filter(function (r) { return r.added_count || r.removed_count || r.changed_count; });
      host.appendChild(el("p", { class: "lead", text: changedOnly.length ? changedOnly.length + " kind(s) changed." : "No differences." }));
      host.appendChild(table([
        { key: "entity", label: "Kind" }, { key: "count_a", label: "Before", num: true }, { key: "count_b", label: "After", num: true },
        { key: "added_count", label: "Added", num: true }, { key: "removed_count", label: "Removed", num: true }, { key: "changed_count", label: "Changed", num: true }
      ], rows));
      changedOnly.forEach(function (r) {
        var items = r.added.map(function (x) { return "+ " + x.label; })
          .concat(r.removed.map(function (x) { return "− " + x.label; }))
          .concat(r.changed.map(function (x) { return "~ " + x.label + " (" + Object.keys(x.fields).join(", ") + ")"; }));
        host.appendChild(el("details", null, [el("summary", { text: r.entity.replace(/_/g, " ") + ": " + (r.added_count + r.removed_count + r.changed_count) + " change(s)" }),
          el("ul", { class: "compact" }, items.map(function (t) { return el("li", { class: "code", text: t }); }))]));
      });
    }).catch(fail);
  }

  // ---------------------------------------------------------------- routing
  var SCREENS = { setup: screenSetup, inputs: screenInputs, components: screenComponents, run: screenRun,
    dashboard: screenDashboard, explorer: screenExplorer, enrichment: screenEnrichment, reports: screenReports, compare: screenCompare };
  function route() {
    destroyCharts();
    var name = (location.hash.slice(1).split("?")[0]) || (state.ws && state.ws.config ? (state.runs.length ? "dashboard" : "run") : "setup");
    var fn = SCREENS[name] || screenSetup;
    if (name !== "run" && name !== "reports") jobListeners = [];
    Array.prototype.forEach.call(document.querySelectorAll("#nav a"), function (a) {
      a.classList.toggle("current", a.getAttribute("data-screen") === name);
      if (a.getAttribute("data-screen") === name) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    });
    clear(screenEl);
    fn(screenEl);
  }
  window.addEventListener("hashchange", route);

  // theme toggle (same storage key as the reports)
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
      route();
    });
  })();

  refreshWorkspace().then(function () {
    return api("GET", "api/jobs/current").then(function (job) { if (jobActive(job)) watchJob(job); });
  }).then(route).catch(fail);
})();
