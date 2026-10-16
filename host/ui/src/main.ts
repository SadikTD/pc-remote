import { h, invoke, listen, type State } from "./api";
import { titlebar } from "./titlebar";
import { home } from "./views/home";
import { devices } from "./views/devices";
import { settings } from "./views/settings";
import { about } from "./views/about";

type Tab = "Home" | "Phones" | "Settings" | "About";
const tabs: Tab[] = ["Home", "Phones", "Settings", "About"];
let current: Tab = "Home";

const main = h("main");
const indicator = h("span.indicator");
const nav = h("nav.tabs", { role: "tablist" }, indicator, ...tabs.map((t) =>
  h("button.tab", { role: "tab", onclick: () => show(t) }, t)));

/** Shown instead of a view while the background service can't be reached. */
const down = () => h("section.view.down", {},
  h("div.down-mark", { "aria-hidden": "true" }),
  h("h2", {}, "The PC Remote service isn't running"),
  h("p.lead", {}, "It starts with Windows and keeps your phone connected. It should be back in a few seconds. If it isn't, reinstall PC Remote."));

async function show(t: Tab) {
  current = t;
  const i = tabs.indexOf(t);
  indicator.style.transform = `translateX(${i * 100}%)`;
  nav.querySelectorAll(".tab").forEach((b, j) => b.setAttribute("aria-selected", String(i === j)));
  let view: HTMLElement;
  try {
    view =
      t === "Settings" ? await settings()
      : t === "About" ? about()
      : await (async () => {
        const s = await invoke<State>("get_state");
        return t === "Home" ? home(s) : devices(s, () => show("Phones"));
      })();
  } catch {
    view = down();
  }
  if (current === t) main.replaceChildren(view);
}

document.getElementById("app")!.append(titlebar(), nav, main);
show("Home");

// The server pushes these on connect/disconnect, lock changes, and when a code was used.
listen("devices", () => (current === "Home" || current === "Phones") && show(current));
listen("paired", () => show(current === "Phones" ? "Phones" : "Home"));
listen<{ up: boolean }>("server", () => show(current));
