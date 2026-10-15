import { h, invoke, listen, type State } from "./api";
import { titlebar } from "./titlebar";
import { home } from "./views/home";
import { devices } from "./views/devices";
import { settings } from "./views/settings";
import { about } from "./views/about";

type Tab = "Home" | "Devices" | "Settings" | "About";
const tabs: Tab[] = ["Home", "Devices", "Settings", "About"];
let current: Tab = "Home";

const main = h("main");
const indicator = h("span.indicator");
const nav = h("nav.tabs", { role: "tablist" }, indicator, ...tabs.map((t) =>
  h("button.tab", { role: "tab", onclick: () => show(t) }, t)));

async function show(t: Tab) {
  current = t;
  const i = tabs.indexOf(t);
  indicator.style.transform = `translateX(${i * 100}%)`;
  nav.querySelectorAll(".tab").forEach((b, j) => b.setAttribute("aria-selected", String(i === j)));
  const view =
    t === "Settings" ? await settings()
    : t === "About" ? about()
    : await (async () => {
      const s = await invoke<State>("get_state");
      return t === "Home" ? home(s) : devices(s, () => show("Devices"));
    })();
  if (current === t) main.replaceChildren(view);
}

document.getElementById("app")!.append(titlebar(), nav, main);
show("Home");

// The host pushes these on connect/disconnect and when a code was used.
listen("devices", () => (current === "Home" || current === "Devices") && show(current));
listen("paired", () => show(current === "Devices" ? "Devices" : "Home"));
