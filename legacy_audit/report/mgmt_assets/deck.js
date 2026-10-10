/* legacy-audit management deck: navigation, notes, charts and diagrams. Offline, no network. */
(function () {
  "use strict";
  var slides = Array.prototype.slice.call(document.querySelectorAll(".slide"));
  var pos = document.getElementById("pos");
  var drawn = {};
  var current = 0;

  function storedTheme() { try { return localStorage.getItem("la-deck-theme"); } catch (e) { return null; } }
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
  function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }

  // Direct value labels on bars (few bars only), so a zero bar still reads as 0.
  var valueLabels = {
    id: "valueLabels",
    afterDatasetsDraw: function (c) {
      if (c.config.type !== "bar") return;
      var meta = c.getDatasetMeta(0), data = c.data.datasets[0].data;
      if (data.length > 12) return;
      var ctx = c.ctx;
      ctx.save();
      ctx.fillStyle = cssVar("--text-2");
      ctx.font = "12px " + getComputedStyle(document.body).fontFamily;
      ctx.textAlign = "center";
      ctx.textBaseline = "bottom";
      meta.data.forEach(function (bar, k) { ctx.fillText(String(data[k]), bar.x, bar.y - 4); });
      ctx.restore();
    }
  };

  function chart(canvas) {
    if (!window.Chart) return;
    var spec = JSON.parse(document.getElementById(canvas.getAttribute("data-chart")).textContent);
    var colors = spec.colors.map(function (c) { return cssVar("--" + c) || c; });
    var ink = cssVar("--text-2"), grid = cssVar("--grid"), axis = cssVar("--axis");
    Chart.defaults.color = ink;
    Chart.defaults.font.family = getComputedStyle(document.body).fontFamily;
    var unit = spec.unit ? " " + spec.unit : "";
    var tooltip = { callbacks: { label: function (c) { return c.formattedValue + unit; } } };
    var cfg;
    if (spec.chart === "doughnut") {
      cfg = {
        type: "doughnut",
        data: { labels: spec.labels.map(function (l, k) { return l + " (" + spec.values[k] + ")"; }),
          datasets: [{ data: spec.values, backgroundColor: colors,
          borderColor: cssVar("--surface"), borderWidth: 2 }] },
        options: { maintainAspectRatio: false, animation: false, cutout: "60%",
          plugins: { legend: { position: "right" }, tooltip: tooltip } }
      };
    } else {
      var line = spec.chart === "line";
      cfg = {
        type: line ? "line" : "bar",
        data: { labels: spec.labels, datasets: [{
          label: spec.title, data: spec.values,
          backgroundColor: line ? cssVar("--accent") : colors,
          borderColor: cssVar("--accent"), borderWidth: line ? 2 : 0,
          pointRadius: line ? 4 : 0, borderRadius: line ? 0 : 4, borderSkipped: "start",
          maxBarThickness: 40 }] },
        options: {
          maintainAspectRatio: false, animation: false,
          plugins: { legend: { display: false }, tooltip: tooltip },
          scales: {
            x: { grid: { display: false }, border: { color: axis } },
            y: { beginAtZero: true, grid: { color: grid }, border: { color: axis }, ticks: { precision: 0 },
                 grace: "8%",
                 title: { display: !!spec.unit, text: spec.unit } }
          }
        }
      };
    }
    cfg.plugins = [valueLabels];
    new Chart(canvas, cfg);
  }

  function renderSlide(i) {
    if (drawn[i]) return;
    drawn[i] = true;
    Array.prototype.forEach.call(slides[i].querySelectorAll("canvas[data-chart]"), chart);
    var pre = slides[i].querySelectorAll("pre.mermaid");
    if (window.mermaid && pre.length) window.mermaid.run({ nodes: pre });
  }

  function show(i) {
    current = Math.max(0, Math.min(slides.length - 1, i));
    slides.forEach(function (s, k) { s.hidden = k !== current; });
    pos.textContent = (current + 1) + " / " + slides.length;
    renderSlide(current);
    if (location.hash !== "#" + (current + 1)) history.replaceState(null, "", "#" + (current + 1));
  }

  if (window.mermaid) {
    window.mermaid.initialize({ startOnLoad: false, securityLevel: "strict",
      theme: isDark() ? "dark" : "neutral", flowchart: { htmlLabels: false } });
  }
  document.getElementById("prev").addEventListener("click", function () { show(current - 1); });
  document.getElementById("next").addEventListener("click", function () { show(current + 1); });
  document.getElementById("notes").addEventListener("click", function () { document.body.classList.toggle("show-notes"); });
  document.getElementById("theme").addEventListener("click", function () {
    var next = isDark() ? "light" : "dark";
    try { localStorage.setItem("la-deck-theme", next); } catch (e) { /* private window */ }
    applyTheme(next);
    location.reload();
  });
  document.addEventListener("keydown", function (e) {
    if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
    var k = e.key;
    if (k === "ArrowRight" || k === "PageDown" || k === " ") { show(current + 1); e.preventDefault(); }
    else if (k === "ArrowLeft" || k === "PageUp") { show(current - 1); e.preventDefault(); }
    else if (k === "Home") show(0);
    else if (k === "End") show(slides.length - 1);
    else if (k === "n" || k === "N") document.body.classList.toggle("show-notes");
  });
  // Print: draw every slide first so charts and diagrams are on paper.
  window.addEventListener("beforeprint", function () {
    slides.forEach(function (s) { s.hidden = false; });
    slides.forEach(function (_, i) { renderSlide(i); });
  });
  window.addEventListener("afterprint", function () { show(current); });
  var start = parseInt((location.hash || "#1").slice(1), 10);
  show(isNaN(start) ? 0 : start - 1);
})();
