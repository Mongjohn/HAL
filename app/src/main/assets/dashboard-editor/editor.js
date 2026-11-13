"use strict";

const $ = id => document.getElementById(id);
const maxRows = 360;
const screenRows = 15;
const drafts = {};
const history = { assistant: [], home: [] };
const dirty = new Set();
const allAppsId = "dock:all_apps";
let installedApps = [];
let revision = null;
let canRestore = false;
let camera = false;
let surface = "home";
let pageIndex = 0;
let selected = null;
let catalog = [];
let templates = [];
let names = new Map();
let busy = false;
let gesture = null;
let notice = "";

const copy = value => JSON.parse(JSON.stringify(value));
const layout = () => drafts[surface];
const page = () => layout().pages[pageIndex];
const active = () => page().cards.find(card => card.id === selected);
const say = message => { $("status").textContent = message || notice; };

function node(tag, label, className) {
  const result = document.createElement(tag);
  if (label != null) result.textContent = label;
  if (className) result.className = className;
  return result;
}

function button(label, click, accessibleLabel = label) {
  const result = node("button", label);
  result.setAttribute("aria-label", accessibleLabel);
  result.onclick = click;
  return result;
}

function options(select, entries, preferred = select.value) {
  select.replaceChildren(...entries.map(([value, label]) => {
    const option = node("option", label);
    option.value = value;
    return option;
  }));
  if (entries.some(([value]) => value === preferred)) select.value = preferred;
}

function checkpoint() {
  history[surface].push({ layout: copy(layout()), dirty: dirty.has(surface) });
  if (history[surface].length > 30) history[surface].shift();
  dirty.add(surface);
}

function edit(change) {
  checkpoint();
  change();
  render();
  say("Draft changes. Apply to update the TV.");
}

function overlaps(a, b) {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

function fits(candidate, ignore, board = page()) {
  const { x, y, w, h } = candidate;
  return [x, y, w, h].every(Number.isInteger) && x >= 0 && y >= 0 && y < maxRows &&
    w > 0 && h > 0 && h <= screenRows && y + h <= maxRows && x + w <= board.columns &&
    !board.cards.some(card => card.id !== ignore && overlaps(candidate, card));
}

function placement(card, candidate, allowSwap) {
  if (fits(candidate, card.id)) return { candidate };
  if (!allowSwap) return null;
  const board = page();
  const occupied = board.cards.filter(other => other.id !== card.id && overlaps(candidate, other));
  if (occupied.length !== 1) return null;
  const [other] = occupied;
  const to = { ...candidate, x: other.x, y: other.y };
  const from = { ...other, x: card.x, y: card.y };
  const remaining = { ...board, cards: board.cards.filter(item => item !== card && item !== other) };
  return !overlaps(to, from) && fits(to, card.id, remaining) && fits(from, other.id, remaining)
    ? { candidate: to, other, from }
    : null;
}

function commitPlacement(card, result) {
  edit(() => {
    Object.assign(card, result.candidate);
    if (result.other) Object.assign(result.other, result.from);
  });
}

function findSpace(card) {
  const board = page();
  if (board.cards.length >= 150) return null;
  const bottom = Math.max(0, ...board.cards.map(item => item.y + item.h));
  for (let y = 0; y <= Math.min(bottom, maxRows - card.h); y++) {
    for (let x = 0; x + card.w <= board.columns; x++) {
      if (fits({ ...card, x, y })) return { x, y };
    }
  }
  return null;
}

function newId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
}

function addCard(source) {
  const card = source ? copy(source) : {
    title: "New card", w: 1, h: 2, entities: [], gauges: {}, graphs: {}, subtitles: {}, halfWidthItems: [],
  };
  card.id = newId();
  card.focusOnHome = false;
  const position = findSpace(card);
  if (!position) return say("There is no room for another card of that size.");
  edit(() => {
    page().cards.push(Object.assign(card, position));
    selected = card.id;
  });
}

function friendlyName(id, templateName) {
  return layout().aliases?.[id] || templateName || layout().names?.[id] || names.get(id) || id;
}

function entityName(id, card) {
  if (card.graphs?.[id]) return card.graphs[id].entities.map(entity => friendlyName(entity.id, entity.name)).join(", ");
  return friendlyName(id);
}

