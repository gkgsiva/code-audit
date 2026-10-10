#!/usr/bin/env node
/*
 * Build the editable management PPTX from deck.json.
 *
 *   node build-pptx.js <deck.json> <out.pptx>
 *
 * Called by `legacy-audit report --type pptx`. Charts are native PowerPoint charts (bar,
 * doughnut, line) so they stay editable; tables and heat maps are native tables; gauges are
 * doughnut charts; flows, capability maps, layers and the roadmap are native shapes; other
 * diagrams are PNG files rendered from Mermaid when mermaid-cli was available, else a note (the
 * Mermaid source is in the speaker notes). Every number comes from deck.json, which comes from
 * the scan.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const PptxGenJS = require("pptxgenjs");

const W = 13.333, H = 7.5;
const MX = 0.6, BODY_TOP = 1.55, BODY_BOTTOM = 6.85, GAP = 0.3;

function fmt(v) {
  if (typeof v === "number") {
    return Number.isInteger(v) ? v.toLocaleString("en-US") : String(Math.round(v * 10) / 10);
  }
  return v === null || v === undefined ? "" : String(v);
}

function main() {
  const [deckPath, outPath] = process.argv.slice(2);
  if (!deckPath || !outPath) {
    console.error("usage: node build-pptx.js <deck.json> <out.pptx>");
    process.exit(2);
  }
  const deck = JSON.parse(fs.readFileSync(deckPath, "utf8"));
  const baseDir = path.dirname(path.resolve(deckPath));
  const pal = deck.palette;
  const brand = deck.branding || {};
  const brandColor = /^#?[0-9a-fA-F]{6}$/.test(brand.primary_color || "")
    ? brand.primary_color.replace("#", "") : "1F4E79";
  const font = (brand.font || "Segoe UI").split(",")[0].replace(/["']/g, "").trim() || "Segoe UI";
  const color = (token) => pal[token] || String(token).replace("#", "");

  const pptx = new PptxGenJS();
  pptx.layout = "LAYOUT_WIDE";
  pptx.author = "legacy-audit";
  pptx.company = "";
  pptx.title = `${deck.application}: legacy audit`;
  pptx.theme = { headFontFace: font, bodyFontFace: font };

  const total = deck.slides.length;
  deck.slides.forEach((s, idx) => {
    const slide = pptx.addSlide();
    if (s.type === "section") {
      drawSection(slide, s, idx, total, deck, color, font);
      return;
    }
    slide.background = { color: "FFFFFF" };
    if (s.color) {
      slide.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: W, h: 0.09, fill: { color: color(s.color) },
        line: { color: color(s.color) } });
    }
    const isTitle = s.type === "title";
    const top = isTitle ? 2.2 : 0.35;
    slide.addShape(pptx.ShapeType.rect, { x: MX - 0.2, y: top + 0.05, w: 0.08, h: isTitle ? 1.3 : 0.95,
      fill: { color: brandColor }, line: { color: brandColor } });
    slide.addText(`${s.section.toUpperCase()} · FOR ${s.audience.toUpperCase()}`, {
      x: MX, y: top, w: W - 2 * MX, h: 0.3, fontSize: 10, color: pal.muted, fontFace: font });
    slide.addText(s.title, { x: MX, y: top + 0.28, w: W - 2 * MX, h: isTitle ? 0.9 : 0.7,
      fontSize: isTitle ? 36 : 26, bold: true, color: pal.text, fontFace: font, valign: "top" });
    if (s.subtitle) {
      slide.addText(s.subtitle, { x: MX, y: top + (isTitle ? 1.15 : 0.95), w: W - 2 * MX, h: 0.35,
        fontSize: 14, color: pal.muted, fontFace: font });
    }
    if (brand.logo_file) {
      try { slide.addImage({ path: brand.logo_file, x: W - MX - 1.4, y: 0.3, w: 1.4, h: 0.5,
        sizing: { type: "contain", w: 1.4, h: 0.5 } }); } catch (e) { /* missing logo */ }
    }
    // footer
    const foot = `${deck.application} · run ${deck.run_id}` +
      (s.stale && s.stale.length ? `   ⚠ Enrichment out of date: ${s.stale.join(", ")}` : "");
    slide.addText(foot, { x: MX, y: H - 0.45, w: W - 2 * MX - 1, h: 0.3, fontSize: 9,
      color: pal.muted, fontFace: font });
    slide.addText(`${idx + 1} / ${total}`, { x: W - MX - 1, y: H - 0.45, w: 1, h: 0.3, fontSize: 9,
      color: pal.muted, align: "right", fontFace: font });

    // body layout: KPI rows full width, then the other blocks side by side
    let y = isTitle ? 4.1 : BODY_TOP;
    const kpis = s.blocks.filter((b) => b.type === "kpis");
    const rest = s.blocks.filter((b) => b.type !== "kpis");
    kpis.forEach((b) => {
      drawKpis(pptx, slide, b, { x: MX, y, w: W - 2 * MX, h: 1.0 }, pal, font);
      y += b.note ? 1.35 : 1.15;
    });
    if (rest.length) {
      const n = rest.length;
      const share = s.layout === "wide-left" && n === 2 ? [1.7, 1] : rest.map(() => 1);
      const unit = (W - 2 * MX - GAP * (n - 1)) / share.reduce((a, v) => a + v, 0);
      let x = MX;
      rest.forEach((b, i) => {
        const box = { x, y, w: unit * share[i], h: BODY_BOTTOM - y };
        drawBlock(pptx, slide, b, box, pal, font, color, baseDir);
        x += unit * share[i] + GAP;
      });
    }
    const notes = [s.notes || ""];
    s.blocks.filter((b) => b.type === "diagram" && b.mermaid && !b.png).forEach((b) => {
      notes.push(`Diagram "${b.title}" (Mermaid source):\n${b.mermaid}`);
    });
    if (s.stale && s.stale.length) notes.push(`Out-of-date enrichment: ${s.stale.join(", ")}.`);
    slide.addNotes(notes.filter(Boolean).join("\n\n") || " ");
  });
  return pptx.writeFile({ fileName: outPath }).then(() => console.log(`wrote ${outPath}`));
}

