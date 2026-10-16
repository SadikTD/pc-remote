// Thin wrappers over the global Tauri API (withGlobalTauri), so no JS deps ship.
declare global {
  const __APP_VERSION__: string;
  interface Window {
    __TAURI__: {
      core: { invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> };
      event: { listen<T>(name: string, cb: (e: { payload: T }) => void): Promise<() => void> };
      window: { getCurrentWindow(): { minimize(): Promise<void>; close(): Promise<void> } };
    };
  }
}

/** Commands the tray process runs itself (file pickers, Explorer); the server answers the rest. */
const LOCAL = new Set(["send_file", "pick_files_dir", "open_files_dir", "open_release"]);

export const invoke = <T>(cmd: string, args?: Record<string, unknown>) =>
  LOCAL.has(cmd) ? window.__TAURI__.core.invoke<T>(cmd, args) : window.__TAURI__.core.invoke<T>("call", { cmd, args: args ?? null });
export const listen = <T = unknown>(name: string, cb: (payload: T) => void) => window.__TAURI__.event.listen<T>(name, (e) => cb(e.payload));
export const appWindow = () => window.__TAURI__.window.getCurrentWindow();

export interface Device { id: string; name: string; connected: boolean }
export interface State { name: string; devices: Device[]; locked: boolean }
export interface Config {
  autostart: boolean; display: string; max_quality: string;
  allow_files: boolean; allow_clip: boolean; anywhere: boolean; files_dir: string;
}
export interface Display { id: string; name: string; primary: boolean }
export interface Update { current: string; latest: string; newer: boolean; url: string }

/** Tiny element builder: h("div.card", {onclick}, child, "text"). */
export function h(tag: string, props: Record<string, unknown> = {}, ...kids: (Node | string | null | false)[]): HTMLElement {
  const [name, ...classes] = tag.split(".");
  const el = document.createElement(name || "div");
  if (classes.length) el.className = classes.join(" ");
  for (const [k, v] of Object.entries(props)) {
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v as EventListener);
    else if (k in el) (el as unknown as Record<string, unknown>)[k] = v;
    else el.setAttribute(k, String(v));
  }
  for (const k of kids) if (k) el.append(k);
  return el;
}

export function toggle(label: string, hint: string, on: boolean, change: (v: boolean) => void): HTMLElement {
  const input = h("input", { type: "checkbox", checked: on, onchange: (e: Event) => change((e.target as HTMLInputElement).checked) });
  return h("label.row.switch", {}, h("span.grow", {}, h("span.label", {}, label), h("span.hint", {}, hint)), input, h("span.knob"));
}
