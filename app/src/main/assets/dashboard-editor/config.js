"use strict";
(() => {
  const el = id => document.getElementById(id);
  const labels = { appearance: "Appearance", weather: "Weather", artwork: "Slideshow artwork", homeassistant: "Home Assistant", cameras: "Cameras", frigate: "Frigate", launcher: "Launcher & remote", maintenance: "Maintenance" };
  const help = {
    appearance: "Choose the dashboard theme, card fills and remote behavior of the Apps card.",
    weather: "Choose extended forecasts independently for each presentation. Off shows current conditions only. Forecast availability depends on your Home Assistant provider.",
    artwork: "Choose uploaded images or recent subscription releases and Watch Next. Videos take priority when an enabled playlist is eligible.",
    homeassistant: "Connect this TV to Home Assistant and choose its header shortcuts. Configure dashboard cards in Dashboard.",
    cameras: "Manage stream addresses and order, the Home camera, live grid and the separate HAL Cameras screensaver.",
    frigate: "Connect to Frigate for alerts and clips. The TV stores the password and pins the server certificate.",
    launcher: "Choose how the Home button opens HAL. Android's Accessibility permission must also be enabled on the TV.",
    maintenance: "Diagnostics, retained logs and TV actions. Restarting HAL disconnects this web session."
  };
  const modes = [["off", "Off"], ["hourly", "Hourly"], ["daily", "5-day"], ["both", "Hourly + 5-day"]];
  const opacity = [["", "Use base opacity"], ...Array.from({ length: 11 }, (_, i) => [String(i * 10), i * 10 + "%"])];
  // key, label, kind/options, explanation, optional numeric limits
  const schema = {
    appearance: [
      ["theme", "Theme", [["Glass", "Glass"], ["GlassDark", "Glass Dark"], ["Calm", "Calm"]], "Calm uses summary tiles with a focused card popup."],
      ["baseOpacity", "Base card opacity", [["", "Use theme default (50%)"], ...opacity.slice(1)], "Shared fallback for all themes; 0% is transparent and 100% solid."],
      ["focusedOpacity", "Focused card tile opacity", opacity, "The dashboard tile or panel under remote focus. The popup has its own setting below."],
      ["unfocusedOpacity", "Unfocused card tile opacity", opacity, "Dashboard tiles without remote focus. Text, artwork and outlines stay readable."],
      ["popupOpacity", "Card popup opacity", opacity, "The opened Calm card panel. Independent of the focused and unfocused tiles."],
      ["collapse", "Collapse Apps card", [["Off", "Off"], ["Up", "Up"], ["Down", "Down"]], "Up/Down collapses after 30 seconds idle. The next key expands the card."],
      ["cycleColors", "Cycle UI colors", "checkbox", "Slowly cycles interface colors, preserving artwork, video and light state colors."]
    ],
    weather: [["dashboard", "Dashboard overview", modes, "Weather cards on the dashboard and Calm's central overview."],
      ["card", "Card popup", modes, "The focused card opened by pressing a Calm summary tile."],
      ["screensaver", "Screensaver", modes, "The global HAL Artwork clock/weather bubble."]],
    artwork: [["enabled", "Artwork slideshow", "checkbox", "Rotate images behind the dashboard and HAL Artwork screensaver."],
      ["useLocal", "Use uploaded images", "checkbox", "Off uses JustWatch subscription releases plus supported apps' Watch Next."],
      ["region", "Streaming country", "text", "Two-letter code, such as DK. Applies to subscription artwork."]],
    launcher: [["catchHomeKey", "Catch the Home button", "checkbox", "Opens HAL immediately. Can affect held volume-button repeats."],
      ["reopenAfterHome", "Reopen after the TV's home screen", "checkbox", "Returns to HAL after the stock launcher appears; also works when leaving an app."]],
    maintenance: [["logHours", "Hours of logs to retain", "number", "Keep between 1 and 72 hours of scrubbed HAL logs and crash traces.", 1, 72],
      ["showMemory", "Show free memory", "checkbox", "Display available TV memory beside the clock."]],
    cameras: [["showHome", "Show camera on Home", "checkbox", "Off gives the dashboard the full width."],
      ["gridLive", "Play grid substreams live", "checkbox", "Off uses still pictures. Live capacity depends on the TV's decoders."],
      ["minimizeStreams", "Minimize simultaneous video streams", "checkbox", "Release other camera streams while a full-screen stream or clip plays."],
      ["showProgress", "Show camera screensaver connection progress", "checkbox", "Off only shows connection problems."]]
  };
  let state = null;
  const forms = new Map();
  const clone = value => JSON.parse(JSON.stringify(value));
  function node(tag, text, className) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (className) n.className = className; return n; }
  function button(text, fn) { const b = node("button", text); b.type = "button"; b.onclick = fn; return b; }
  async function request(body) {
    const response = await fetch("/api/config", { signal: AbortSignal.timeout(50_000), ...(body ? { method: "POST", headers: { "Content-Type": "application/json; charset=utf-8" }, body: JSON.stringify(body) } : {}) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "HAL could not confirm these settings.");
    return data;
  }
  function inputField(form, [key, title, kind, hint, min, max], value) {
    const label = node("label", undefined, "setting-field");
    const text = node("span", title, "setting-label");
    let input;
    if (Array.isArray(kind)) {
      input = node("select");
      const entries = [...kind];
      if (value != null && !entries.some(([v]) => String(v) === String(value))) entries.push([String(value), value + "%"]);
      for (const [v, t] of entries) { const o = node("option", t); o.value = v; input.append(o); }
      input.value = value ?? "";
    } else {
      input = node("input"); input.type = kind || "text";
      if (kind === "checkbox") input.checked = !!value; else input.value = value ?? "";
      input.autocomplete = "off";
      if (kind === "number") { input.min = min; input.max = max; input.step = "1"; input.required = true; }
      if (["text", "password", "url"].includes(kind)) input.maxLength = key === "token" || kind === "password" ? 8192 : key === "url" || key === "sub" ? 4096 : 160;
    }
    input.name = key; input.id = "config-" + form.dataset.section + "-" + key;
    if (kind === "checkbox") { label.classList.add("setting-toggle"); label.append(input, text); } else label.append(text, input);
    if (hint) { const note = node("small", hint); note.id = input.id + "-help"; input.setAttribute("aria-describedby", note.id); label.append(note); }
    form.append(label); return input;
  }
  function values(form, fields) {
    return Object.fromEntries(fields.map(([key, , kind]) => {
      const input = form.elements.namedItem(key);
      return [key, kind === "checkbox" ? input.checked : kind === "number" ? Number(input.value) : key.endsWith("Opacity") ? input.value === "" ? null : Number(input.value) : input.value];
    }));
  }
  const stateKey = key => key === "ha-login" ? "homeassistant" : key;
  const workspaceKey = key => key === "artwork" ? "backgrounds" : stateKey(key);
  const baselineFor = (key, data) => key === "homeassistant" ? {topBar: data.topBar} : key === "ha-login" ? {url:data.url,username:data.username,connected:data.connected,method:data.method} : data;
  function refreshBadges() {
    for (const tab of document.querySelectorAll('[data-workspace]')) {
      const badge = tab.querySelector('.draft-badge');
      if (badge) badge.hidden = ![...forms.values()].some(f => workspaceKey(f.dataset.section) === tab.dataset.workspace && f.dataset.dirty === 'true');
    }
  }
  function dirty(form) {
    form.dataset.dirty = "true"; form.querySelector('[type="submit"]').disabled = false;
    const workspace = workspaceKey(form.dataset.section);
    const badge = el("tab-" + workspace)?.querySelector(".draft-badge"); if (badge) badge.hidden = false;
  }
  function status(form, message) { form.querySelector(".config-status").textContent = message; }
  function makeForm(section, container, title = labels[section], explanation = help[section]) {
    container.replaceChildren(node("h2", title), node("p", explanation, "section-intro"));
    const form = node("form", undefined, "configuration-form"); form.dataset.section = section;
    form.dataset.revision = state.revision; form.dataset.dirty = "false";
    form.baseline = clone(baselineFor(section, state.sections[stateKey(section)]));
    const fields = node("fieldset", undefined, "config-fields"); fields.dataset.section = section; form.append(fields);
    const bar = node("div", undefined, "config-save-bar");
    const save = node("button", "Save " + title.toLowerCase(), "primary"); save.type = "submit"; save.disabled = true;
    const reload = button("Reload saved settings", async () => {
      if (form.dataset.saving === "true") return;
      if (form.dataset.dirty === "true" && !confirm("Discard unsaved changes in " + title + "?")) return;
      pending(form, true); status(form, "Reloading…");
      try { const latest = await request(); if (form.isConnected) { adopt(latest, section); refreshBadges(); } }
      catch (e) { status(form, e.message); }
      finally { if (form.isConnected) pending(form, false); }
    });
    const message = node("p", "", "config-status"); message.setAttribute("role", "status");
    bar.append(save, reload, message); form.append(bar);
    form.addEventListener("input", () => dirty(form)); form.addEventListener("change", () => dirty(form));
    container.append(form); forms.set(section, form); return { form, fields };
  }
  function adopt(latest, force) {
    state = latest;
    for (const [key, other] of [...forms]) {
      if (key === force || (other.dataset.dirty !== "true" && other.dataset.saving !== "true")) render(key);
      else if (JSON.stringify(other.baseline) === JSON.stringify(baselineFor(key, state.sections[stateKey(key)]))) other.dataset.revision = state.revision;
    }
  }
  function pending(form, active) {
    form.dataset.saving = String(active);
    form.querySelector("fieldset").disabled = active;
    for (const b of form.querySelectorAll(".config-save-bar button")) b.disabled = active;
    if (!active) form.querySelector('[type="submit"]').disabled = form.dataset.dirty !== "true";
  }
  async function submit(form, section, value) {
    if (form.dataset.saving === "true") return;
    const revision = form.dataset.revision;
    pending(form, true); status(form, "Saving…");
    try {
      const result = await request({ revision, section, values: value });
      if (result.needsCode) return result;
      if (result.saved !== true || !result.configuration?.revision) throw new Error("HAL did not confirm the save. Your draft is kept; reload to check.");
      if (!form.isConnected) return result;
      adopt(result.configuration, form.dataset.section);
      refreshBadges();
      status(forms.get(form.dataset.section), "Saved to this TV.");
      document.dispatchEvent(new CustomEvent("hal-configuration-saved", { detail: section }));
      return result;
    } catch (e) { status(form, e.message); }
    finally {
      if (form.isConnected) pending(form, false);
    }
  }
  async function runAction(name, cameraId) {
    if (name === "restart" && !confirm("Restart HAL on the TV? This disconnects the web UI. Re-enable it from the gear menu or ADB afterward.")) return;
    const result = el("tv-action-status");
    const sourceForm = document.activeElement?.closest("form");
    const report = text => { if (result) result.textContent = text; if (sourceForm?.querySelector(".config-status")) status(sourceForm, text); };
    try {
      const latest = await request();
      const reply = await request({ revision: latest.revision, section: "action", values: { name, cameraId } });
      if (!reply.saved) throw new Error("The TV did not confirm this action.");
      report(name === "restart" ? "Restart requested. Re-enable the web UI on the TV or through ADB." : "Opened on the TV. Use the TV remote to continue.");
    } catch (e) { report(e.message); }
  }
  function render(section) {
    const container = document.querySelector('[data-config="' + section + '"]');
    const data = state.sections[stateKey(section)]; if (!container || !data) return;
    if (section === "homeassistant" && !forms.has("ha-login")) render("ha-login");
    const { form, fields } = makeForm(section, container, section === "ha-login" ? "Home Assistant connection" : section === "homeassistant" ? "Header shortcuts" : labels[section], section === "ha-login" ? "Connect with a password or access token. Login changes save independently of your header shortcuts." : section === "homeassistant" ? "Choose the actions shown beside the clock. Configure dashboard cards in Dashboard." : help[section]);
    for (const field of schema[section] || []) inputField(fields, field, data[field[0]]);
    // Field IDs use the owner form's section, including nested fieldsets.
    for (const input of fields.querySelectorAll("input,select")) input.id = "config-" + section + "-" + input.name;
    if (section === "appearance") form.onsubmit = event => { event.preventDefault(); submit(form, section, values(form, schema[section])); };
    if (section === "weather") form.onsubmit = event => { event.preventDefault(); submit(form, section, values(form, schema[section])); };
    if (section === "maintenance") {
      fields.append(node("p", "HAL " + state.status.version + " (build " + state.status.buildCode + ") · Built " + new Date(state.status.builtAt).toLocaleString() + " · Retained logs: " + Math.round(state.status.logBytes / 1024) + " KiB", "hint"));
      fields.append(button("Restart HAL on TV", () => runAction("restart")));
      form.onsubmit = event => { event.preventDefault(); submit(form, section, values(form, schema[section])); };
    }
    if (section === "launcher") {
      fields.append(node("p", "The HAL Helper sees remote button presses and which app window opens only to handle Home. It does not read other screen content or save/send those events. You can disable it in Android at any time.", "setting-explanation"));
      inputField(fields, ["consent", "I understand and accept how the Home helper works", "checkbox"], false);
      fields.append(node("p", state.status.homeAutoOff ? "HAL's crash guard disabled the Home override after failed launches." : "Accessibility helper: " + (state.status.helperEnabled ? "enabled" : "off") + " · " + (state.status.helperRunning ? "running" : "not running"), "hint"));
      fields.append(button("Open Accessibility settings on TV", () => runAction("accessibility")));
      form.onsubmit = event => { event.preventDefault(); submit(form, section, { ...values(form, schema[section]), consent: form.elements.namedItem("consent").checked }); };
    }
    if (section === "artwork") {
      const services = node("fieldset", undefined, "service-choices"); services.dataset.section = section;
      services.append(node("legend", "Subscriptions for streaming artwork"));
      const chosen = new Set(data.services);
      for (const name of state.choices.services) { const label = node("label"); const input = node("input"); input.type = "checkbox"; input.value = name; input.checked = chosen.has(name); label.append(input, document.createTextNode(" " + name)); services.append(label); }
      fields.append(services, node("p", "Artwork availability comes from JustWatch and supported Watch Next apps; it is not a guarantee of playback availability.", "hint"));
      fields.append(node("p", "Watch Next permission: " + (state.status.watchNextAllowed ? "allowed" : "not allowed"), "hint"));
      fields.append(button("Request Watch Next permission on TV", () => runAction("watch-next")));
      const local = form.elements.namedItem("useLocal"); const update = () => { services.disabled = local.checked; form.elements.namedItem("region").disabled = local.checked; }; local.addEventListener("change", update); update();
      form.onsubmit = event => { event.preventDefault(); submit(form, section, { ...values(form, schema[section]), services: [...services.querySelectorAll("input:checked")].map(i => i.value) }); };
    }
    if (section === "ha-login") renderHomeAssistant(form, fields, data);
    if (section === "homeassistant") renderHeaderActions(form, fields, data);
    if (section === "frigate") {
      const defs = [["url", "Frigate address", "text", "Host or HTTP(S) URL; defaults to port 8971."], ["username", "Username", "text"], ["password", "Password", "password", data.passwordSaved ? "Saved on TV. Leave blank to keep it for this same address and username." : "Required to connect."], ["trustCertificate", "Trust the certificate presented by this Frigate server", "checkbox", "Confirm on first connection or to replace a changed certificate. Existing pins remain enforced otherwise."]];
      for (const d of defs) inputField(fields, d, data[d[0]]);
      fields.append(button("Remove Frigate connection", () => { if (confirm("Remove Frigate from this TV?")) submit(form, section, { remove: true }); }));
      form.querySelector('[type="submit"]').textContent = "Connect & save Frigate";
      form.onsubmit = event => { event.preventDefault(); submit(form, section, values(form, defs)); };
    }
    if (section === "cameras") { fields.append(node("p", "Maximum reported decoders: " + state.status.decoders, "hint")); renderCameras(form, fields, data); }
    const badge = el("tab-" + workspaceKey(section))?.querySelector(".draft-badge"); if (badge) badge.hidden = true;
  }
  function renderHomeAssistant(form, fields, data) {
    fields.append(node("p", data.connected ? "A login is saved on this TV. Saved tokens and passwords are never filled into this form." : "No login saved. Connect with a password or a long-lived access token.", "hint"));
    const defs = [["url", "Home Assistant address", "text", "For example http://homeassistant.local:8123"], ["method", "Login method", [["password", "Username & password"], ["token", "Long-lived access token"]]], ["username", "Username", "text"], ["password", "Password", "password", "Never stored. Leave blank to reuse a saved password login on the same server."], ["token", "Access token", "password", "Leave blank to reuse a saved token on the same server."]];
    for (const d of defs) inputField(fields, d, data[d[0]]);
    const code = inputField(fields, ["code", "Authenticator code", "text"], ""); code.autocomplete = "one-time-code"; code.parentElement.hidden = true;
    let flow = "";
    const method = form.elements.namedItem("method");
    function update() { for (const key of ["username", "password", "token"]) form.elements.namedItem(key).parentElement.hidden = key === "token" ? method.value !== "token" : method.value === "token"; }
    method.addEventListener("change", () => { resetFlow(); update(); }); update();
    const connect = form.querySelector('[type="submit"]'); connect.textContent = "Connect & save login";
    const restart = button("Start login again", () => { resetFlow(); dirty(form); form.elements.namedItem("password").focus(); }); restart.hidden = true; fields.append(restart);
    function resetFlow() { flow = ""; code.value = ""; code.parentElement.hidden = true; restart.hidden = true; connect.textContent = "Connect & save login"; }
    for (const key of ["url", "username", "password", "token"]) form.elements.namedItem(key).addEventListener("input", () => { if (flow) resetFlow(); });
    form.onsubmit = async event => {
      event.preventDefault();
      if (!form.reportValidity()) return;
      const result = await submit(form, "ha-login", { ...values(form, defs), code: code.value, flow });
      if (result?.needsCode || result?.saved) { form.elements.namedItem("password").value = ""; form.elements.namedItem("token").value = ""; }
      if (result?.needsCode) { restart.hidden = false; flow = result.flow; code.value = ""; code.parentElement.hidden = false; code.focus(); status(form, result.message); connect.textContent = "Verify code & save login"; }
    };
    fields.append(button("Log out on this TV", () => { if (confirm("Remove this TV's Home Assistant login?")) submit(form, "ha-logout", {}); }));
  }
  function renderHeaderActions(form, fields, data) {
    const actions = [...data.topBar];
    fields.append(node("p", "Choose up to " + state.choices.maxActions + " scenes, scripts or buttons. Use the arrows to set their order.", "hint"));
    const list = node("ol", undefined, "configured-actions"); fields.append(list);
    const picker = node("select"); picker.setAttribute("aria-label", "Home Assistant header action");
    function draw() { list.replaceChildren(...actions.map((id, index) => { const row = node("li"); row.append(node("span", id), button("↑", () => { if (index) { [actions[index - 1], actions[index]] = [actions[index], actions[index - 1]]; draw(); dirty(form); } }), button("↓", () => { if (index < actions.length - 1) { [actions[index + 1], actions[index]] = [actions[index], actions[index + 1]]; draw(); dirty(form); } }), button("Remove", () => { actions.splice(index, 1); draw(); dirty(form); })); return row; })); }
    draw();
    const manual = node("input"); manual.placeholder = "scene.evening"; manual.setAttribute("aria-label", "Manual header action entity ID");
    fields.append(picker, manual, button("Add header action", () => { const id = manual.value.trim() || picker.value; if (/^(scene|script|button|input_button)\.[a-z0-9_]+$/.test(id) && !actions.includes(id) && actions.length < state.choices.maxActions) { actions.push(id); manual.value = ""; draw(); dirty(form); } else status(form, "Choose a unique supported action within the limit."); }));
    const searchLabel = node("label", "Find a header action");
    const search = node("input"); search.type = "search"; search.placeholder = "Search names or entity IDs"; search.setAttribute("aria-label", "Find a header action"); searchLabel.append(search); fields.insertBefore(searchLabel, picker);
    const choiceStatus = node("p", "", "hint"); fields.append(choiceStatus);
    let available = [], fetching = false;
    const filter = () => {
      const words = search.value.toLowerCase().trim().split(/\s+/).filter(Boolean);
      const matches = available.filter(e => words.every(w => (e.name + " " + e.id).toLowerCase().includes(w)));
      picker.replaceChildren(...matches.map(e => { const o = node("option", e.name + " · " + e.id); o.value = e.id; return o; }));
    };
    search.oninput = filter;
    const refreshChoices = async () => {
      if (fetching) return;
      fetching = true; choiceStatus.textContent = "Loading header actions…";
      try {
        const deadline = Date.now() + 35_000;
        let catalog;
        do {
          const response = await fetch("/api/catalog", {signal:AbortSignal.timeout(10_000)});
          if (!response.ok) throw new Error("Catalog unavailable");
          catalog = await response.json();
          if (!picker.isConnected) return;
          if (!catalog.loading) break;
          await new Promise(resolve => setTimeout(resolve, 500));
        } while (Date.now() < deadline);
        if (catalog.loading) throw new Error("Catalog still loading");
        available = (catalog.entities || []).filter(e => ["scene", "script", "button", "input_button"].includes(e.domain)); filter();
        choiceStatus.textContent = catalog.notice || (available.length ? available.length + " header actions available." : "No actions found. Enter an action ID manually or connect Home Assistant.");
      } catch { choiceStatus.textContent = "Entity choices unavailable. Enter an action ID manually, or reload choices after reconnecting."; }
      finally { fetching = false; }
    };
    fields.append(button("Reload entity choices", refreshChoices)); refreshChoices();
    form.querySelector('[type="submit"]').textContent = "Save header shortcuts";
    form.onsubmit = event => { event.preventDefault(); submit(form, "homeassistant", { topBar: actions }); };
  }
  function renderCameras(form, fields, data) {
    const cameras = clone(data.items); const hidden = new Set(data.hidden);
    const selected = inputField(fields, ["selected", "Home camera", [["", "First available camera"], ...cameras.map(c => [c.id, c.name])]], data.selected);
    const seconds = inputField(fields, ["seconds", "Camera screensaver seconds per camera", state.choices.cameraSeconds.map(n => [String(n), n + " seconds"])] , String(data.seconds));
    fields.append(button("Open Android screensaver settings on TV", () => runAction("screensaver")));
    const list = node("ol", undefined, "camera-editors"); fields.append(list);
    const savedIds = new Set(data.items.map(c => c.id));
    function draw() {
      const selection = selected.value;
      selected.replaceChildren(...[["", "First available camera"], ...cameras.map(c => [c.id, c.name])].map(([id, name]) => { const o = node("option", name); o.value = id; return o; })); selected.value = cameras.some(c => c.id === selection) ? selection : "";
      list.replaceChildren(...cameras.map((c, index) => {
        const li = node("li"); const details = node("details", undefined, "camera-entry"); details.open = !savedIds.has(c.id);
        const summary = node("summary", c.name || "New camera"); details.append(summary);
        const body = node("div", undefined, "camera-fields"); body.dataset.section = "camera-" + c.id;
        const defs = [["name", "Camera name", "text"], ["url", "Main RTSP stream", "text", c.urlSaved ? "Saved on TV. Leave blank to keep the address and login." : "Required, including any stream login."], ["sub", "Substream RTSP address", "text", c.subSaved ? "Saved on TV. Leave blank to keep it." : "Optional lighter stream for the grid."], ["clearSub", "Remove saved substream", "checkbox"], ["tcp", "Use TCP transport", "checkbox"], ["audio", "Main stream audio", "checkbox"], ["subAudio", "Substream audio", "checkbox"], ["stretch", "Stretch main stream to 16:9", "checkbox"], ["subStretch", "Stretch substream to 16:9", "checkbox"]];
        for (const def of defs) { const input = inputField(body, def, c[def[0]]); input.name = c.id + "-" + def[0]; input.addEventListener("input", () => { c[def[0]] = def[2] === "checkbox" ? input.checked : input.value; if (def[0] === "name") summary.textContent = c.name || "New camera"; }); }
        const include = inputField(body, ["include", "Include in HAL Cameras screensaver", "checkbox"], !hidden.has(c.id)); include.name = c.id + "-include"; include.onchange = () => { if (include.checked) hidden.delete(c.id); else hidden.add(c.id); };
        const actions = node("div", undefined, "controls");
        for (const [label, step] of [["Move up", -1], ["Move down", 1]]) { const b = button(label, () => { const to = index + step; [cameras[index], cameras[to]] = [cameras[to], cameras[index]]; draw(); dirty(form); }); b.disabled = index + step < 0 || index + step >= cameras.length; actions.append(b); }
        const test = button("Test streams on TV", () => runAction("camera-test", c.id)); test.disabled = !savedIds.has(c.id);
        actions.append(test, button("Delete camera", () => { if (confirm("Delete " + c.name + "?")) { cameras.splice(index, 1); hidden.delete(c.id); draw(); dirty(form); } }));
        body.append(actions); details.append(body); li.append(details); return li;
      }));
    }
    draw(); fields.append(button("Add camera", () => { cameras.push({ id: crypto.randomUUID(), name: "New camera", url: "", sub: "", tcp: true, audio: true, subAudio: true, stretch: false, subStretch: true }); draw(); dirty(form); }));
    fields.append(node("p", "Save new stream addresses before testing. Playback tests run on the TV, including its codec and audio diagnostics.", "hint"));
    form.onsubmit = event => { event.preventDefault(); submit(form, "cameras", { ...values(form, schema.cameras), items: cameras, selected: selected.value, seconds: Number(seconds.value), hidden: [...hidden] }); };
  }
  const searchEntries = [];
  for (const [key, title] of Object.entries(labels)) {
    searchEntries.push({ key, title, text: title + " " + help[key] });
    for (const [, label, , note] of schema[key] || []) searchEntries.push({ key, title: label, text: title + " " + label + " " + (note || "") });
  }
  for (const [key, title, text] of [["backgrounds", "Video playlists", "Video library URL playback shuffle random start switch dimming"], ["settings", "Settings transfer", "Import export backup file credentials"], ["dashboard", "Dashboard layout", "Cards views entities visibility rows columns Apps app selection template"], ["homeassistant", "Home Assistant login", "username password token authenticator two-factor MFA header actions"], ["cameras", "Camera streams", "RTSP transport TCP UDP substream audio stretch order test"], ["frigate", "Frigate login", "password alerts clips certificate pin trust"]]) searchEntries.push({ key, title, text });
  function search() {
    const words = el("settings-search").value.toLowerCase().trim().split(/\s+/).filter(Boolean);
    const results = el("settings-search-results"); results.replaceChildren(); results.hidden = !words.length;
    if (!words.length) return;
    const matches = searchEntries.filter(e => words.every(w => e.text.toLowerCase().includes(w))).sort((a,b) => Number(b.title.toLowerCase() === words.join(" ")) - Number(a.title.toLowerCase() === words.join(" ")) || words.filter(w => b.title.toLowerCase().includes(w)).length - words.filter(w => a.title.toLowerCase().includes(w)).length).slice(0, 12);
    for (const match of matches) results.append(button(match.title + " · " + (labels[match.key] || match.key), () => {
      el("tab-" + (match.key === "artwork" ? "backgrounds" : match.key)).click();
      if (match.key === "artwork") el("artwork-library").open = true;
      results.hidden = true; el("settings-search").value = "";
      const container = document.querySelector('[data-config="' + match.key + '"]');
      const target = [...(container?.querySelectorAll("label") || [])].find(n => n.textContent.includes(match.title))?.querySelector("input,select");
      if (target) { target.scrollIntoView({ block: "center" }); target.focus(); }
    }));
    if (!matches.length) results.append(node("p", "No settings found. Try camera, weather, opacity or login."));
  }
  el("settings-search").oninput = search;
  el("settings-search").onkeydown = event => { if (event.key === "Enter") { event.preventDefault(); el("settings-search-results").querySelector("button")?.click(); } if (event.key === "Escape") { el("settings-search").value = ""; search(); } };
  document.addEventListener("keydown", event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") { event.preventDefault(); el("settings-search").focus(); } });
  window.addEventListener("beforeunload", event => { if ([...forms.values()].some(f => f.dataset.dirty === "true")) event.preventDefault(); });
  const actionStatus = node("p", "", "hint"); actionStatus.id = "tv-action-status"; actionStatus.setAttribute("role", "status"); document.querySelector(".settings-content").prepend(actionStatus);
  // Keep the standalone screensaver entry reachable even with no cameras configured.
  const activation = node("section", undefined, "settings-block"); activation.append(node("h2", "Android screensaver"), node("p", "Select HAL Artwork or HAL Cameras and choose activation timing on the TV. Android owns activation and sleep.", "hint"), button("Open screensaver settings on TV", () => runAction("screensaver"))); el("backgrounds-panel").append(activation);
  document.addEventListener("hal-configuration-saved", event => {
    if (["ha-login", "ha-logout"].includes(event.detail)) setTimeout(() => { const refresh = [...(forms.get("homeassistant")?.querySelectorAll("button") || [])].find(b => b.textContent === "Reload entity choices"); refresh?.click(); }, 500);
  });
  async function load() {
    try { state = await request(); if (!state.sections || !state.revision) throw new Error("Update HAL and reload this page to use web settings."); for (const key of Object.keys(labels)) render(key);
      const androidStatus = state.status.screensaver;
      if (androidStatus) activation.append(node("p", androidStatus.readable ? "Android screensaver: " + (androidStatus.component || "system default") + (androidStatus.enabled === false ? " (disabled)" : "") : "Android does not expose screensaver selection on this TV.", "hint"));
      for (const name of state.choices.services) if (!searchEntries.some(e => e.title === name)) searchEntries.push({key:"artwork",title:name,text:name+" subscription streaming artwork"}); }
    catch (e) { for (const container of document.querySelectorAll("[data-config]")) { container.replaceChildren(node("p", e.message, "config-status"), button("Retry loading settings", load)); } }
  }
  load();
})();