function drawKpis(pptx, slide, b, box, pal, font) {
  const items = b.items;
  const gap = 0.15;
  const w = (box.w - gap * (items.length - 1)) / items.length;
  items.forEach((k, i) => {
    const x = box.x + i * (w + gap);
    slide.addShape(pptx.ShapeType.rect, { x, y: box.y, w, h: box.h, fill: { color: "F3F2EE" },
      line: { color: "F3F2EE" } });
    slide.addText(fmt(k.value), { x: x + 0.1, y: box.y + 0.05, w: w - 0.2, h: 0.55, fontSize: 24,
      bold: true, color: pal.text, fontFace: font });
    slide.addText(k.label + (k.unit ? ` (${k.unit})` : ""), { x: x + 0.1, y: box.y + 0.58,
      w: w - 0.2, h: 0.35, fontSize: 10, color: pal.muted, fontFace: font });
  });
  if (b.note) {
    slide.addText(b.note, { x: box.x, y: box.y + box.h + 0.03, w: box.w, h: 0.25, fontSize: 9,
      color: pal.muted, fontFace: font });
  }
}

// Mix a hex colour with white: t = 1 keeps the colour, t = 0 is white.
function tint(hex, t) {
  const n = parseInt(hex, 16);
  const ch = [n >> 16, (n >> 8) & 255, n & 255].map((c) => Math.round(c * t + 255 * (1 - t)));
  return ch.map((c) => c.toString(16).padStart(2, "0")).join("").toUpperCase();
}

// A section divider: the section's colour edge to edge, title in white.
function drawSection(slide, s, idx, total, deck, color, font) {
  const c = color(s.color || "accent");
  slide.background = { color: c };
  slide.addShape("rect", { x: MX - 0.2, y: 2.75, w: 0.08, h: 1.6, fill: { color: "FFFFFF" },
    line: { color: "FFFFFF" } });
  slide.addText(`${s.section.toUpperCase()} · FOR ${s.audience.toUpperCase()}`, { x: MX, y: 2.7,
    w: W - 2 * MX, h: 0.35, fontSize: 12, color: "FFFFFF", fontFace: font });
  slide.addText(s.title, { x: MX, y: 3.05, w: W - 2 * MX, h: 0.9, fontSize: 40, bold: true,
    color: "FFFFFF", fontFace: font });
  if (s.subtitle) {
    slide.addText(s.subtitle, { x: MX, y: 3.95, w: W - 2 * MX, h: 0.4, fontSize: 16,
      color: "FFFFFF", fontFace: font });
  }
  slide.addText(`${deck.application} · run ${deck.run_id}`, { x: MX, y: H - 0.45, w: W - 2 * MX - 1,
    h: 0.3, fontSize: 9, color: "FFFFFF", fontFace: font });
  slide.addText(`${idx + 1} / ${total}`, { x: W - MX - 1, y: H - 0.45, w: 1, h: 0.3, fontSize: 9,
    color: "FFFFFF", align: "right", fontFace: font });
  slide.addNotes(s.notes || " ");
}

