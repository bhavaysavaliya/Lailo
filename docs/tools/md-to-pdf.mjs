// Usage: npm run pdf  (from docs/tools, after npm install) - or: node md-to-pdf.mjs <in.md> <out.pdf> <dir with node_modules> <playwright index.mjs>
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import path from "node:path";

const [input, output, libDir, playwrightPath] = process.argv.slice(2);
const { marked } = await import(pathToFileURL(path.join(libDir, "node_modules/marked/lib/marked.esm.js")).href);
const { chromium } = await import(pathToFileURL(playwrightPath).href);
const mermaidJs = path.join(libDir, "node_modules/mermaid/dist/mermaid.min.js");

const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
marked.use({
  renderer: {
    code({ text, lang }) {
      if (lang === "mermaid") return `<div class="diagram"><pre class="mermaid">${escape(text)}</pre></div>`;
      return `<pre class="code"><code>${escape(text)}</code></pre>`;
    },
  },
});

const body = marked.parse(readFileSync(input, "utf8"));
const html = `<!doctype html><html><head><meta charset="utf-8"><title>Lailo — Architecture</title>
<style>
  @page { size: A4; margin: 18mm 16mm 20mm 16mm; }
  :root { --ink:#1d1a24; --muted:#6b6577; --line:#e2dee9; --jamun:#4e2a84; --soft:#f6f4f9; }
  body { font-family: -apple-system, "Helvetica Neue", Arial, sans-serif; color: var(--ink); font-size: 10.5pt; line-height: 1.5; }
  h1 { font-size: 24pt; color: var(--jamun); margin: 0 0 4pt; letter-spacing: -0.01em; }
  h2 { font-size: 15pt; color: var(--jamun); margin: 22pt 0 6pt; padding-bottom: 3pt; border-bottom: 1px solid var(--line); break-after: avoid; }
  h3 { font-size: 12pt; margin: 14pt 0 4pt; break-after: avoid; }
  p, li { orphans: 3; widows: 3; }
  hr { border: 0; border-top: 1px solid var(--line); margin: 14pt 0; }
  blockquote { color: var(--muted); border-left: 3px solid var(--line); margin: 8pt 0; padding: 2pt 10pt; }
  code { font-family: "SF Mono", Menlo, monospace; font-size: 8.8pt; background: var(--soft); padding: 1px 4px; border-radius: 3px; }
  pre.code { background: var(--soft); border: 1px solid var(--line); border-radius: 6px; padding: 8pt 10pt; font-size: 8.5pt; overflow: hidden; white-space: pre-wrap; break-inside: avoid; }
  pre.code code { background: none; padding: 0; }
  table { width: 100%; border-collapse: collapse; margin: 8pt 0; font-size: 9pt; break-inside: auto; }
  tr { break-inside: avoid; }
  th, td { text-align: left; vertical-align: top; border-bottom: 1px solid var(--line); padding: 4pt 6pt; }
  th { background: var(--soft); font-weight: 600; }
  .diagram { break-inside: avoid; margin: 10pt 0; text-align: center; }
  .diagram svg { max-width: 100% !important; height: auto; max-height: 150mm; width: auto; }
  pre.mermaid { background: none; border: 0; }
  a { color: var(--jamun); text-decoration: none; }
</style></head><body>${body}</body></html>`;

const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent(html, { waitUntil: "load" });
await page.addScriptTag({ path: mermaidJs });
const result = await page.evaluate(async () => {
  window.mermaid.initialize({ startOnLoad: false, theme: "neutral", securityLevel: "strict",
    flowchart: { htmlLabels: true, useMaxWidth: true }, sequence: { useMaxWidth: true, mirrorActors: false, wrap: true, width: 120, actorMargin: 40, messageFontSize: 15, actorFontSize: 15, noteFontSize: 14, boxMargin: 6 }, er: { useMaxWidth: true } });
  const nodes = [...document.querySelectorAll("pre.mermaid")];
  const errors = [];
  for (const [i, n] of nodes.entries()) {
    try { await window.mermaid.run({ nodes: [n] }); } catch (e) { errors.push(`diagram ${i + 1}: ${e.message ?? e}`); }
  }
  return { total: nodes.length, rendered: document.querySelectorAll("pre.mermaid svg").length, errors };
});
console.log(JSON.stringify(result));
await page.pdf({
  path: output, format: "A4", printBackground: true, preferCSSPageSize: true,
  displayHeaderFooter: true,
  headerTemplate: "<div></div>",
  footerTemplate: `<div style="width:100%;font-size:8px;color:#6b6577;padding:0 16mm;display:flex;justify-content:space-between;font-family:Arial">
    <span>Lailo — Architecture</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>`,
});
await browser.close();
