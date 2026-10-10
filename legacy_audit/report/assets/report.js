/* legacy-audit technical report: theme, catalogue tables, charts and diagrams.
   Plain JavaScript, no network: data comes from data/*.js files next to the pages. */
(function () {
  "use strict";
  var BASE = document.body.getAttribute("data-base") || "";
  var PAGE_SIZE = 50;

  // ---- theme -------------------------------------------------------------------------------
  function storedTheme() {
    try { return localStorage.getItem("la-theme"); } catch (e) { return null; }
  }
  function applyTheme(t) {
    if (t === "light" || t === "dark") document.documentElement.setAttribute("data-theme", t);
    else document.documentElement.removeAttribute("data-theme");
  }
  function isDark() {
    var t = document.documentElement.getAttribute("data-theme");
    if (t) return t === "dark";
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  }
  applyTheme(storedTheme());
  var toggle = document.getElementById("theme-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      var next = isDark() ? "light" : "dark";
      applyTheme(next);
      try { localStorage.setItem("la-theme", next); } catch (e) { /* private window */ }
      location.reload(); // charts and diagrams re-read the colours
    });
  }
  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  // ---- cells -------------------------------------------------------------------------------
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function href(h, abs) { return abs ? h : BASE + h; }
  function cellText(v) {
    if (v === null || v === undefined) return "";
    if (typeof v === "object") return v.text || "";
    return String(v);
  }
  function renderCell(col, v) {
    var td = el("td", col.type === "num" ? "num" : col.type === "code" ? "code" : "");
    if (col.type === "evidence" && v && typeof v === "object") {
      td.className = "ev";
      var parts = (v.text || "").split("/");
      var shortText = parts.length > 2 ? "\u2026/" + parts.slice(-2).join("/") : v.text;
      td.title = v.text;
      if (v.href) {
        var a = el("a", null, shortText);
        a.href = href(v.href, v.abs);
        td.appendChild(a);
      } else {
        td.appendChild(el("code", null, shortText));
      }
      if (v.vscode) {
        var vs = el("a", null, "VS Code");
        vs.href = v.vscode;
        vs.title = "Open in VS Code at this line";
        td.appendChild(vs);
      }
    } else if (col.type === "link" && v && typeof v === "object") {
      var l = el("a", null, v.text);
      l.href = href(v.href, false);
      td.appendChild(l);
    } else if (col.type === "badge" && v) {
      td.appendChild(el("span", "chip " + String(v).toLowerCase().replace(/\s+/g, "-"), String(v)));
    } else {
      td.textContent = cellText(v);
    }
    return td;
  }

  // ---- catalogue tables --------------------------------------------------------------------
  function Catalogue(host) {
    var name = host.getAttribute("data-catalogue");
    var data = (window.LA_DATA || {})[name];
    if (!data) { host.appendChild(el("p", "muted", "No data for " + name + ".")); return; }
    var cols = data.columns;
    var fixedCol = host.getAttribute("data-filter-col");
    var fixedVal = host.getAttribute("data-filter-value");
    var rows = data.rows;
    if (fixedCol) {
      var fi = cols.findIndex(function (c) { return c.key === fixedCol; });
      var isList = fi >= 0 && cols[fi].type === "list";
      rows = rows.filter(function (r) {
        var v = cellText(r[fi]);
        return isList ? v.split(", ").indexOf(fixedVal) >= 0 : v === fixedVal;
      });
    }
    if (!rows.length) { host.appendChild(el("p", "muted", "None.")); return; }
    var state = { q: "", filters: {}, sort: null, dir: 1, page: 0 };

    var controls = el("div", "controls");
    var search = el("input");
    search.type = "search";
    search.placeholder = "Search " + rows.length + " rows";
    search.setAttribute("aria-label", "Search");
    controls.appendChild(search);
    cols.forEach(function (c, i) {
      if (!c.filter || c.key === fixedCol) return;
      var values = {};
      rows.forEach(function (r) { values[cellText(r[i])] = true; });
      var keys = Object.keys(values).sort();
      if (keys.length < 2 || keys.length > 40) return;
      var label = el("label", null, c.label);
      var sel = el("select");
      sel.appendChild(new Option("all", ""));
      keys.forEach(function (k) { sel.appendChild(new Option(k === "" ? "(none)" : k, k === "" ? "\u0000" : k)); });
      sel.addEventListener("change", function () {
        if (sel.value === "") delete state.filters[i];
        else state.filters[i] = sel.value === "\u0000" ? "" : sel.value;
        state.page = 0; draw();
      });
      label.appendChild(sel);
      controls.appendChild(label);
    });
    var count = el("span", "muted");
    controls.appendChild(count);
    host.appendChild(controls);

    var wrap = el("div", "table-wrap");
    var table = el("table", "data");
    var thead = el("thead");
    var hr = el("tr");
    cols.forEach(function (c, i) {
      var th = el("th", "sortable" + (c.type === "num" ? " num" : ""), c.label);
      th.tabIndex = 0;
      function sortBy() {
        state.dir = state.sort === i ? -state.dir : 1;
        state.sort = i;
        Array.prototype.forEach.call(hr.children, function (h) { h.removeAttribute("aria-sort"); });
        th.setAttribute("aria-sort", state.dir > 0 ? "ascending" : "descending");
        draw();
      }
      th.addEventListener("click", sortBy);
      th.addEventListener("keydown", function (e) { if (e.key === "Enter") sortBy(); });
      hr.appendChild(th);
    });
    thead.appendChild(hr);
    table.appendChild(thead);
    var tbody = el("tbody");
    table.appendChild(tbody);
    wrap.appendChild(table);
    host.appendChild(wrap);

    var pager = el("div", "pager");
    var prev = el("button", null, "Previous");
    var next = el("button", null, "Next");
    var info = el("span");
    pager.appendChild(prev); pager.appendChild(info); pager.appendChild(next);
    host.appendChild(pager);
    prev.addEventListener("click", function () { state.page--; draw(); });
    next.addEventListener("click", function () { state.page++; draw(); });
    var timer = null;
    search.addEventListener("input", function () {
      clearTimeout(timer);
      timer = setTimeout(function () { state.q = search.value.toLowerCase(); state.page = 0; draw(); }, 150);
    });

    // Lower-cased row text for searching, built once.
    var haystack = rows.map(function (r) {
      return r.map(cellText).join(" \u0001 ").toLowerCase();
    });

    function draw() {
      var idx = [];
      for (var k = 0; k < rows.length; k++) {
        if (state.q && haystack[k].indexOf(state.q) < 0) continue;
        var ok = true;
        for (var f in state.filters) {
          if (cellText(rows[k][f]) !== state.filters[f]) { ok = false; break; }
        }
        if (ok) idx.push(k);
      }
      if (state.sort !== null) {
        var s = state.sort, num = cols[s].type === "num";
        idx.sort(function (a, b) {
          var x = rows[a][s], y = rows[b][s];
          if (num) return ((+x || 0) - (+y || 0)) * state.dir;
          return cellText(x).localeCompare(cellText(y), undefined, { numeric: true }) * state.dir;
        });
      }
      var pages = Math.max(1, Math.ceil(idx.length / PAGE_SIZE));
      state.page = Math.min(Math.max(state.page, 0), pages - 1);
      tbody.textContent = "";
      var frag = document.createDocumentFragment();
      idx.slice(state.page * PAGE_SIZE, (state.page + 1) * PAGE_SIZE).forEach(function (k) {
        var tr = el("tr");
        cols.forEach(function (c, i) { tr.appendChild(renderCell(c, rows[k][i])); });
        frag.appendChild(tr);
      });
      tbody.appendChild(frag);
      count.textContent = idx.length === rows.length ? rows.length + " rows" : idx.length + " of " + rows.length + " rows";
      info.textContent = "Page " + (state.page + 1) + " of " + pages;
      prev.disabled = state.page === 0;
      next.disabled = state.page >= pages - 1;
      pager.style.display = pages > 1 ? "" : "none";
    }
    draw();
  }
  Array.prototype.forEach.call(document.querySelectorAll("[data-catalogue]"), Catalogue);

  // ---- charts (Chart.js, vendored) ---------------------------------------------------------
  // Each chart is one series: bars in the accent colour, or in status colours where the bar
  // is a grade or a severity (the axis label always names it, so colour is never alone).
  function drawCharts() {
    if (!window.Chart) return;
    var ink = cssVar("--text-2"), grid = cssVar("--grid"), axis = cssVar("--axis");
    Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", sans-serif';
    Chart.defaults.color = ink;
    Array.prototype.forEach.call(document.querySelectorAll("canvas[data-chart]"), function (canvas) {
      var spec = JSON.parse(document.getElementById(canvas.getAttribute("data-chart")).textContent);
      var colors = spec.colors.map(function (c) { return cssVar("--" + c) || c; });
      var horizontal = spec.horizontal;
      var valueAxis = {
        beginAtZero: true,
        grid: { color: grid },
        border: { color: axis },
        ticks: { precision: 0 },
        title: { display: !!spec.unit, text: spec.unit, color: ink }
      };
      var catAxis = { grid: { display: false }, border: { color: axis } };
      new Chart(canvas, {
        type: "bar",
        data: {
          labels: spec.labels,
          datasets: [{
            label: spec.label,
            data: spec.values,
            backgroundColor: colors,
            borderRadius: 4,
            borderSkipped: "start",
            maxBarThickness: 28,
            categoryPercentage: 0.8,
            barPercentage: 0.9
          }]
        },
        options: {
          indexAxis: horizontal ? "y" : "x",
          maintainAspectRatio: false,
          animation: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: function (c) { return c.formattedValue + (spec.unit ? " " + spec.unit : ""); } } }
          },
          scales: horizontal ? { x: valueAxis, y: catAxis } : { x: catAxis, y: valueAxis }
        }
      });
    });
  }
  drawCharts();

  // ---- diagrams (Mermaid, vendored) --------------------------------------------------------
  if (window.mermaid && document.querySelector("pre.mermaid")) {
    window.mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: isDark() ? "dark" : "neutral",
      flowchart: { htmlLabels: false, useMaxWidth: false },
      maxTextSize: 200000
    });
    window.mermaid.run({ querySelector: "pre.mermaid" });
  }
})();