function heading(slide, text, box, pal, font) {
  if (!text) return 0;
  slide.addText(text, { x: box.x, y: box.y, w: box.w, h: 0.35, fontSize: 13, bold: true,
    color: "52514E", fontFace: font });
  return 0.4;
}

function drawBlock(pptx, slide, b, box, pal, font, color, baseDir) {
  const t = b.type;
  if (t === "bullets") {
    const dy = heading(slide, b.title, box, pal, font);
    const items = (b.items || []).length ? b.items : ["None."];
    slide.addText(items.map((it) => ({ text: String(it), options: { bullet: true, breakLine: true } })),
      { x: box.x, y: box.y + dy, w: box.w, h: box.h - dy, fontSize: 14, color: pal.text,
        fontFace: font, valign: "top", paraSpaceAfter: 6, fit: "shrink" });
  } else if (t === "text") {
    slide.addText(b.text, { x: box.x, y: box.y, w: box.w, h: box.h, fontSize: 18, color: "52514E",
      fontFace: font, valign: "top", fit: "shrink" });
  } else if (t === "placeholder") {
    slide.addShape(pptx.ShapeType.rect, { x: box.x, y: box.y, w: box.w, h: Math.min(box.h, 1.6),
      fill: { color: "F3F2EE" }, line: { color: "C3C2B7", width: 1.5, dashType: "dash" } });
    slide.addText([{ text: "Not enriched yet. ", options: { bold: true } }, { text: b.text }],
      { x: box.x + 0.2, y: box.y + 0.1, w: box.w - 0.4, h: Math.min(box.h, 1.6) - 0.2,
        fontSize: 14, color: "52514E", fontFace: font, valign: "middle" });
  } else if (t === "table") {
    const dy = heading(slide, b.title, box, pal, font);
    const head = b.columns.map((c) => ({ text: c, options: { bold: true, color: "52514E",
      fill: { color: "F3F2EE" } } }));
    const rows = b.rows.length ? b.rows : [b.columns.map((_, i) => (i === 0 ? "None." : ""))];
    const body = rows.map((r) => r.map((c) => ({ text: fmt(c) })));
    const size = b.columns.length > 4 || rows.length > 7 ? 9 : 11;
    // column widths follow the text: longer columns get more room
    const weight = b.columns.map((c, i) => Math.sqrt(Math.max(4, String(c).length,
      ...rows.map((r) => fmt(r[i]).length))));
    const sum = weight.reduce((a, v) => a + v, 0);
    const colW = weight.map((v) => (box.w * v) / sum);
    slide.addTable([head, ...body], { x: box.x, y: box.y + dy, w: box.w, fontSize: size,
      fontFace: font, color: pal.text, border: { type: "solid", pt: 0.5, color: pal.grid },
      colW, valign: "top", margin: 0.04, autoPage: false });
    if (b.more) {
      slide.addText(`+${b.more} more in the technical report`, { x: box.x, y: box.y + box.h - 0.3,
        w: box.w, h: 0.3, fontSize: 9, color: pal.muted, fontFace: font });
    }
  } else if (t === "chart") {
    const dy = heading(slide, b.title, box, pal, font);
    if (!b.values.length) {
      slide.addText("Nothing to chart.", { x: box.x, y: box.y + dy, w: box.w, h: 0.4, fontSize: 12,
        color: pal.muted, fontFace: font });
      return;
    }
    const data = [{ name: b.title, labels: b.labels.map(String), values: b.values }];
    const colors = b.colors.map(color);
    const common = { x: box.x, y: box.y + dy, w: box.w, h: Math.min(box.h - dy, 4.6),
      showTitle: false, dataLabelColor: pal.text, dataLabelFontSize: 10, dataLabelFontFace: font,
      catAxisLabelFontSize: 10, valAxisLabelFontSize: 10, catAxisLabelColor: "52514E",
      valAxisLabelColor: "52514E", catAxisLabelFontFace: font, valAxisLabelFontFace: font,
      valGridLine: { color: pal.grid, size: 0.5 }, catGridLine: { style: "none" } };
    if (b.chart === "doughnut") {
      slide.addChart(pptx.ChartType.doughnut, data, Object.assign({}, common, {
        chartColors: colors, holeSize: 55, showLegend: true, legendPos: "r", legendFontSize: 11,
        showValue: true, showPercent: false, dataLabelColor: "FFFFFF" }));
    } else if (b.chart === "line") {
      slide.addChart(pptx.ChartType.line, data, Object.assign({}, common, {
        chartColors: [color("accent")], lineSize: 2, lineDataSymbol: "circle",
        lineDataSymbolSize: 7, showValue: true, dataLabelPosition: "t", showLegend: false,
        valAxisTitle: b.unit, showValAxisTitle: !!b.unit, valAxisTitleFontSize: 10 }));
    } else {
      const uniform = colors.every((c) => c === colors[0]);
      slide.addChart(pptx.ChartType.bar, data, Object.assign({}, common, {
        barDir: "col", chartColors: uniform ? [colors[0]] : colors, showValue: true,
        dataLabelPosition: "outEnd", showLegend: false, barGapWidthPct: 60,
        valAxisTitle: b.unit, showValAxisTitle: !!b.unit, valAxisTitleFontSize: 10 }));
    }
  } else if (t === "gauges") {
    drawGauges(pptx, slide, b, box, pal, font, color);
  } else if (t === "capmap") {
    drawCapmap(pptx, slide, b, box, pal, font, color);
  } else if (t === "heatmap") {
    drawHeatmap(slide, b, box, pal, font);
  } else if (t === "flow") {
    drawFlow(pptx, slide, b, box, pal, font);
  } else if (t === "gantt") {
    drawGantt(pptx, slide, b, box, pal, font, color);
  } else if (t === "layers") {
    drawLayers(pptx, slide, b, box, pal, font, color);
  } else if (t === "diagram") {
    const dy = heading(slide, b.title, box, pal, font);
    const png = b.png ? path.resolve(baseDir, b.png) : null;
    if (png && fs.existsSync(png)) {
      slide.addImage({ path: png, x: box.x, y: box.y + dy, w: box.w, h: box.h - dy,
        sizing: { type: "contain", w: box.w, h: box.h - dy } });
    } else {
      slide.addShape(pptx.ShapeType.rect, { x: box.x, y: box.y + dy, w: box.w, h: 1.2,
        fill: { color: "F3F2EE" }, line: { color: "C3C2B7", width: 1, dashType: "dash" } });
      slide.addText(b.mermaid ? "Diagram not embedded: install @mermaid-js/mermaid-cli (mmdc) and " +
        "rebuild. The Mermaid source is in the speaker notes; the HTML deck shows the diagram."
        : (b.note || "No diagram."), { x: box.x + 0.2, y: box.y + dy + 0.1, w: box.w - 0.4, h: 1.0,
        fontSize: 12, color: "52514E", fontFace: font, valign: "middle" });
    }
  }
}