function renameEntity(id) {
  const field = node("input");
  field.maxLength = 255;
  field.value = layout().aliases?.[id] || "";
  field.placeholder = "Use Home Assistant or template name";
  field.onchange = () => edit(() => {
    layout().aliases ||= {};
    const value = field.value.trim();
    if (value) layout().aliases[id] = value;
    else delete layout().aliases[id];
  });
  const label = node("label", id);
  label.append(field);
  return label;
}

function displayName(id, card) {
  const name = entityName(id, card);
  if (card.graphs?.[id]) return name + " · History";
  if (card.gauges?.[id]) return name + " · Gauge";
  return name;
}

function positionTile(tile, card) {
  tile.style.gridColumn = (card.x + 1) + " / span " + card.w;
  tile.style.gridRow = (card.y + 1) + " / span " + card.h;
}

function attachGesture(handle, tile, card, resize) {
  handle.onpointerdown = event => {
    if (busy || gesture || !event.isPrimary || event.button !== 0) return;
    const grid = $("grid");
    const viewport = $("grid-viewport");
    const style = getComputedStyle(grid);
    const dx = (grid.clientWidth + parseFloat(style.columnGap)) / page().columns;
    const dy = (viewport.clientHeight + parseFloat(style.rowGap)) / screenRows;
    let candidate = card;
    let moved = false;
    let preview = null;
    function resetPreview() {
      if (preview) positionTile(preview.tile, preview.card);
      preview = null;
      positionTile(tile, card);
    }
    gesture = event.pointerId;
    handle.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startY = event.clientY;
    const startScroll = viewport.scrollTop;
    handle.onpointermove = next => {
      if (next.pointerId !== gesture) return;
      const x = Math.round((next.clientX - startX) / dx);
      const y = Math.round((next.clientY - startY + viewport.scrollTop - startScroll) / dy);
      moved ||= x !== 0 || y !== 0;
      const clamp = (value, max, min = 0) => Math.max(min, Math.min(max, value));
      candidate = resize ? { ...card, w: clamp(card.w + x, page().columns - card.x, 1), h: clamp(card.h + y, Math.min(screenRows, maxRows - card.y), 1) }
        : { ...card, x: clamp(card.x + x, page().columns - card.w), y: clamp(card.y + y, maxRows - card.h) };
      resetPreview();
      const result = placement(card, candidate, !resize);
      tile.classList.toggle("invalid", !result);
      if (!result) return;
      positionTile(tile, result.candidate);
      if (result.other) {
        const otherTile = [...grid.children].find(item => item.dataset.card === result.other.id);
        if (otherTile) {
          preview = { tile: otherTile, card: result.other };
          positionTile(otherTile, result.from);
        }
      }
    };
    function finish(next) {
      if (next.pointerId !== gesture) return;
      gesture = null;
      handle.onpointermove = handle.onpointerup = handle.onpointercancel = handle.onlostpointercapture = null;
      tile.classList.remove("invalid");
      resetPreview();
      if (next.type !== "pointerup" || !moved) return;
      selected = card.id;
      const result = placement(card, candidate, !resize);
      if (result && ["x", "y", "w", "h"].some(key => result.candidate[key] !== card[key])) {
        commitPlacement(card, result);
      } else {
        render();
        if (!result) say("The cards do not fit in those positions.");
      }
    }
    handle.onpointerup = handle.onpointercancel = handle.onlostpointercapture = finish;
  };
}

