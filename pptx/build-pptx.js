#!/usr/bin/env node
/*
 * Build the editable management PPTX from deck.json.
 *
 *   node build-pptx.js <deck.json> <out.pptx>
 *
 * Called by `legacy-audit report --type pptx`. Charts are native PowerPoint charts (bar,
 * doughnut, line) so they stay editable; tables are native tables; diagrams are PNG files
 * rendered from Mermaid when mermaid-cli was available, else a note (the Mermaid source is in
 * the speaker notes). Every number comes from deck.json, which comes from the scan.
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
    slide.background = { color: "FFFFFF" };
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
      const w = (W - 2 * MX - GAP * (n - 1)) / n;
      rest.forEach((b, i) => {
        const box = { x: MX + i * (w + GAP), y, w, h: BODY_BOTTOM - y };
        drawBlock(pptx, slide, b, box, pal, font, color, baseDir);
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

main().catch((err) => { console.error(err && err.stack ? err.stack : err); process.exit(1); });