// Share of a whole as a ring: a native doughnut chart with the percentage in the middle.
function drawGauges(pptx, slide, b, box, pal, font, color) {
  const items = b.items || [];
  const gap = 0.25;
  const w = Math.min(3, (box.w - gap * (items.length - 1)) / Math.max(items.length, 1));
  const left = box.x + (box.w - (w * items.length + gap * (items.length - 1))) / 2;
  const ring = Math.min(w - 0.4, 2.2);
  items.forEach((g, i) => {
    const x = left + i * (w + gap);
    slide.addShape(pptx.ShapeType.roundRect, { x, y: box.y, w, h: ring + 1.3, rectRadius: 0.08,
      fill: { color: "F3F2EE" }, line: { color: "F3F2EE" } });
    const pct = Math.max(0, Math.min(100, g.pct));
    slide.addChart(pptx.ChartType.doughnut, [{ name: g.label, labels: ["Share", "Rest"],
      values: [pct, 100 - pct] }], { x: x + (w - ring) / 2, y: box.y + 0.15, w: ring, h: ring,
      holeSize: 72, chartColors: [color(g.color), "E1E0D9"], showLegend: false, showValue: false,
      showPercent: false, showTitle: false, dataBorder: { pt: 0.5, color: "F3F2EE" } });
    slide.addText(`${pct}%`, { x: x + (w - ring) / 2, y: box.y + 0.15 + ring / 2 - 0.3, w: ring,
      h: 0.6, fontSize: 22, bold: true, align: "center", valign: "middle", color: pal.text,
      fontFace: font });
    slide.addText(g.label, { x: x + 0.1, y: box.y + ring + 0.25, w: w - 0.2, h: 0.55, fontSize: 12,
      bold: true, align: "center", valign: "top", color: pal.text, fontFace: font, fit: "shrink" });
    if (g.sub) {
      slide.addText(g.sub, { x: x + 0.1, y: box.y + ring + 0.8, w: w - 0.2, h: 0.35, fontSize: 10,
        align: "center", color: pal.muted, fontFace: font });
    }
  });
}