function renderGrid() {
  const grid = $("grid");
  grid.style.setProperty("--screen-rows", screenRows);
  grid.style.gridTemplateColumns = "repeat(" + page().columns + ", minmax(0, 1fr))";
  grid.replaceChildren();
  for (const card of [...page().cards].sort((a, b) => a.y - b.y || a.x - b.x)) {
    const tile = node("article", null, "tile" + (card.id === selected ? " active" : ""));
    tile.dataset.card = card.id;
    const heading = card.title?.trim();
    const title = button(heading || "⠿", () => {
      selected = card.id;
      render();
    }, heading || "Select or move card");
    title.className = heading ? "tile-title" : "tile-title tile-grip";
    const resize = button("↘", () => {
      selected = card.id;
      render();
      $("w").focus();
    }, "Resize " + (card.title || "card"));
    resize.className = "resize";
    tile.append(title);
    if (card.focusOnHome) tile.append(node("p", "Home button"));
    if (card.apps) {
      for (const id of card.apps.slice(0, 8)) tile.append(node("p", appName(id)));
    } else {
      const readings = node("div", null, "tile-readings");
      let pending = false;
      const half = halfWidths(card);
      card.entities.forEach((id, index) => {
        if (card.subtitles?.[index]?.trim()) {
          readings.append(node("p", card.subtitles[index]));
          pending = false;
        }
        const small = half.includes(index) && canUseHalf(card, id);
        readings.append(node("p", displayName(id, card), small ? (pending ? "second-half" : "half") : ""));
        pending = small && !pending;
      });
      if (card.subtitles?.[card.entities.length]) {
        readings.append(node("p", card.subtitles[card.entities.length]));
      }
      tile.append(readings);
    }
    tile.append(resize);
    positionTile(tile, card);
    attachGesture(title, tile, card, false);
    attachGesture(resize, tile, card, true);
    grid.append(tile);
  }
}

function renderEntities() {
  const search = $("search").value.trim().toLowerCase();
  const room = $("room").value;
  const domain = $("domain").value;
  const matches = catalog.filter(entity =>
    (!room || entity.area === room) && (!domain || entity.domain === domain) &&
    (entity.name.toLowerCase().includes(search) || entity.id.includes(search)));
  $("entities").replaceChildren(...matches.slice(0, 80).map(entity => {
    const row = node("li");
    row.append(button(entity.name + " · " + entity.id, () => { $("manual").value = entity.id; }));
    return row;
  }));
  if (matches.length > 80) $("entities").append(node("li", "Search or filter to see more entities."));
}

function canUseHalf(card, id) {
  return !id.startsWith("weather.") && !card.graphs?.[id] && !card.gauges?.[id];
}

function halfWidths(card) {
  return card.halfWidthItems || (card.twoPerRow ? card.entities.flatMap((id, index) => canUseHalf(card, id) ? [index] : []) : []);
}

function editContents(card, order) {
  const half = halfWidths(card);
  const subtitles = {};
  order.forEach((old, index) => {
    if (Object.hasOwn(card.subtitles || {}, old)) subtitles[index] = card.subtitles[old];
  });
  const trailing = card.subtitles?.[card.entities.length];
  if (trailing) subtitles[order.length] = trailing;
  card.halfWidthItems = order.flatMap((old, index) => half.includes(old) ? [index] : []);
  card.entities = order.map(index => card.entities[index]);
  card.subtitles = subtitles;
  for (const map of [card.graphs, card.gauges]) {
    for (const id of Object.keys(map || {})) if (!card.entities.includes(id)) delete map[id];
  }
}

function appName(id) {
  return installedApps.find(app => app.id === id)?.name || id;
}

function renderApps(card) {
  $("apps-per-row").value = card.appsPerRow || 4;
  const choices = installedApps.filter(app => !card.apps.includes(app.id));
  options($("app-choice"), choices.map(app => [app.id, app.name]));
  $("add-app").disabled = choices.length === 0 || card.apps.length >= 250;
  $("selected-apps").replaceChildren(...card.apps.map((id, index) => {
    const row = node("li");
    const name = appName(id);
    const move = by => edit(() => {
      [card.apps[index], card.apps[index + by]] = [card.apps[index + by], card.apps[index]];
    });
    const up = button("↑", () => move(-1), "Move " + name + " up");
    const down = button("↓", () => move(1), "Move " + name + " down");
    up.disabled = index === 0;
    down.disabled = index === card.apps.length - 1;
    row.append(node("span", name), up, down, button("Remove", () => edit(() => card.apps.splice(index, 1))));
    return row;
  }));
}

