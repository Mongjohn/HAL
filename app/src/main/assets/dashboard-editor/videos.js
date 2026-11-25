"use strict";
(() => {
  const el = id => document.getElementById(id);
  const panel = el("video-library"), fields = el("video-fields"), status = el("video-status");
  const numeric = [["video-switch", "switchSeconds"], ["dashboard-dim", "dashboardDim"], ["screensaver-dim", "screensaverDim"]];
  const openVideos = new Set(), openPlaylists = new Set();
  let draft = null, dirty = false, busy = false;
  const say = text => { status.textContent = text; };
  const id = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), v => v.toString(16).padStart(2, "0")).join("");
  const validUrl = text => {
    try { const url = new URL(text); return ["http:", "https:"].includes(url.protocol) && url.hostname && !url.username && !url.password; }
    catch { return false; }
  };
  function overview() {
    if (!draft) return;
    const enabled = draft.playlists.filter(p => p.enabled);
    const active = new Set(enabled.flatMap(p => p.videoIds));
    const count = key => draft.videos.filter(v => active.has(v.id) && v[key]).length;
    el("playlist-overview").textContent = enabled.length
      ? `${enabled.length} ${enabled.length === 1 ? "playlist" : "playlists"} enabled · ${count("background")} dashboard / ${count("screensaver")} screensaver videos`
      : "No playlists enabled. HAL will use image artwork.";
  }
  function changed() {
    dirty = true; controls(); overview();
    say("Background changes are not saved. Save to update the TV.");
  }
  function controls() {
    fields.disabled = busy || !draft;
    el("video-reload").disabled = busy;
    el("video-save").disabled = busy || !dirty;
    el("video-add-button").disabled = busy || !draft || draft.videos.length >= 50;
    el("playlist-add-button").disabled = busy || !draft || draft.playlists.length >= 24;
  }
  async function request(body) {
    let response;
    try {
      response = await fetch("/api/videos", { signal: AbortSignal.timeout(10_000),
        ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json; charset=utf-8" }, body: JSON.stringify(body) }) });
    } catch { throw new Error("No response from HAL. Check that Enable Web Editor is on."); }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "HAL returned an unexpected background response.");
    return data;
  }
  function validate(data) {
    if (!Number.isSafeInteger(data?.revision) || data.schema !== 4 || !Array.isArray(data.videos) || !Array.isArray(data.playlists) ||
        typeof data.shuffle !== "boolean" || numeric.some(([, key]) => !Number.isInteger(data[key])) ||
        data.videos.some(v => ["background", "screensaver", "randomStart"].some(k => typeof v[k] !== "boolean")) ||
        data.playlists.some(p => !Array.isArray(p.videoIds) || typeof p.enabled !== "boolean")) throw new Error("HAL did not return background settings. Reload the page after updating HAL.");
    return data;
  }
  function button(label, action, accessible) {
    const b = document.createElement("button"); b.type = "button"; b.textContent = label; b.onclick = action;
    if (accessible) b.setAttribute("aria-label", accessible);
    return b;
  }
  function disclosure(key, open, title, note) {
    const details = document.createElement("details"), summary = document.createElement("summary");
    const heading = document.createElement("strong"), small = document.createElement("span");
    heading.textContent = title; small.textContent = note; small.className = "entry-note";
    summary.append(heading, small); details.append(summary); details.open = open.has(key);
    details.ontoggle = () => { if (details.open) open.add(key); else open.delete(key); };
    return { details, heading, small };
  }
  function textField(label, value, length, change, type = "text") {
    const field = document.createElement("label"), input = document.createElement("input");
    field.textContent = label; input.type = type; input.maxLength = length; input.required = true; input.value = value;
    input.oninput = () => { change(input.value); changed(); }; field.append(input); return field;
  }
  function check(label, checked, action, key) {
    const field = document.createElement("label"), input = document.createElement("input");
    input.type = "checkbox"; input.checked = checked; if (key) input.dataset.option = key;
    input.onchange = () => { action(input.checked); changed(); }; field.append(input, document.createTextNode(" " + label)); return field;
  }
  function renderPlaylists() {
    el("playlist-list").replaceChildren(...draft.playlists.map(p => {
      const row = document.createElement("li"); row.className = "playlist-entry";
      const { details, heading, small } = disclosure(p.id, openPlaylists, p.title, `${p.videoIds.length} videos`);
      const enabled = check("Enabled", p.enabled, value => { p.enabled = value; }, "playlist-enabled");
      enabled.className = "playlist-enable"; row.append(enabled, details);
      const body = document.createElement("div"); body.className = "entry-editor";
      body.append(textField("Playlist name", p.title, 160, value => { p.title = value; heading.textContent = value || "Unnamed playlist"; }));
      const members = document.createElement("div"); members.className = "playlist-members";
      for (const video of draft.videos) {
        const member = check(video.title || "Unnamed video", p.videoIds.includes(video.id), checked => {
          p.videoIds = checked ? [...p.videoIds, video.id] : p.videoIds.filter(v => v !== video.id);
          small.textContent = `${p.videoIds.length} videos`;
        }, "playlist-member"); member.querySelector("input").dataset.videoId = video.id; members.append(member);
      }
      if (!draft.videos.length) members.textContent = "Add videos to the library below, then select them here.";
      body.append(members, button("Delete playlist", () => { draft.playlists = draft.playlists.filter(v => v.id !== p.id); changed(); renderPlaylists(); }));
      details.append(body); return row;
    }));
    controls(); overview();
  }
  function render() {
    el("video-shuffle").checked = draft.shuffle;
    for (const [key, value] of numeric) el(key).value = draft[value];
    el("video-list").replaceChildren(...draft.videos.map((video, index) => {
      const row = document.createElement("li"); row.className = "video-entry";
      const targets = () => [video.background && "Dashboard", video.screensaver && "Screensaver", video.randomStart && "Random start"].filter(Boolean).join(" · ") || "Not used on either screen";
      const { details, heading, small } = disclosure(video.id, openVideos, video.title, targets());
      const body = document.createElement("div"); body.className = "entry-editor";
      body.append(textField("Name", video.title, 160, value => { video.title = value; heading.textContent = value || "Unnamed video"; }),
        textField("Direct video URL", video.url, 4096, value => { video.url = value; }, "url"));
      const options = document.createElement("div"); options.className = "controls";
      for (const [key, label] of [["background", "Dashboard background"], ["screensaver", "Screensaver background"], ["randomStart", "Start at a random timestamp"]]) {
        options.append(check(label, video[key], value => { video[key] = value; small.textContent = targets(); }, key));
      }
      const actions = document.createElement("div"); actions.className = "controls video-actions";
      const move = step => { [draft.videos[index], draft.videos[index + step]] = [draft.videos[index + step], draft.videos[index]]; changed(); render(); };
      const up = button("↑", () => move(-1), "Move video up"), down = button("↓", () => move(1), "Move video down");
      up.disabled = index === 0; down.disabled = index === draft.videos.length - 1;
      actions.append(up, down, button("Remove", () => {
        draft.videos.splice(index, 1);
        for (const p of draft.playlists) p.videoIds = p.videoIds.filter(v => v !== video.id);
        changed(); render();
      }));
      body.append(options, actions); details.append(body); row.append(details); return row;
    }));
    renderPlaylists();
  }
  async function load() {
    busy = true; controls(); say("Loading background settings…");
    try { draft = validate(await request()); dirty = false; render(); say(`${draft.videos.length} of 50 videos. Settings are saved per TV.`); }
    catch (error) { say(error.message); }
    finally { busy = false; controls(); }
  }
  panel.addEventListener("toggle", () => { if (panel.open && !draft && !busy) load(); });
  el("video-shuffle").onchange = () => { draft.shuffle = el("video-shuffle").checked; changed(); };
  for (const [key, value] of numeric) { el(key).required = true; el(key).oninput = () => { draft[value] = el(key).valueAsNumber; changed(); }; }
  el("playlist-add").onsubmit = event => {
    event.preventDefault(); const title = el("playlist-title").value.trim(); if (!title || draft.playlists.length >= 24) return;
    const p = { id: id(), title, enabled: false, videoIds: [] }; draft.playlists.push(p); openPlaylists.add(p.id);
    el("playlist-title").value = ""; changed(); renderPlaylists();
  };
  el("video-add").onsubmit = event => {
    event.preventDefault(); const title = el("video-title").value.trim(), url = el("video-url").value.trim();
    if (!title || !validUrl(url)) return say("Enter a name and a direct HTTP(S) video URL without a username or password.");
    if (draft.videos.length >= 50) return say("Keep at most 50 videos in the library.");
    const video = { id: id(), title, url, background: false, screensaver: false, randomStart: false };
    draft.videos.push(video); openVideos.add(video.id); el("video-title").value = ""; el("video-url").value = "";
    changed(); render();
  };
  el("video-reload").onclick = () => { if (!dirty || confirm("Discard unsaved background changes and reload saved settings?")) load(); };
  el("video-save").onclick = async () => {
    for (const [key] of numeric) if (!el(key).reportValidity()) return;
    if (draft.switchSeconds > 0 && draft.switchSeconds < 10) return say("Use 0 to play to the end, or a switch interval of at least 10 seconds.");
    const invalid = draft.videos.find(v => !v.title.trim() || !validUrl(v.url));
    if (invalid) { openVideos.add(invalid.id); render(); return say("Every video needs a name and a valid direct HTTP(S) URL without a username or password."); }
    if (draft.playlists.some(p => !p.title.trim())) return say("Every playlist needs a name.");
    busy = true; controls(); say("Saving background settings…");
    try {
      const result = await request(draft);
      if (result.saved !== true || result.settings?.revision !== draft.revision + 1) throw new Error("HAL did not confirm the video update. Your draft is kept; reload to check saved settings.");
      draft = validate(result.settings); dirty = false; render(); say("Video settings saved to the TV.");
    } catch (error) { say(error.message); }
    finally { busy = false; controls(); }
  };
})();
