import { h, invoke, toggle, type Config, type Display } from "../api";

export async function settings(): Promise<HTMLElement> {
  const { config, displays } = await invoke<{ config: Config; displays: Display[] }>("get_config");
  const note = h("p.note", { role: "status" });
  const save = async (patch: Partial<Config>) => {
    const next = { ...config, ...patch };
    try {
      await invoke("set_config", { config: next });
      Object.assign(config, patch);
      note.textContent = "";
    } catch (e) {
      note.textContent = `Couldn't save: ${e}`;
    }
  };
  const select = (label: string, value: string, options: [string, string][], change: (v: string) => void) =>
    h(
      "label.row",
      {},
      h("span.grow.label", {}, label),
      h("select", { onchange: (e: Event) => change((e.target as HTMLSelectElement).value) },
        ...options.map(([v, t]) => h("option", { value: v, selected: v === value }, t))),
    );

  const netNote = h("p.note", { role: "status" });
  const showNet = async () => {
    const n = await invoke<{ upnp: boolean; public: string | null; manual: boolean; lan: string[] }>("get_net");
    netNote.textContent = !config.anywhere ? ""
      : n.upnp ? `Your router forwards connections to this PC${n.public ? ` (public address ${n.public})` : ""}.`
      : n.manual ? `Your router didn't accept UPnP. Your phone still connects through the PC Remote relay. For the fastest connection, forward TCP 47999 and UDP 47998 to ${n.lan[0] ?? "this PC"}.`
      : "Asking your router…";
  };
  showNet();
  const folder = h("span.hint.path", { title: config.files_dir }, config.files_dir);
  const change = async () => {
    const dir = await invoke<string | null>("pick_files_dir");
    if (dir) {
      config.files_dir = dir;
      folder.textContent = dir;
      folder.title = dir;
    }
  };
  return h(
    "section.view",
    {},
    h("h2", {}, "Settings"),
    h(
      "div.group",
      {},
      h("div.row.info", {}, h("span.grow", {}, h("span.label", {}, "Always on"), h("span.hint", {}, "PC Remote runs as a service from startup, so your phone can reach the sign-in screen, lock screen and admin prompts."))),
      toggle("Show in the tray", "Adds the PC Remote icon when you sign in", config.autostart, (v) => save({ autostart: v })),
      select("Display to stream", config.display, [["", "Primary display"], ...displays.map((d): [string, string] => [d.id, d.primary ? `${d.name} (primary)` : d.name])], (v) => save({ display: v })),
      select("Max quality", config.max_quality, [["auto", "Auto"], ["smooth", "Smooth"], ["sharp", "Sharp"], ["battery", "Battery saver"]], (v) => save({ max_quality: v })),
    ),
    h(
      "div.group",
      {},
      toggle("Receive files", "Phones can send files to this PC", config.allow_files, (v) => save({ allow_files: v })),
      toggle("Share clipboard", "Copy on one device, paste on the other", config.allow_clip, (v) => save({ allow_clip: v })),
      toggle("Direct connection from anywhere", "Asks your router to open a port (UPnP) for a faster remote stream", config.anywhere, async (v) => {
        netNote.textContent = v ? "Asking your router…" : "";
        await save({ anywhere: v });
        showNet();
      }),
    ),
    netNote,
    h(
      "div.group",
      {},
      h("div.row", {}, h("span.grow", {}, h("span.label", {}, "Received files"), folder),
        h("button.ghost", { onclick: change }, "Change"),
        h("button.ghost", { onclick: () => invoke("open_files_dir") }, "Open")),
    ),
    note,
  );
}