function renderInspector() {
  const card = active();
  $("inspector").hidden = !card;
  if (!card) return;
  $("title").value = card.title;
  $("home-focus").checked = !!card.focusOnHome;
  $("entity-settings").hidden = !!card.apps;
  $("apps-settings").hidden = !card.apps;
  $("duplicate").hidden = !!card.apps;
  for (const key of ["x", "y", "w", "h"]) $(key).value = card[key] + (key === "x" || key === "y" ? 1 : 0);
  $("x").max = page().columns;
  $("y").max = maxRows - card.h + 1;
  $("w").max = page().columns;
  $("h").max = Math.min(screenRows, maxRows - card.y);
  if (card.apps) return renderApps(card);
  $("end-heading").value = card.subtitles?.[card.entities.length] || "";
  $("contents").replaceChildren(...card.entities.map((id, index) => {
    const label = displayName(id, card);
    const row = node("li");
    const indices = card.entities.map((_, i) => i);
    function move(by) {
      [indices[index], indices[index + by]] = [indices[index + by], indices[index]];
      edit(() => editContents(card, indices));
    }
    const up = button("↑", () => move(-1), "Move " + label + " up");
    const down = button("↓", () => move(1), "Move " + label + " down");
    up.disabled = index === 0;
    down.disabled = index === indices.length - 1;
    row.append(node("span", label), up, down,
      button("Remove", () => edit(() => editContents(card, indices.filter(i => i !== index))), "Remove " + label));
    const heading = node("input");
    heading.maxLength = 160;
    heading.value = card.subtitles?.[index] || "";
    heading.onchange = () => setHeading(card, index, heading.value);
    const headingLabel = node("label", "Optional subheading before " + label);
    headingLabel.className = "entity-heading";
    headingLabel.append(heading);
    row.append(headingLabel);
    if (canUseHalf(card, id)) {
      const size = node("input");
      size.type = "checkbox";
      size.checked = halfWidths(card).includes(index);
      size.onchange = () => edit(() => {
        const half = halfWidths(card).filter(position => position !== index);
        if (size.checked) half.push(index);
        card.halfWidthItems = half;
      });
      const width = node("label");
      width.append(size, document.createTextNode("Half width"));
      row.append(width);
    }
    const rename = node("details");
    rename.append(node("summary", "Friendly name"));
    const ids = card.graphs?.[id]?.entities.map(entity => entity.id) || [id];
    rename.append(...ids.map(renameEntity));
    row.append(rename);
    return row;
  }));
}

function setHeading(card, index, value) {
  edit(() => {
    card.subtitles ||= {};
    const text = value.trim();
    if (text) card.subtitles[index] = text;
    else delete card.subtitles[index];
  });
}

$("end-heading").onchange = () => {
  const card = active();
  setHeading(card, card.entities.length, $("end-heading").value);
};

function render() {
  $("workspace").hidden = false;
  $("surface").value = surface;
  options($("page"), layout().pages.map((view, index) => [String(index), view.title || "Untitled view"]), String(pageIndex));
  $("page-title").value = page().title;
  $("columns").value = page().columns;
  $("remove-page").disabled = layout().pages.length === 1;
  $("add-page").disabled = layout().pages.length >= 32;
  $("dock-label").hidden = surface !== "assistant";
  $("add-apps").hidden = surface !== "home" || layout().pages.some(view => view.cards.some(card => card.apps));
  $("dock").checked = layout().bottomRow;
  $("camera").hidden = surface !== "home" || !camera;
  $("apply").disabled = busy || !dirty.has(surface);
  $("restore").disabled = busy || !canRestore;
  $("undo").disabled = busy || history[surface].length === 0;
  $("workspace").inert = busy;
  renderGrid();
  renderInspector();
}

async function request(path, data) {
  let response;
  try {
    response = await fetch("/api/" + path, {
      signal: AbortSignal.timeout(10_000),
      ...(data === undefined ? {} : {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data),
      }),
    });
  } catch {
    throw new Error("No response from HAL. Check that Enable Web Editor is on.");
  }
  const json = await response.json().catch(() => null);
  if (!response.ok || !json) throw new Error(json?.error || "HAL returned an unexpected response.");
  return json;
}

async function loadState() {
  let state = await request("state");
  while (state.loading) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    state = await request("state");
  }
  revision = state.revision;
  canRestore = state.canRestore;
  camera = state.camera;
  installedApps = [{ id: allAppsId, name: "All apps" }, ...(state.apps || [])];
  for (const key of ["assistant", "home"]) {
    drafts[key] = state[key];
    history[key] = [];
  }
  dirty.clear();
  pageIndex = 0;
  selected = null;
}

