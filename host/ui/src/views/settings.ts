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

  return h(
    "section.view",
    {},
    h("h2", {}, "Settings"),
    h(
      "div.group",
      {},
      toggle("Start with Windows", "Runs quietly in the tray", config.autostart, (v) => save({ autostart: v })),
      select("Display to stream", config.display, [["", "Primary display"], ...displays.map((d): [string, string] => [d.id, d.primary ? `${d.name} (primary)` : d.name])], (v) => save({ display: v })),
      select("Max quality", config.max_quality, [["auto", "Auto"], ["smooth", "Smooth"], ["sharp", "Sharp"], ["battery", "Battery saver"]], (v) => save({ max_quality: v })),
    ),
    h(
      "div.group",
      {},
      toggle("Receive files", "Phones can send files to this PC", config.allow_files, (v) => save({ allow_files: v })),
      toggle("Share clipboard", "Copy on one device, paste on the other", config.allow_clip, (v) => save({ allow_clip: v })),
      toggle("Access from anywhere", "Opens ports on your router with UPnP", config.anywhere, (v) => save({ anywhere: v })),
    ),
    h(
      "div.group",
      {},
      h("div.row", {}, h("span.grow", {}, h("span.label", {}, "Received files"), h("span.hint.path", { title: config.files_dir }, config.files_dir)),
        h("button.ghost", { onclick: () => invoke("open_files_dir") }, "Open")),
    ),
    note,
  );
}
