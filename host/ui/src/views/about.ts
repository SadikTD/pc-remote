import { h, invoke, type Update } from "../api";

export function about(): HTMLElement {
  const result = h("p.note", { role: "status" });
  const check = h("button.primary", {}, "Check for updates") as HTMLButtonElement;
  check.onclick = async () => {
    check.disabled = true;
    result.textContent = "Checking…";
    try {
      const u = await invoke<Update>("check_update");
      if (u.newer) {
        result.replaceChildren(`Version ${u.latest.replace(/^v/, "")} is out. It installs by itself within a few hours. `,
          h("a", { href: "#", onclick: (e: Event) => { e.preventDefault(); invoke("open_release", { url: u.url }); } }, "What's new"));
      } else {
        result.textContent = "You have the latest version.";
      }
    } catch {
      result.textContent = "Couldn't reach GitHub. Check your internet connection and try again.";
    }
    check.disabled = false;
  };
  return h(
    "section.view.about",
    {},
    h("img.logo", { src: "/icon.svg", alt: "", width: 72, height: 72 }),
    h("h2", {}, "PC Remote for Windows"),
    h("p.hint", {}, `Version ${__APP_VERSION__}`),
    h("p.lead", {}, "Control this PC from your phone, from anywhere: its screen, mouse, keyboard, files, media and power."),
    h("p.hint", {}, "Updates install automatically. Open source under GPL-3.0."),
    check,
    result,
  );
}
