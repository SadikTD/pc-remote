import { h, invoke, type Device, type State } from "../api";

function row(d: Device, reload: () => void): HTMLElement {
  const li = h("li.row");
  const normal = () => {
    li.replaceChildren(
      h(`i.dot${d.connected ? ".live" : ""}`),
      h("span.grow", {}, h("span.label", {}, d.name), h("span.hint", {}, d.connected ? "Connected" : "Not connected")),
      h("button.ghost", { onclick: rename }, "Rename"),
      h("button.ghost.danger", { onclick: confirm }, "Remove"),
    );
  };
  const rename = () => {
    const input = h("input.text", { value: d.name, maxLength: 40, "aria-label": "Phone name" }) as HTMLInputElement;
    const save = async () => {
      await invoke("rename", { id: d.id, name: input.value });
      reload();
    };
    input.addEventListener("keydown", (e) => (e.key === "Enter" ? save() : e.key === "Escape" ? normal() : 0));
    li.replaceChildren(input, h("button.primary", { onclick: save }, "Save"), h("button.ghost", { onclick: normal }, "Cancel"));
    input.focus();
    input.select();
  };
  const confirm = () => {
    li.replaceChildren(
      h("span.grow", {}, h("span.label", {}, `Remove ${d.name}?`), h("span.hint", {}, "It will need to scan a new code to connect again.")),
      h("button.danger-solid", { onclick: async () => { await invoke("revoke", { id: d.id }); reload(); } }, "Remove"),
      h("button.ghost", { onclick: normal }, "Keep"),
    );
  };
  normal();
  return li;
}

export function devices(state: State, reload: () => void): HTMLElement {
  return h(
    "section.view",
    {},
    h("h2", {}, "Paired phones"),
    state.devices.length
      ? h("ul.list", {}, ...state.devices.map((d) => row(d, reload)))
      : h("p.empty", {}, "No phones yet. Scan the code on the Home tab with PC Remote on your phone."),
  );
}