// Domains as coloured groups, each capability a tile with the domain's colour on its edge.
function drawCapmap(pptx, slide, b, box, pal, font, color) {
  const domains = b.domains || [];
  const cols = Math.min(Math.max(domains.length, 1), 4);
  const gap = 0.2;
  const w = (box.w - gap * (cols - 1)) / cols;
  const rows = Math.ceil(domains.length / cols) || 1;
  const rowH = (box.h - gap * (rows - 1)) / rows;
  domains.forEach((d, i) => {
    const c = color(d.color);
    const x = box.x + (i % cols) * (w + gap);
    const y = box.y + Math.floor(i / cols) * (rowH + gap);
    const capH = Math.min(0.42, (rowH - 0.6) / Math.max(d.caps.length, 1));
    const h = Math.min(rowH, 0.6 + d.caps.length * (capH + 0.06) + 0.1);
    slide.addShape(pptx.ShapeType.roundRect, { x, y, w, h, rectRadius: 0.08,
      fill: { color: tint(c, 0.14) }, line: { color: tint(c, 0.5), width: 1 } });
    slide.addText(d.name, { x: x + 0.15, y: y + 0.08, w: w - 0.3, h: 0.42, fontSize: 14, bold: true,
      color: pal.text, fontFace: font, fit: "shrink" });
    d.caps.forEach((cap, k) => {
      const cy = y + 0.55 + k * (capH + 0.06);
      slide.addShape(pptx.ShapeType.rect, { x: x + 0.15, y: cy, w: w - 0.3, h: capH,
        fill: { color: "FFFFFF" }, line: { color: "FFFFFF" } });
      slide.addShape(pptx.ShapeType.rect, { x: x + 0.15, y: cy, w: 0.06, h: capH,
        fill: { color: c }, line: { color: c } });
      slide.addText(cap, { x: x + 0.27, y: cy, w: w - 0.45, h: capH, fontSize: 11, color: pal.text,
        fontFace: font, valign: "middle", fit: "shrink" });
    });
  });
}

// A native table whose cell fill shows how much (seq) or how risky (risk); the text stays.
function drawHeatmap(slide, b, box, pal, font) {
  const dy = heading(slide, b.title, box, pal, font);
  const scale = b.scale === "risk" ? "risk" : "seq";
  const head = [{ text: "", options: {} }, ...b.columns.map((c) => ({ text: String(c),
    options: { bold: true, color: "52514E", fill: { color: "FFFFFF" }, align: "center" } }))];
  const body = (b.rows || []).map((r) => [
    { text: String(r.label), options: { bold: true, color: "52514E" } },
    ...r.cells.map((c) => ({ text: String(c.text || ""), options: { align: "center", bold: true,
      fill: { color: pal[`${scale}-${c.level}`] || "F3F2EE" },
      color: scale === "seq" && c.level >= 4 ? "FFFFFF" : pal.text } })),
  ]);
  if (!body.length) body.push([{ text: "None.", options: {} }, ...b.columns.map(() => ({ text: "" }))]);
  const first = Math.min(3, box.w * 0.28);
  const rest = (box.w - first) / Math.max(b.columns.length, 1);
  slide.addTable([head, ...body], { x: box.x, y: box.y + dy, w: box.w, fontSize: 10,
    fontFace: font, color: pal.text, border: { type: "solid", pt: 2, color: "FFFFFF" },
    colW: [first, ...b.columns.map(() => rest)], rowH: 0.34, valign: "middle", margin: 0.04,
    autoPage: false });
  if (b.legend) {
    const ty = Math.min(box.y + dy + 0.34 * (body.length + 1) + 0.1, box.y + box.h - 0.3);
    slide.addText(b.legend, { x: box.x, y: ty, w: box.w, h: 0.3, fontSize: 9, color: pal.muted,
      fontFace: font });
  }
}