$("surface").onchange = () => {
  surface = $("surface").value;
  pageIndex = 0;
  selected = null;
  render();
  say(dirty.has(surface) ? "Draft changes. Apply to update the TV." : "");
};
$("page").onchange = () => { pageIndex = Number($("page").value); selected = null; render(); };
$("page-title").onchange = () => edit(() => { page().title = $("page-title").value.trim(); });
$("columns").onchange = () => {
  const columns = $("columns").valueAsNumber;
  if (!Number.isInteger(columns) || columns < 1 || columns > 6 ||
      page().cards.some(card => card.x + card.w > columns)) {
    $("columns").value = page().columns;
    return say("Move or narrow the cards before removing their columns.");
  }
  edit(() => { page().columns = columns; });
};
$("dock").onchange = () => edit(() => { layout().bottomRow = $("dock").checked; });
$("add-page").onclick = () => edit(() => {
  layout().pages.push({ title: "New view", columns: page().columns, cards: [] });
  pageIndex = layout().pages.length - 1;
  selected = null;
});
$("remove-page").onclick = () => {
  if (page().cards.length && !confirm("Remove this view and all its cards?")) return;
  edit(() => { layout().pages.splice(pageIndex, 1); pageIndex = Math.max(0, pageIndex - 1); selected = null; });
};
$("add-card").onclick = () => addCard();
$("add-apps").onclick = () => addCard({ title: "Apps", w: page().columns, h: 4, entities: [], apps: [], appsPerRow: 4 });
$("app-choice").onchange = () => { $("add-app").disabled = !$("app-choice").value; };
$("add-app").onclick = () => {
  const id = $("app-choice").value;
  const card = active();
  if (id && !card.apps.includes(id) && card.apps.length < 250) edit(() => card.apps.push(id));
};
$("apps-per-row").onchange = () => {
  const count = $("apps-per-row").valueAsNumber;
  if (Number.isInteger(count) && count >= 1 && count <= 8) edit(() => { active().appsPerRow = count; });
  else renderInspector();
};
$("home-focus").onchange = () => edit(() => {
  const target = active();
  for (const view of layout().pages) for (const card of view.cards) card.focusOnHome = false;
  target.focusOnHome = $("home-focus").checked;
});
$("duplicate").onclick = () => addCard(active());
$("remove").onclick = () => edit(() => {
  page().cards = page().cards.filter(card => card.id !== selected);
  selected = null;
});
$("title").onchange = () => edit(() => { active().title = $("title").value.trim(); });
for (const key of ["x", "y", "w", "h"]) {
  $(key).onchange = () => {
    const card = active();
    const value = $(key).valueAsNumber - (key === "x" || key === "y" ? 1 : 0);
    const result = placement(card, { ...card, [key]: value }, key === "x" || key === "y");
    if (result) commitPlacement(card, result);
    else { renderInspector(); say("That size or position does not fit."); }
  };
}
$("search").oninput = $("room").onchange = $("domain").onchange = renderEntities;
$("display").onchange = () => {
  $("gauge-fields").hidden = $("display").value !== "gauge";
  $("history-fields").hidden = $("display").value !== "history";
};
$("add-entity").onclick = () => {
  const card = active();
  const id = $("manual").value.trim();
  if (!/^[a-z0-9_]+\.[a-z0-9_]+$/.test(id)) return say("Enter a Home Assistant entity ID.");
  if (card.entities.length >= 250) return say("This card has reached its entity limit.");
  const kind = $("display").value;
  const min = $("gauge-min").valueAsNumber;
  const max = $("gauge-max").valueAsNumber;
  const hours = $("history-hours").valueAsNumber;
  if (kind === "gauge" && !(max > min)) return say("The gauge maximum must be greater than the minimum.");
  if (kind === "history" && !(hours >= 1 && hours <= 8760)) return say("Enter 1 to 8760 history hours.");
  if (kind === "entity" && card.entities.includes(id)) return say("This entity is already in the card.");
  edit(() => {
    const last = card.entities.length;
    if (kind === "history") {
      const key = "graph:" + newId();
      card.graphs ||= {};
      card.graphs[key] = { entities: [{ id }], hours, fitY: true };
      card.entities.push(key);
    } else {
      if (kind === "gauge") {
        card.gauges ||= {};
        card.gauges[id] = { min, max, levels: [], needle: false };
      }
      if (!card.entities.includes(id)) card.entities.push(id);
    }
    if (card.entities.length > last && card.subtitles?.[last]) {
      card.subtitles[card.entities.length] = card.subtitles[last];
      delete card.subtitles[last];
    }
  });
  $("manual").value = "";
};
$("template").onchange = () => { $("use-template").disabled = $("template").value === ""; };
$("use-template").onclick = () => {
  const template = templates[Number($("template").value)];
  if (!template || !confirm("Replace the current surface’s draft with this template?")) return;
  const { bottomRow, aliases } = layout();
  edit(() => {
    const apps = surface === "home" ? layout().pages.flatMap(view => view.cards).find(card => card.apps) : null;
    drafts[surface] = { ...copy(template.layout), bottomRow, aliases };
    if (apps) {
      const first = layout().pages[0];
      const height = Math.max(0, ...first.cards.map(card => card.y + card.h));
      if (height + apps.h <= maxRows && first.cards.length < 150) {
        first.cards.push({ ...apps, x: 0, y: height, w: Math.min(first.columns, apps.w) });
      } else {
        layout().pages.push({title: "Apps", columns: first.columns, cards: [{ ...apps, x: 0, y: 0, w: Math.min(first.columns, apps.w) }]});
      }
    }
    selected = null;
    pageIndex = 0;
  });
  if (template.skipped.length) say("Unsupported template cards: " + template.skipped.join(", "));
};
$("undo").onclick = () => {
  const entry = history[surface].pop();
  drafts[surface] = entry.layout;
  if (!entry.dirty) dirty.delete(surface);
  pageIndex = Math.min(pageIndex, layout().pages.length - 1);
  if (!active()) selected = null;
  render();
  say(dirty.has(surface) ? "Draft changes. Apply to update the TV." : "");
};
$("apply").onclick = async () => {
  const result = $("apply-result");
  const label = surface === "home" ? "Home panel" : "Assistant tab";
  function report(state, message) {
    result.hidden = false;
    result.dataset.state = state;
    result.textContent = message;
  }
  busy = true;
  render();
  report("pending", `Applying ${label} to the TV…`);
  try {
    const saved = await request("apply", { revision, surface, layout: layout() });
    if (typeof saved.revision !== "string" || saved.applied !== true) {
      throw new Error("HAL did not confirm the update.");
    }
    revision = saved.revision;
    const hasTarget = layout().pages.some(view => view.cards.some(card => card.focusOnHome));
    if (hasTarget) {
      const other = surface === "home" ? "assistant" : "home";
      for (const view of drafts[other].pages) for (const card of view.cards) card.focusOnHome = false;
      history[other] = [];
    }
    canRestore = true;
    dirty.delete(surface);
    history[surface] = [];
    say("");
    report("success", `${label} layout applied to the TV.`);
  } catch (error) {
    report("error", `Could not confirm apply for ${label}. ${error.message}`);
  }
  finally { busy = false; render(); }
};
$("restore").onclick = async () => {
  if (!confirm("Restore the previous TV layout and discard any open drafts?")) return;
  busy = true;
  render();
  try {
    await request("restore", { revision });
    await loadState();
    say("Previous layout restored.");
  } catch (error) { say(error.message); }
  finally { busy = false; render(); }
};
window.addEventListener("beforeunload", event => {
  if (dirty.size) { event.preventDefault(); event.returnValue = ""; }
});

async function start() {
  try {
    await loadState();
    const data = await request("catalog");
    catalog = data.entities;
    names = new Map(catalog.map(entity => [entity.id, entity.name]));
    templates = data.templates;
    notice = data.notice || "";
    const filterOptions = field => [...new Set(catalog.map(entity => entity[field]).filter(Boolean))].sort().map(value => [value, value]);
    options($("room"), [["", "All rooms"], ...filterOptions("area")]);
    options($("domain"), [["", "All types"], ...filterOptions("domain")]);
    options($("template"), [["", "Choose a template"], ...templates.map((template, index) => [String(index), template.title])]);
    renderEntities();
    render();
    say("");
  } catch (error) {
    say("Could not open the editor. Check the TV and reload this page. " + error.message);
  }
}
start();
