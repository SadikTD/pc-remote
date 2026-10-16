import { h, invoke, type State } from "../api";

const TTL = 120;

function status(s: State): [string, string] {
  const n = s.devices.filter((d) => d.connected).length;
  if (s.locked && n) return ["warn", "Locked, phone connected"];
  if (n === 1) return ["live", "Phone connected"];
  if (n > 1) return ["live", `${n} phones connected`];
  return ["ok", "Ready for your phone"];
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
    '<rect class="track" x="3" y="3" width="254" height="254" rx="34" pathLength="1"/>' +
    '<rect class="bar" x="3" y="3" width="254" height="254" rx="34" pathLength="1"/>';

  let link = "";
  const copy = h("button.ghost", {
    onclick: async () => {
      await navigator.clipboard.writeText(link);
      copy.textContent = "Copied. Send it to your phone";
      setTimeout(() => (copy.textContent = "Copy pairing link"), 2500);
    },
  }, "Copy pairing link");
  let timer = 0;
  let expires = 0;
  const tick = () => {
    const s = Math.max(0, Math.round((expires - Date.now()) / 1000));
    left.textContent = `New code in ${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
    if (s === 0) refresh();
  };
  const refresh = async () => {
    const code = await invoke<{ svg: string; link: string }>("new_qr");
    qr.innerHTML = code.svg;
    link = code.link;
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
  const send = (id: string, folder: boolean) => invoke("send_file", { device: id, folder });
  return h(
    "section.view.home",
    {},
    h("div.hero", {},
      h(`span.pill.${tone}`, {}, h("i.dot"), text),
      h("h1.pc", {}, state.name)),
    h("div.code", {}, ring, qr),
    h("p.lead", {}, "Scan with PC Remote on your phone, or with its camera."),
    h("div.under", {}, left, copy),
    connected.length
      ? h("ul.list.compact", {}, ...connected.map((d) => h("li.row", {}, h("i.dot.live"), h("span.grow.label", {}, d.name),
          h("button.ghost", { onclick: () => send(d.id, false) }, "Send a file"),
          h("button.ghost", { onclick: () => send(d.id, true) }, "Send folder"))))
      : null,
  );
}
