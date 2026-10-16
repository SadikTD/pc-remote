import { appWindow, h } from "./api";

const icon = (d: string) => {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("viewBox", "0 0 10 10");
  s.innerHTML = `<path d="${d}" stroke="currentColor" stroke-width="1" fill="none"/>`;
  return s;
};

export function titlebar(): HTMLElement {
  return h(
    "header.titlebar",
    { "data-tauri-drag-region": "" },
    h("span.brand", { "data-tauri-drag-region": "" }, "PC Remote"),
    h("button.tb", { title: "Minimize", "aria-label": "Minimize", onclick: () => appWindow().minimize() }, icon("M1 5.5h8")),
    h("button.tb.close", { title: "Close to tray", "aria-label": "Close", onclick: () => appWindow().close() }, icon("M1.5 1.5l7 7M8.5 1.5l-7 7")),
  );
}
