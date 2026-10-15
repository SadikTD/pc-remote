import { h, invoke, type State } from "../api";

const TTL = 120;

function status(s: State): [string, string] {
  if (!s.sunshine) return ["warn", "Starting screen sharing"];
  const n = s.devices.filter((d) => d.connected).length;
  if (n === 1) return ["live", "Remote connected"];
  if (n > 1) return ["live", `${n} phones connected`];
  return ["ok", "Ready"];
}

export function home(state: State): HTMLElement {
  const [tone, text] = status(state);
  const qr = h("div.qr", { "aria-label": "Pairing code" });
  const left = h("span.countdown");
  // The ring drains over the code's 120 s life, then a fresh code replaces it.
  const ring = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  ring.setAttribute("class", "ring");
  ring.setAttribute("viewBox", "0 0 260 260");
  ring.innerHTML =
    '<defs><linearGradient id="rg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7C8CFF"/><stop offset="1" stop-color="#4FD1FF"/></linearGradient></defs>' +
    '<rect class="track" x="3" y="3" width="254" height="254" rx="30" pathLength="1"/>' +
    '<rect class="bar" x="3" y="3" width="254" height="254" rx="30" pathLength="1"/>';

  let timer = 0;
  let expires = 0;
  const tick = () => {
    const s = Math.max(0, Math.round((expires - Date.now()) / 1000));
    left.textContent = `New code in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    if (s === 0) refresh();
  };
  const refresh = async () => {
    qr.innerHTML = await invoke<string>("new_qr");
    expires = Date.now() + TTL * 1000;
    const bar = ring.querySelector<SVGElement>(".bar")!;
    bar.style.animation = "none";
    void bar.getBoundingClientRect();
    bar.style.animation = "";
    tick();
  };
  clearInterval(timer);
  timer = window.setInterval(() => (document.contains(qr) ? tick() : clearInterval(timer)), 1000);
  refresh();

  const connected = state.devices.filter((d) => d.connected);
  return h(
    "section.view.home",
    {},
    h("h1.pc", {}, state.name),
    h(`span.pill.${tone}`, {}, h("i.dot"), text),
    h("div.code", {}, ring, qr),
    h("p.lead", {}, "Open PC Remote on your phone and scan this code."),
    left,
    connected.length
      ? h("ul.list.compact", {}, ...connected.map((d) => h("li.row", {}, h("i.dot.live"), h("span.grow", {}, d.name),
          h("button.ghost", { onclick: () => invoke("send_file", { device: d.id }) }, "Send file"))))
      : null,
  );
}
