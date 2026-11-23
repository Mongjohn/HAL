"use strict";

(() => {
  const el = id => document.getElementById(id);
  const panel = el("video-library"), fields = el("video-fields"), status = el("video-status");
  let draft = null, dirty = false, busy = false;
  const numeric = [["video-switch", "switchSeconds"],
    ["dashboard-dim", "dashboardDim"], ["screensaver-dim", "screensaverDim"]];
  const say = text => { status.textContent = text; };
  const validUrl = text => {
    try { const url = new URL(text); return ["http:", "https:"].includes(url.protocol) && url.hostname && !url.username && !url.password; }
    catch { return false; }
  };
  function changed() {
    dirty = true;
    el("video-save").disabled = false;
    say("Video changes are not saved yet. Save video settings to update the TV.");
  }
  function controls() {
    fields.disabled = busy || !draft;
    el("video-reload").disabled = busy;
    el("video-save").disabled = busy || !dirty;
    el("video-add-button").disabled = busy || !draft || draft.videos.length >= 50;
  }
  async function request(body) {
    let response;
    try {
      response = await fetch("/api/videos", {
        signal: AbortSignal.timeout(10_000),
        ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json; charset=utf-8" }, body: JSON.stringify(body) }),
      });
    } catch { throw new Error("No response from HAL. Check that Enable Web Editor is on."); }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "HAL returned an unexpected video response.");
    return data;
  }
  function validate(data) {
    if (!Number.isSafeInteger(data?.revision) || data.schema !== 3 || !Array.isArray(data?.videos) ||
        typeof data.shuffle !== "boolean" || numeric.some(([, key]) => !Number.isInteger(data[key])) ||
        data.videos.some(video => ["background", "screensaver", "randomStart"].some(key => typeof video[key] !== "boolean"))) {
      throw new Error("HAL did not return video settings.");
    }
    return data;
  }
  function button(label, action, accessible) {
    const result = document.createElement("button");
    result.type = "button";
    result.textContent = label;
    if (accessible) result.setAttribute("aria-label", accessible);
    result.onclick = action;
    return result;
  }
  function render() {
    el("video-shuffle").checked = draft.shuffle;
    for (const [id, key] of numeric) el(id).value = draft[key];
    el("video-list").replaceChildren(...draft.videos.map((video, index) => {
      const row = document.createElement("li");
      row.className = "video-entry";
      for (const [key, label, length] of [["title", "Name", 160], ["url", "Direct video URL", 4096]]) {
        const field = document.createElement("label"), input = document.createElement("input");
        field.textContent = label;
        input.type = key === "url" ? "url" : "text";
        input.maxLength = length;
        input.required = true;
        input.value = video[key];
        input.oninput = () => { video[key] = input.value; changed(); };
        field.append(input);
        row.append(field);
      }
      const options = document.createElement("div");
      options.className = "controls";
      for (const [key, text] of [["background", "Dashboard background"], ["screensaver", "Screensaver background"], ["randomStart", "Start at a random timestamp"]]) {
        const label = document.createElement("label"), check = document.createElement("input");
        check.type = "checkbox"; check.dataset.option = key; check.checked = video[key];
        check.onchange = () => { video[key] = check.checked; changed(); };
        label.append(check, document.createTextNode(" " + text)); options.append(label);
      }
      row.append(options);
      const actions = document.createElement("div");
      actions.className = "controls";
      const move = by => {
        const other = index + by;
        [draft.videos[index], draft.videos[other]] = [draft.videos[other], draft.videos[index]];
        changed(); render();
      };
      const up = button("↑", () => move(-1), "Move video up");
      const down = button("↓", () => move(1), "Move video down");
      up.disabled = index === 0;
      down.disabled = index === draft.videos.length - 1;
      actions.append(up, down, button("Remove", () => { draft.videos.splice(index, 1); changed(); render(); }));
      row.append(actions);
      return row;
    }));
    controls();
  }
  async function load() {
    busy = true; controls(); say("Loading video settings…");
    try { draft = validate(await request()); dirty = false; render(); say(`${draft.videos.length} of 50 videos. Settings are saved per TV.`); }
    catch (error) { say(error.message); }
    finally { busy = false; controls(); }
  }
  panel.addEventListener("toggle", () => { if (panel.open && !draft && !busy) load(); });
  el("video-shuffle").onchange = () => { draft.shuffle = el("video-shuffle").checked; changed(); };
  for (const [id, key] of numeric) {
    el(id).required = true;
    el(id).oninput = () => { draft[key] = el(id).valueAsNumber; changed(); };
  }
  el("video-add").onsubmit = event => {
    event.preventDefault();
    const title = el("video-title").value.trim(), url = el("video-url").value.trim();
    if (!title || !validUrl(url)) return say("Enter a name and a direct HTTP(S) video URL without a username or password.");
    if (draft.videos.length >= 50) return say("Keep at most 50 videos in the library.");
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    draft.videos.push({ id: Array.from(bytes, value => value.toString(16).padStart(2, "0")).join(""), title, url,
      background: false, screensaver: false, randomStart: false });
    el("video-title").value = ""; el("video-url").value = "";
    changed(); render();
  };
  el("video-reload").onclick = () => { if (!dirty || confirm("Discard unsaved video changes and reload the saved library?")) load(); };
  el("video-save").onclick = async () => {
    for (const [id] of numeric) if (!el(id).reportValidity()) return;
    if (draft.switchSeconds > 0 && draft.switchSeconds < 10) return say("Use 0 to play to the end, or a switch interval of at least 10 seconds.");
    const invalid = draft.videos.some(video => !video.title.trim() || !validUrl(video.url));
    if (invalid) return say("Every video needs a name and a valid direct HTTP(S) URL without a username or password.");
    busy = true; controls(); say("Saving video settings…");
    try {
      const result = await request(draft);
      if (result.saved !== true || result.settings?.revision !== draft.revision + 1) throw new Error("HAL did not confirm the video update. Your draft is kept; reload to check the saved library.");
      draft = validate(result.settings); dirty = false; render();
      say("Video settings saved to the TV.");
    } catch (error) { say(error.message); }
    finally { busy = false; controls(); }
  };
})();