// A flow as columns of coloured boxes joined by arrows (all editable shapes).
const FLOW_LEGEND = [["screen", "Screen"], ["service", "Service"], ["table", "Table"],
  ["system", "Other system"], ["trigger", "Trigger"], ["job", "Job"]];
function kindColors(pal, kind) {
  const [fill, stroke] = String(pal[`kind-${kind}`] || pal["kind-muted"] || "F0EFEC/898781")
    .split("/");
  return { fill, stroke: stroke || fill };
}
function drawFlow(pptx, slide, b, box, pal, font) {
  const cols = (b.columns || []).filter((c) => c.nodes.length);
  const top = box.y + 0.35, legendH = 0.3;
  const h = box.h - 0.35 - legendH;
  const colW = box.w / Math.max(cols.length, 1);
  const nodeW = Math.min(2.6, colW * 0.74);
  const pos = {}, boxes = [], labels = [];
  cols.forEach((c, i) => {
    const cx = box.x + i * colW + (colW - nodeW) / 2;
    slide.addText(c.title, { x: box.x + i * colW, y: box.y, w: colW, h: 0.3, fontSize: 10,
      bold: true, color: "52514E", align: "center", fontFace: font });
    const n = c.nodes.length;
    const slot = h / n;
    const nodeH = Math.min(0.62, slot * 0.72);
    c.nodes.forEach((nd, k) => {
      const y = top + k * slot + (slot - nodeH) / 2;
      const kc = kindColors(pal, nd.kind);
      const shape = nd.kind === "table" ? pptx.ShapeType.can
        : nd.kind === "system" || nd.kind === "trigger" ? pptx.ShapeType.roundRect
          : pptx.ShapeType.rect;
      boxes.push([String(nd.label), { shape, x: cx, y, w: nodeW, h: nodeH,
        fill: { color: kc.fill }, line: { color: kc.stroke, width: 1.25 }, rectRadius: 0.12,
        fontSize: nodeH < 0.4 ? 8 : 10, color: pal.text, align: "center", valign: "middle",
        fontFace: font, fit: "shrink", margin: 2 }]);
      if (!(nd.key in pos)) pos[nd.key] = { col: i, x: cx, y, w: nodeW, h: nodeH };
    });
  });
  (b.edges || []).forEach((e) => {
    const a = pos[e.from], z = pos[e.to];
    if (!a || !z || a === z) return;
    let x1, y1, x2, y2;
    if (a.col === z.col) {
      x1 = a.x + a.w * 0.85; x2 = z.x + z.w * 0.85;
      if (z.y > a.y) { y1 = a.y + a.h; y2 = z.y; } else { y1 = a.y; y2 = z.y + z.h; }
    } else if (z.col > a.col) {
      x1 = a.x + a.w; y1 = a.y + a.h / 2; x2 = z.x; y2 = z.y + z.h / 2;
    } else {
      x1 = a.x; y1 = a.y + a.h / 2; x2 = z.x + z.w; y2 = z.y + z.h / 2;
    }
    slide.addShape(pptx.ShapeType.line, { x: Math.min(x1, x2), y: Math.min(y1, y2),
      w: Math.max(Math.abs(x2 - x1), 0.001), h: Math.max(Math.abs(y2 - y1), 0.001),
      flipH: x2 < x1, flipV: y2 < y1,
      line: { color: "898781", width: 1, endArrowType: "triangle" } });
    if (e.label) {
      const lw = Math.min(1.6, 0.12 + String(e.label).length * 0.065);
      const mx = x1 + (x2 - x1) * 0.55, my = y1 + (y2 - y1) * 0.55;
      labels.push([String(e.label), { x: mx - lw / 2, y: my - 0.11, w: lw, h: 0.22, fontSize: 7,
        color: "52514E", align: "center", valign: "middle", fill: { color: "FFFFFF" },
        fontFace: font, margin: 0 }]);
    }
  });
  // arrows first, then the boxes on top of them, then the arrow labels
  boxes.forEach(([text, opts]) => slide.addText(text, opts));
  labels.forEach(([text, opts]) => slide.addText(text, opts));
  let lx = box.x;
  FLOW_LEGEND.forEach(([k, label]) => {
    const kc = kindColors(pal, k);
    slide.addShape(pptx.ShapeType.rect, { x: lx, y: box.y + box.h - 0.22, w: 0.16, h: 0.16,
      fill: { color: kc.fill }, line: { color: kc.stroke, width: 1 } });
    slide.addText(label, { x: lx + 0.2, y: box.y + box.h - 0.27, w: 1.1, h: 0.26, fontSize: 9,
      color: "52514E", fontFace: font, margin: 0 });
    lx += 0.3 + label.length * 0.075;
  });
}

// Plan waves as bars on a month axis.
function drawGantt(pptx, slide, b, box, pal, font, color) {
  const bars = b.bars || [];
  const span = Math.max(b.months || 0, 1);
  const labelW = Math.min(3, box.w * 0.25);
  const tx = box.x + labelW + 0.15, tw = box.w - labelW - 0.15;
  const rowH = Math.min(0.6, (box.h - 0.5) / Math.max(bars.length, 1));
  bars.forEach((g, i) => {
    const y = box.y + i * rowH;
    slide.addText(g.name, { x: box.x, y, w: labelW, h: rowH * 0.85, fontSize: 12, bold: true,
      color: pal.text, fontFace: font, valign: "middle", fit: "shrink" });
    slide.addShape(pptx.ShapeType.roundRect, { x: tx, y: y + rowH * 0.1, w: tw, h: rowH * 0.65,
      rectRadius: 0.06, fill: { color: "F3F2EE" }, line: { color: "F3F2EE" } });
    const c = color(g.color);
    const bw = Math.max(0.05, (tw * (g.end - g.start)) / span);
    slide.addText(`M${g.start} to M${g.end}`, { shape: pptx.ShapeType.roundRect,
      x: tx + (tw * g.start) / span, y: y + rowH * 0.1, w: bw, h: rowH * 0.65, rectRadius: 0.06,
      fill: { color: c }, line: { color: c }, fontSize: 10, color: "FFFFFF", fontFace: font,
      valign: "middle", margin: 4 });
  });
  const step = span <= 12 ? 1 : span <= 36 ? 3 : 6;
  const ay = box.y + bars.length * rowH + 0.05;
  for (let m = 0; m <= span; m += step) {
    const x = tx + (tw * m) / span;
    slide.addText(`M${m}`, { x: x - 0.3, y: ay, w: 0.6, h: 0.25, fontSize: 9, color: pal.muted,
      align: "center", fontFace: font });
  }
}

// Architecture layers as coloured bands: name on the left, components and note on the right.
function drawLayers(pptx, slide, b, box, pal, font, color) {
  const layers = b.layers || [];
  const gap = 0.12;
  const h = Math.min(1.1, (box.h - gap * (layers.length - 1)) / Math.max(layers.length, 1));
  const nameW = Math.min(2.6, box.w * 0.24);
  layers.forEach((l, i) => {
    const y = box.y + i * (h + gap);
    const c = color(l.color);
    slide.addShape(pptx.ShapeType.rect, { x: box.x, y, w: box.w, h, fill: { color: tint(c, 0.14) },
      line: { color: tint(c, 0.5), width: 1 } });
    slide.addText(l.name, { x: box.x, y, w: nameW, h, fill: { color: c }, fontSize: 14, bold: true,
      color: "FFFFFF", fontFace: font, valign: "middle", margin: 8, fit: "shrink" });
    const runs = [{ text: (l.items || []).join("   ·   ") || "(no components)",
      options: { bold: true, color: pal.text, breakLine: !!l.note } }];
    if (l.note) runs.push({ text: l.note, options: { color: "52514E", fontSize: 11 } });
    slide.addText(runs, { x: box.x + nameW + 0.15, y, w: box.w - nameW - 0.25, h, fontSize: 12,
      fontFace: font, valign: "middle", fit: "shrink" });
  });
}

main().catch((err) => { console.error(err && err.stack ? err.stack : err); process.exit(1); });
