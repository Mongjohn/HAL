"use strict";

const $ = id => document.getElementById(id);
const maxRows = 360;
const screenRows = 15;
const maxScreenColumns = 6;
const viewport = $("grid-viewport");
const drafts = {};
const history = { assistant: [], home: [] };
const dirty = new Set();
const allAppsId = "dock:all_apps";
let installedApps = [];
let revision = null;
let canRestore = {};
let camera = false;
let surface = "home";
let pageIndex = 0;
let selected = null;
let inspectedCard = null;
let displayedPage = null;
let inspectorSection = "content";
let catalog = [];
let templates = [];
let names = new Map();
let busy = false;
let cancelGesture = null;
let notice = "";

const copy = value => JSON.parse(JSON.stringify(value));
const layout = () => drafts[surface];
const page = () => layout().pages[pageIndex];
const visibleColumns = view => view.columnsPerScreen ?? Math.min(view.columns, maxScreenColumns);
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

function edit(change, refresh = render) {
  checkpoint();
  change();
  $("apply").disabled = busy;
  $("undo").disabled = busy;
  refresh();
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

function columnPlacement(card, x) {
  if (!Number.isInteger(x) || x < 0 || x + card.w > page().columns) return null;
  for (let distance = 0; distance <= maxRows - card.h; distance++) {
    const below = { ...card, x, y: card.y + distance };
    if (fits(below, card.id)) return { candidate: below };
    if (distance === 0) continue;
    const above = { ...card, x, y: card.y - distance };
    if (fits(above, card.id)) return { candidate: above };
  }
  return null;
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
  revealCard(card);
}

function friendlyName(id, templateName) {
  return layout().aliases?.[id] || templateName || layout().names?.[id] || names.get(id) || id;
}

function entityName(id, card) {
  if (card.graphs?.[id]) return card.graphs[id].entities.map(entity => friendlyName(entity.id, entity.name)).join(", ");
  return friendlyName(id);
}

function renameEntity(id, labelText, onRename) {
  const field = node("input");
  field.maxLength = 255;
  field.value = layout().aliases?.[id] || "";
  field.placeholder = "Use Home Assistant or template name";
  field.onchange = () => edit(() => {
    layout().aliases ||= {};
    const value = field.value.trim();
    field.value = value;
    if (value) layout().aliases[id] = value;
    else delete layout().aliases[id];
  }, onRename);
  const label = node("label", labelText);
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

function cardTile(card) {
  return [...$("grid").children].find(element => element.dataset.card === card.id);
}

function revealCard(card) {
  const tile = cardTile(card);
  const bounds = tile.getBoundingClientRect();
  const view = viewport.getBoundingClientRect();
  const left = bounds.left - view.left;
  const right = bounds.right - view.left - viewport.clientWidth;
  const top = bounds.top - view.top;
  const bottom = bounds.bottom - view.top - viewport.clientHeight;
  viewport.scrollBy(left < 0 ? left : Math.max(0, Math.min(left, right)),
    top < 0 ? top : Math.max(0, Math.min(top, bottom)));
}

function edgeSpeed(position, start, size) {
  const edge = Math.min(36, size / 4);
  if (!edge) return 0;
  const offset = position - start;
  if (offset < edge) return -Math.min(18, Math.ceil((edge - offset) / 2));
  if (offset > size - edge) return Math.min(18, Math.ceil((offset - size + edge) / 2));
  return 0;
}

function attachGesture(handle, card, resize) {
  handle.onpointerdown = event => {
    if (busy || cancelGesture || !event.isPrimary || event.button !== 0) return;
    event.preventDefault();
    // A pending field edit can replace the card's DOM as it commits on blur.
    document.activeElement?.blur();
    const tile = cardTile(card);
    if (!tile || !page().cards.includes(card)) return;
    const style = getComputedStyle($("grid"));
    const dx = parseFloat(style.gridTemplateColumns) + parseFloat(style.columnGap);
    const dy = parseFloat(style.gridTemplateRows) + parseFloat(style.rowGap);
    if (!(dx > 0 && dy > 0)) return;
    let candidate = card;
    let moved = false;
    let preview = null;
    function resetPreview() {
      if (preview) positionTile(preview.tile, preview.card);
      preview = null;
      positionTile(tile, card);
    }
    const pointerId = event.pointerId;
    viewport.setPointerCapture(pointerId);
    const listeners = new AbortController();
    const listen = { signal: listeners.signal };
    const startX = event.clientX;
    const startY = event.clientY;
    const startLeft = viewport.scrollLeft;
    const startTop = viewport.scrollTop;
    let frame = 0;
    let pointer = event;
    function updatePreview() {
      const x = Math.round((pointer.clientX - startX + viewport.scrollLeft - startLeft) / dx);
      const y = Math.round((pointer.clientY - startY + viewport.scrollTop - startTop) / dy);
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
        const otherTile = cardTile(result.other);
        if (otherTile) {
          preview = { tile: otherTile, card: result.other };
          positionTile(otherTile, result.from);
        }
      }
    }
    function scrollAtEdge() {
      frame = 0;
      const bounds = viewport.getBoundingClientRect();
      const left = viewport.scrollLeft;
      const top = viewport.scrollTop;
      viewport.scrollBy(edgeSpeed(pointer.clientX, bounds.left, viewport.clientWidth),
        edgeSpeed(pointer.clientY, bounds.top, viewport.clientHeight));
      if (left !== viewport.scrollLeft || top !== viewport.scrollTop) {
        updatePreview();
        frame = requestAnimationFrame(scrollAtEdge);
      }
    }
    document.addEventListener("pointermove", next => {
      if (next.pointerId !== pointerId) return;
      pointer = next;
      updatePreview();
      if (!frame && (moved || Math.hypot(next.clientX - startX, next.clientY - startY) > 4)) {
        frame = requestAnimationFrame(scrollAtEdge);
      }
    }, listen);
    cancelGesture = () => {
      cancelAnimationFrame(frame);
      listeners.abort();
      cancelGesture = null;
      if (viewport.hasPointerCapture(pointerId)) viewport.releasePointerCapture(pointerId);
      tile.classList.remove("invalid");
      resetPreview();
    };
    function finish(next) {
      if (next.pointerId !== pointerId) return;
      cancelGesture();
      if (next.type !== "pointerup") return;
      if (!moved) {
        handle.click();
        return;
      }
      selected = card.id;
      const result = placement(card, candidate, !resize);
      if (result && ["x", "y", "w", "h"].some(key => result.candidate[key] !== card[key])) {
        commitPlacement(card, result);
      } else {
        render();
        if (!result) say("The cards do not fit in those positions.");
      }
    }
    document.addEventListener("pointerup", finish, listen);
    document.addEventListener("pointercancel", finish, listen);
    viewport.addEventListener("lostpointercapture", finish, listen);
    window.addEventListener("blur", cancelGesture, listen);
  };
}

function renderGrid() {
  cancelGesture?.();
  const grid = $("grid");
  grid.style.setProperty("--screen-rows", screenRows);
  grid.style.setProperty("--column-ratio", page().columns / visibleColumns(page()));
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
      showInspectorSection("layout");
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
    attachGesture(title, card, false);
    attachGesture(resize, card, true);
    grid.append(tile);
  }
}

const entityIdPattern = /^[a-z0-9_]+\.[a-z0-9_]+$/;
let pickerCount = 0;

function entityPicker(value = "", update = null) {
  const wrapper = node("div", null, "entity-picker");
  const label = node("label", "Entity");
  const input = node("input");
  input.value = value;
  input.maxLength = 255;
  input.autocomplete = "off";
  input.placeholder = "Search or enter entity ID";
  input.setAttribute("role", "combobox");
  input.setAttribute("aria-autocomplete", "list");
  label.append(input);

  const popup = node("div", null, "entity-picker-options");
  const filters = node("div", null, "controls");
  const room = node("select");
  const type = node("select");
  for (const [name, select] of [["Room", room], ["Type", type]]) {
    const field = node("label", name);
    field.append(select);
    filters.append(field);
  }
  const results = node("div", null, "entity-picker-results");
  results.id = "entity-picker-" + ++pickerCount;
  results.tabIndex = -1;
  results.setAttribute("role", "listbox");
  results.setAttribute("aria-label", "Matching entities");
  input.setAttribute("aria-controls", results.id);
  const status = node("div", null, "hint");
  status.setAttribute("role", "status");
  popup.append(filters, results, status);
  wrapper.append(label, popup);
  let matches = [];
  let highlighted = -1;
  let query = "";

  function commit(id) {
    input.value = id;
    if (update && id !== value) {
      value = id;
      update(id);
    }
  }
  function close() {
    popup.hidden = true;
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
  }
  function choose(id) {
    commit(id);
    input.focus();
    close();
  }
  function resultsForQuery() {
    const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    const found = catalog.filter(entity =>
      (!room.value || entity.area === room.value) && (!type.value || entity.domain === type.value) &&
      words.every(word => (entity.name + " " + entity.id).toLowerCase().includes(word)));
    matches = found.slice(0, 60);
    highlighted = -1;
    input.removeAttribute("aria-activedescendant");
    results.replaceChildren(...matches.map((entity, index) => {
      const option = node("div");
      option.id = results.id + "-" + index;
      option.setAttribute("role", "option");
      option.setAttribute("aria-selected", "false");
      option.append(node("span", entity.name), node("small", entity.id));
      option.onpointerdown = event => event.preventDefault();
      option.onclick = () => choose(entity.id);
      return option;
    }));
    status.textContent = !found.length ? "No matching entities." :
      found.length > matches.length ? "Search or filter to see more entities." : "";
  }
  function open() {
    for (const [select, field, all] of [[room, "area", "All rooms"], [type, "domain", "All types"]]) {
      const values = [...new Set(catalog.map(entity => entity[field]).filter(Boolean))].sort();
      options(select, [["", all], ...values.map(value => [value, value])]);
    }
    popup.hidden = false;
    input.setAttribute("aria-expanded", "true");
    resultsForQuery();
  }
  input.onfocus = input.onclick = () => {
    if (popup.hidden) {
      query = entityIdPattern.test(input.value) ? "" : input.value;
      open();
    }
  };
  input.oninput = () => {
    query = input.value;
    if (popup.hidden) open();
    else resultsForQuery();
  };
  input.onchange = () => {
    const id = input.value.trim();
    if (!id || entityIdPattern.test(id)) commit(id);
  };
  room.onchange = type.onchange = resultsForQuery;
  wrapper.onfocusout = event => {
    if (wrapper.contains(event.relatedTarget)) return;
    const id = input.value.trim();
    if (!id || entityIdPattern.test(id)) commit(id);
    else if (update) input.value = value;
    close();
  };
  input.onkeydown = event => {
    if (event.key === "Escape" && !popup.hidden) {
      event.preventDefault();
      if (update) input.value = value;
      close();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (popup.hidden) open();
      if (!matches.length) return;
      const shift = event.key === "ArrowDown" ? 1 : -1;
      const next = highlighted < 0 && shift < 0 ? matches.length - 1 : highlighted + shift;
      results.children[highlighted]?.setAttribute("aria-selected", "false");
      highlighted = Math.max(0, Math.min(matches.length - 1, next));
      const option = results.children[highlighted];
      option.setAttribute("aria-selected", "true");
      input.setAttribute("aria-activedescendant", option.id);
      option.scrollIntoView({ block: "nearest" });
    } else if (event.key === "Enter" && !popup.hidden) {
      event.preventDefault();
      if (highlighted >= 0) choose(matches[highlighted].id);
      else if (entityIdPattern.test(input.value.trim())) choose(input.value.trim());
    }
  };
  close();
  return { wrapper, input };
}

const contentEntity = entityPicker();
$("content-entity-picker").replaceChildren(contentEntity.wrapper);

function canUseHalf(card, id) {
  const mediaOnly = card.entities.length > 0 &&
    card.entities.every(entity => entity.startsWith("media_player.") && !card.graphs?.[entity] && !card.gauges?.[entity]);
  return !mediaOnly && !id.startsWith("weather.") && !card.graphs?.[id] && !card.gauges?.[id];
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
  const visibility = card.itemVisibility || {};
  card.itemVisibility = Object.fromEntries(order.flatMap((old, index) =>
    visibility[old] ? [[index, visibility[old]]] : []));
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
  const openItems = openItemVisibility($("selected-apps"));
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
    const controls = node("div", null, "entity-actions");
    controls.append(up, down, button("Remove", () => edit(() => {
      card.apps.splice(index, 1);
      if (card.itemVisibility) delete card.itemVisibility[id];
    }), "Remove " + name));
    row.append(node("span", name, "item-name"), controls, itemVisibilityEditor(card, id, openItems));
    return row;
  }));
}

const newStateRule = () => ({ condition: "state", entity: "", state: [""] });
const newRuleGroup = () => ({ condition: "and", conditions: [newStateRule()] });

function visibilityField(label, value, update) {
  const field = node("input");
  field.maxLength = 255;
  field.value = value;
  field.onchange = () => {
    if (value === field.value) return;
    checkpoint();
    update(field.value);
    value = field.value;
    $("apply").disabled = false;
    $("undo").disabled = false;
    say("Draft changes. Apply to update the TV.");
  };
  const wrapper = node("label", label);
  wrapper.append(field);
  return wrapper;
}

function renderVisibilityRule(rule, remove, depth = 0) {
  const panel = node("fieldset", null, "visibility-rule");
  panel.append(node("legend", rule.condition === "state" ? "Entity state" : "Condition group"));
  const select = node("select");
  if (rule.condition === "state") {
    panel.append(entityPicker(rule.entity, value => edit(() => { rule.entity = value; }, () => {})).wrapper);
    const comparison = "state_not" in rule ? "state_not" : "state";
    const values = rule[comparison];
    options(select, [["state", "Is any of"], ["state_not", "Is none of"]], comparison);
    select.onchange = () => edit(() => {
      delete rule[comparison];
      rule[select.value] = values;
    });
    const label = node("label", "Comparison");
    label.append(select);
    panel.append(label);
    values.forEach((value, index) => {
      const row = node("div", null, "controls");
      row.append(visibilityField(rule.homeAssistantComparison ? "State or entity ID" : "State", value, next => { values[index] = next; }));
      if (values.length > 1) row.append(button("Remove state", () => edit(() => values.splice(index, 1))));
      panel.append(row);
    });
    if (values.length < 32) panel.append(button("Add state", () => edit(() => values.push(""))));
  } else {
    options(select, [["and", "All conditions match"], ["or", "Any condition matches"], ["not", "At least one condition fails"]], rule.condition);
    select.onchange = () => edit(() => { rule.condition = select.value; });
    const label = node("label", "Show when");
    label.append(select);
    panel.append(label);
    rule.conditions.forEach((child, index) => {
      panel.append(renderVisibilityRule(child, () => {
        rule.conditions.splice(index, 1);
        if (!rule.conditions.length) remove();
      }, depth + 1));
    });
    if (depth < 8 && rule.conditions.length < 32) {
      panel.append(button("Add condition", () => edit(() => rule.conditions.push(newStateRule()))));
      if (depth < 7) panel.append(button("Add group", () => edit(() => rule.conditions.push(newRuleGroup()))));
    }
  }
  panel.append(button(depth === 0 ? "Clear rules" : "Remove rule", () => edit(remove)));
  return panel;
}

function visibilityEditor(rule, setRule) {
  return rule
    ? renderVisibilityRule(rule, () => setRule(null))
    : button("Add visibility rules", () => edit(() => setRule(newRuleGroup())));
}

function renderVisibility(card) {
  $("visibility-status").textContent = card.visibility ? "Show this card when its rules match." : "This card is always visible.";
  $("visibility-rules").replaceChildren(visibilityEditor(card.visibility, rule => {
    if (rule) card.visibility = rule;
    else delete card.visibility;
  }));
}

function openItemVisibility(list) {
  return new Set([...list.querySelectorAll("details[data-item-visibility][open]")].map(details => details.dataset.itemVisibility));
}

function itemVisibilityEditor(card, key, openItems) {
  const details = node("details", null, "inspector-disclosure");
  details.dataset.itemVisibility = key;
  details.open = openItems.has(key);
  const setting = card.itemVisibility?.[key];
  details.append(node("summary", setting ? "Visibility · conditional" : "Visibility"));
  details.append(visibilityEditor(setting?.rule, rule => {
    if (rule) {
      card.itemVisibility ||= {};
      card.itemVisibility[key] = { rule, keepSpace: false };
    } else {
      delete card.itemVisibility[key];
    }
  }));
  if (setting) {
    const space = node("select");
    options(space, [["release", "Release space"], ["keep", "Keep space empty"]], setting.keepSpace ? "keep" : "release");
    space.onchange = () => edit(() => { setting.keepSpace = space.value === "keep"; }, () => {});
    const label = node("label", "When this item is hidden");
    label.append(space);
    details.append(label);
  }
  return details;
}

function validVisibility(rule) {
  if (rule.condition === "state") {
    const values = rule.state || rule.state_not;
    return entityIdPattern.test(rule.entity) && values.length > 0 && values.every(value => value.length > 0);
  }
  return rule.conditions.length > 0 && rule.conditions.every(validVisibility);
}

const inspectorButtons = [...$("card-sections").querySelectorAll("button")];

function showInspectorSection(section) {
  inspectorSection = section;
  for (const button of inspectorButtons) {
    const current = button.dataset.section === section;
    button.setAttribute("aria-pressed", String(current));
    $(button.getAttribute("aria-controls")).hidden = !current;
  }
}

for (const button of inspectorButtons) {
  button.onclick = () => showInspectorSection(button.dataset.section);
}

function renderInspector() {
  const card = active();
  $("inspector-empty").hidden = !!card;
  $("inspector-body").hidden = !card;
  const sameCard = card?.id === inspectedCard;
  if (!sameCard) {
    inspectedCard = card?.id;
    $("contents").replaceChildren();
    $("selected-apps").replaceChildren();
    contentEntity.input.value = "";
    $("end-heading-settings").open = false;
    $("add-entities").open = !!card && card.entities.length === 0;
    inspectorSection = "content";
  }
  if (!card) return;
  showInspectorSection(inspectorSection);
  $("title").value = card.title;
  $("home-focus").checked = !!card.focusOnHome;
  $("entity-settings").hidden = !!card.apps;
  $("apps-settings").hidden = !card.apps;
  $("apps-per-row-setting").hidden = !card.apps;
  $("duplicate").hidden = !!card.apps;
  for (const key of ["x", "y", "w", "h"]) $(key).value = card[key] + (key === "x" || key === "y" ? 1 : 0);
  $("x").max = page().columns;
  $("y").max = maxRows - card.h + 1;
  $("w").max = page().columns;
  $("h").max = Math.min(screenRows, maxRows - card.y);
  renderVisibility(card);
  if (card.apps) return renderApps(card);
  $("end-heading").value = card.subtitles?.[card.entities.length] || "";
  const openRows = new Set([...$("contents").querySelectorAll("details[open]")].map(details => details.dataset.row));
  const openItems = openItemVisibility($("contents"));
  const refreshRowNames = [];
  $("contents").replaceChildren(...card.entities.map((id, index) => {
    const label = displayName(id, card);
    const row = node("li");
    const name = node("span", label, "item-name");
    const indices = card.entities.map((_, i) => i);
    function move(by) {
      [indices[index], indices[index + by]] = [indices[index + by], indices[index]];
      edit(() => editContents(card, indices));
    }
    const up = button("↑", () => move(-1), "Move " + label + " up");
    const down = button("↓", () => move(1), "Move " + label + " down");
    up.disabled = index === 0;
    down.disabled = index === indices.length - 1;
    const remove = button("Remove", () => edit(() => editContents(card, indices.filter(i => i !== index))), "Remove " + label);
    const controls = node("div", null, "entity-actions");
    controls.append(up, down, remove);
    row.append(name, controls);
    const details = node("details", null, "inspector-disclosure");
    details.dataset.row = index + ":" + id;
    details.open = openRows.has(details.dataset.row);
    details.append(node("summary", "Friendly name & row settings"));
    const settings = node("div", null, "inspector-panel");
    const ids = card.graphs?.[id]?.entities.map(entity => entity.id) || [id];
    refreshRowNames.push(() => {
      const updated = displayName(id, card);
      name.textContent = updated;
      up.setAttribute("aria-label", "Move " + updated + " up");
      down.setAttribute("aria-label", "Move " + updated + " down");
      remove.setAttribute("aria-label", "Remove " + updated);
    });
    settings.append(...ids.map(entityId => renameEntity(entityId, ids.length === 1 ? "Friendly name" : "Friendly name for " + entityId, () => {
      refreshRowNames.forEach(refresh => refresh());
      renderGrid();
    })));
    const heading = node("input");
    heading.maxLength = 160;
    heading.value = card.subtitles?.[index] || "";
    heading.onchange = () => setHeading(card, index, heading.value);
    const headingLabel = node("label", "Subheading before this item");
    heading.placeholder = "Optional";
    headingLabel.append(heading);
    settings.append(headingLabel);
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
      settings.append(width);
    }
    details.append(settings);
    row.append(details, itemVisibilityEditor(card, String(index), openItems));
    return row;
  }));
}

function setHeading(card, index, value) {
  edit(() => {
    card.subtitles ||= {};
    const text = value.trim();
    if (text) card.subtitles[index] = text;
    else delete card.subtitles[index];
  }, renderGrid);
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
  const available = Math.min(maxScreenColumns, page().columns);
  options($("columns-per-screen"), [["", "Auto (" + available + ")"],
    ...Array.from({ length: available }, (_, index) => [String(index + 1), String(index + 1)])],
  String(page().columnsPerScreen ?? ""));
  $("remove-page").disabled = layout().pages.length === 1;
  $("add-page").disabled = layout().pages.length >= 64;
  $("dock-label").hidden = surface !== "assistant";
  $("add-apps").hidden = surface !== "home" || layout().pages.some(view => view.cards.some(card => card.apps));
  $("dock").checked = layout().bottomRow;
  $("camera").hidden = surface !== "home" || !camera;
  $("apply").disabled = busy || !dirty.has(surface);
  $("restore").disabled = busy || !canRestore[surface];
  $("restore").textContent = `Restore previous ${surface === "home" ? "Home" : "Assistant"} layout`;
  $("undo").disabled = busy || history[surface].length === 0;
  $("workspace").inert = busy;
  $("settings-transfer").inert = busy;
  renderGrid();
  if (displayedPage !== page()) {
    displayedPage = page();
    viewport.scrollTo(0, 0);
  }
  renderInspector();
}

const settingsRequestLimit = 4 * 1024 * 1024;

async function request(path, data) {
  const body = data === undefined ? undefined : JSON.stringify(data);
  if (path.startsWith("settings/") && body !== undefined && new Blob([body]).size > settingsRequestLimit) {
    throw new Error("The settings and selection exceed the 4 MiB request limit.");
  }
  let response;
  try {
    response = await fetch("/api/" + path, {
      signal: AbortSignal.timeout(10_000),
      ...(data === undefined ? {} : {
        method: "POST", headers: { "Content-Type": "application/json" }, body,
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
  const deadline = Date.now() + 35_000;
  let state = await request("state");
  while (state.loading && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 1000));
    state = await request("state");
  }
  if (state.loading || typeof state.revision !== "string" || !state.home?.pages || !state.assistant?.pages || !state.canRestore) {
    throw new Error("HAL did not return the current layouts.");
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
  const input = $("columns");
  const columns = input.valueAsNumber;
  if (!input.validity.valid || !Number.isInteger(columns)) {
    input.value = page().columns;
    return say("Enter a total between 1 and " + input.max + " columns.");
  }
  if (page().cards.some(card => card.x + card.w > columns)) {
    $("columns").value = page().columns;
    return say("Move or narrow the cards before removing their columns.");
  }
  edit(() => {
    page().columns = columns;
    if (page().columnsPerScreen > columns) page().columnsPerScreen = columns;
  });
};
$("columns-per-screen").onchange = () => edit(() => {
  const value = $("columns-per-screen").value;
  if (value) page().columnsPerScreen = Number(value);
  else delete page().columnsPerScreen;
});
$("dock").onchange = () => edit(() => { layout().bottomRow = $("dock").checked; });
$("add-page").onclick = () => edit(() => {
  layout().pages.push({ title: "New view", columns: page().columns, columnsPerScreen: page().columnsPerScreen, cards: [] });
  pageIndex = layout().pages.length - 1;
  selected = null;
});
$("remove-page").onclick = () => {
  if (page().cards.length && !confirm("Remove this view and all its cards?")) return;
  edit(() => { layout().pages.splice(pageIndex, 1); pageIndex = Math.max(0, pageIndex - 1); selected = null; });
};
$("add-card").onclick = () => addCard();
$("add-apps").onclick = () => addCard({ title: "Apps", w: visibleColumns(page()), h: 4, entities: [], apps: [], appsPerRow: 4 });
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
    const result = key === "x" ? columnPlacement(card, value) :
      placement(card, { ...card, [key]: value }, key === "y");
    if (result) {
      const shifted = key === "x" && result.candidate.y !== card.y;
      commitPlacement(card, result);
      revealCard(card);
      if (shifted) say("Moved to column " + (card.x + 1) + ", row " + (card.y + 1) + ". Apply to update the TV.");
    }
    else { renderInspector(); say("That size or position does not fit."); }
  };
}
$("display").onchange = () => {
  $("gauge-fields").hidden = $("display").value !== "gauge";
  $("history-fields").hidden = $("display").value !== "history";
};
$("add-entity").onclick = () => {
  const card = active();
  const id = contentEntity.input.value.trim();
  if (!entityIdPattern.test(id)) return say("Enter a Home Assistant entity ID.");
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
    if (last === 0 && kind === "entity" && id.startsWith("media_player.") && card.h === 2 &&
        fits({ ...card, h: 5 }, card.id)) card.h = 5;
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
  contentEntity.input.value = "";
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
function report(state, message) {
  const result = $("apply-result");
  result.hidden = false;
  result.dataset.state = state;
  result.textContent = message;
}

$("apply").onclick = async () => {
  const label = surface === "home" ? "Home panel" : "Assistant tab";
  const invalid = layout().pages.flatMap((view, index) => view.cards.flatMap(card => {
    if (card.visibility && !validVisibility(card.visibility)) return [{ index, card, item: null }];
    const item = Object.keys(card.itemVisibility || {}).find(key => !validVisibility(card.itemVisibility[key].rule));
    return item === undefined ? [] : [{ index, card, item }];
  }))[0];
  if (invalid) {
    pageIndex = invalid.index;
    selected = invalid.card.id;
    render();
    if (invalid.item === null) {
      showInspectorSection("visibility");
      inspectorButtons.find(button => button.dataset.section === "visibility").focus();
    } else {
      showInspectorSection("content");
      const item = [...$("inspector-body").querySelectorAll("details[data-item-visibility]")]
        .find(details => details.dataset.itemVisibility === invalid.item);
      if (item) {
        item.open = true;
        item.querySelector("summary").focus();
      }
    }
    report("error", "Complete each visibility rule with an entity ID and state, and add at least one rule to each group.");
    return;
  }
  busy = true;
  render();
  report("pending", `Applying ${label} to the TV…`);
  try {
    const saved = await request("apply", { revision, surface, layout: layout() });
    if (typeof saved.revision !== "string" || saved.applied !== true || saved.surface !== surface || !saved.layout?.pages || !saved.canRestore) {
      throw new Error("HAL did not confirm the update.");
    }
    revision = saved.revision;
    drafts[surface] = saved.layout;
    displayedPage = page();
    reconcileHomeFocus(saved.layout);
    canRestore = saved.canRestore;
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
  const label = surface === "home" ? "Home" : "Assistant";
  if (!confirm(`Restore the previous ${label} layout? Only this surface's draft will be discarded. A restored Home-focus target takes priority over the other surface's target.`)) return;
  busy = true;
  render();
  report("pending", `Restoring the previous ${label} layout…`);
  try {
    const saved = await request("restore", { revision, surface });
    if (saved.restored !== true || saved.surface !== surface || typeof saved.revision !== "string" || !saved.layout?.pages || !saved.canRestore) {
      throw new Error("HAL did not confirm the restore.");
    }
    revision = saved.revision;
    drafts[surface] = saved.layout;
    reconcileHomeFocus(saved.layout);
    canRestore = saved.canRestore;
    history[surface] = [];
    dirty.delete(surface);
    pageIndex = 0;
    selected = null;
    report("success", `Previous ${label} layout restored.`);
  } catch (error) { report("error", `Could not confirm restore. ${error.message}`); }
  finally { busy = false; render(); }
};

function reconcileHomeFocus(savedLayout) {
  if (!savedLayout.pages.some(view => view.cards.some(card => card.focusOnHome))) return;
  const other = surface === "home" ? "assistant" : "home";
  for (const draft of [drafts[other], ...history[other].map(entry => entry.layout)]) {
    for (const view of draft.pages) for (const card of view.cards) card.focusOnHome = false;
  }
}
window.addEventListener("beforeunload", event => {
  if (dirty.size) { event.preventDefault(); event.returnValue = ""; }
});

async function start() {
  try {
    await loadState();
    render();
    say("");
  } catch (error) {
    say("Could not open the editor. Check the TV and reload this page. " + error.message);
    return;
  }
  try {
    await loadCatalog();
    render();
  } catch (error) {
    say("Could not load the Home Assistant catalog. " + error.message);
  }
}

function setCatalog(data) {
  catalog = data.entities;
  names = new Map(catalog.map(entity => [entity.id, entity.name]));
  templates = data.templates;
  notice = data.notice || "";
  options($("template"), [["", "Choose a template"], ...templates.map((template, index) => [String(index), template.title])]);
}

async function loadCatalog() {
  setCatalog(await request("catalog"));
}

start();

let settingsMode = null;
let settingsFile = null;
const settingsResult = message => { $("settings-result").textContent = message; };
const settingsKeys = () => [...$("settings-choices").querySelectorAll("input:checked")].map(input => input.value);

function closeSettingsSelection() {
  settingsMode = null;
  settingsFile = null;
  $("settings-file").value = "";
  $("settings-selection").hidden = true;
  $("settings-choices").replaceChildren();
}

function showSettingsChoices(mode, choices) {
  settingsMode = mode;
  const container = $("settings-choices");
  container.replaceChildren();
  for (const section of choices) {
    container.append(node("h3", section.title));
    for (const item of section.items) {
      const label = node("label");
      const input = node("input");
      input.type = "checkbox";
      input.value = item.key;
      input.checked = !item.sensitive;
      input.onchange = () => { $("settings-confirm").disabled = settingsKeys().length === 0; };
      label.append(input, document.createTextNode(item.title + (item.sensitive ? " (includes credentials)" : "")));
      container.append(label);
    }
  }
  $("settings-selection-title").textContent = mode === "export" ? "Choose saved settings" : "Choose settings from the file";
  $("settings-confirm").textContent = mode === "export" ? "Download settings file" : "Import selected settings to TV";
  $("settings-confirm").disabled = settingsKeys().length === 0;
  $("settings-selection").hidden = false;
  settingsResult(choices.length ? "" : "No settings to transfer.");
}

$("settings-cancel").onclick = () => { closeSettingsSelection(); settingsResult(""); };
$("settings-export-options").onclick = async () => {
  closeSettingsSelection();
  busy = true;
  render();
  try {
    const data = await request("settings/options");
    showSettingsChoices("export", data.choices);
  } catch (error) { settingsResult(error.message); }
  finally { busy = false; render(); }
};
$("settings-file").onchange = async () => {
  const file = $("settings-file").files[0];
  closeSettingsSelection();
  if (!file) return;
  busy = true;
  render();
  try {
    if (file.size > settingsRequestLimit) throw new Error("The settings file exceeds 4 MiB.");
    const parsed = JSON.parse(await file.text());
    if (parsed?.format !== "hal-settings" || parsed.version !== 1) throw new Error("Choose a HAL settings file (version 1).");
    const data = await request("settings/preview", { revision, settings: parsed });
    settingsFile = parsed;
    showSettingsChoices("import", data.choices);
  } catch (error) { settingsResult(`Could not read settings. ${error.message}`); }
  finally { busy = false; render(); }
};
$("settings-confirm").onclick = async () => {
  const keys = settingsKeys();
  if (!keys.length || !settingsMode) return;
  const importing = settingsMode === "import";
  if (importing && !confirm("Import the selected settings to the TV? Imported dashboards replace their saved surfaces. Camera and entity selections are merged, and selected logins are replaced. Open browser drafts will be reloaded.")) return;
  busy = true;
  render();
  try {
    if (importing) {
      const data = await request("settings/import", { revision, keys, settings: settingsFile });
      if (data.imported !== true || typeof data.revision !== "string") throw new Error("HAL did not confirm the import.");
      closeSettingsSelection();
      settingsResult("Settings imported. Reloading saved TV layouts…");
      try {
        await loadState();
      } catch (error) {
        settingsResult(`Settings imported. Reload this page to see the saved TV layouts. ${error.message}`);
        return;
      }
      setCatalog({ entities: [], templates: [] });
      try {
        await loadCatalog();
        settingsResult("Settings imported. The editor now shows saved TV layouts.");
      } catch (error) {
        settingsResult(`Settings imported and saved layouts reloaded. Could not refresh the Home Assistant catalog. ${error.message}`);
      }
    } else {
      const data = await request("settings/export", { revision, keys });
      if (data.settings?.format !== "hal-settings") throw new Error("HAL did not return a settings file.");
      const url = URL.createObjectURL(new Blob([JSON.stringify(data.settings, null, 2)], { type: "application/json;charset=utf-8" }));
      const link = node("a");
      link.href = url;
      link.download = "hal-settings.json";
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
      closeSettingsSelection();
      settingsResult("Settings file downloaded from the saved TV configuration.");
    }
  } catch (error) { settingsResult(`Could not confirm ${importing ? "import" : "export"}. ${error.message}`); }
  finally { busy = false; render(); }
};

const artworkPanel = $("artwork-library");
const artworkInput = $("artwork-files");
const artworkUpload = $("artwork-upload");
const artworkStatus = $("artwork-status");
const artworkList = $("artwork-list");
let artworkBusy = false;

function updateArtworkControls() {
  artworkInput.disabled = artworkBusy;
  artworkUpload.disabled = artworkBusy || !artworkInput.files.length;
  for (const button of artworkList.querySelectorAll("button")) button.disabled = artworkBusy;
}

async function artworkRequest(action, body, name) {
  const response = await fetch("/api/artwork" + (action ? "/" + action : ""), {
    signal: AbortSignal.timeout(30_000),
    ...(action ? {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream", ...(name ? { "X-Artwork-Name": name } : {}) },
      body,
    } : {}),
  });
  const json = await response.json().catch(() => null);
  if (!response.ok || !json) throw new Error(json?.error || "HAL could not read the artwork library.");
  return json;
}

function renderArtwork({ files, limit }) {
  artworkList.replaceChildren();
  for (const file of files) {
    const row = document.createElement("li");
    const button = document.createElement("button");
    button.textContent = "Delete";
    button.setAttribute("aria-label", "Delete " + file.name);
    button.addEventListener("click", () => changeArtwork(() => artworkRequest("delete", file.id)));
    row.append(document.createTextNode(file.name + " "), button);
    artworkList.append(row);
  }
  artworkStatus.textContent = files.length + " of " + limit + " images";
}

async function changeArtwork(action) {
  if (artworkBusy) return;
  artworkBusy = true;
  updateArtworkControls();
  try {
    renderArtwork(await action());
  } catch (error) {
    artworkStatus.textContent = error.message;
  } finally {
    artworkBusy = false;
    updateArtworkControls();
  }
}

artworkPanel.addEventListener("toggle", () => {
  if (artworkPanel.open) changeArtwork(() => artworkRequest());
});
artworkInput.addEventListener("change", updateArtworkControls);
artworkUpload.addEventListener("click", () => changeArtwork(async () => {
  let listing;
  let failures = [];
  for (const file of artworkInput.files) {
    try {
      const name = file.name.replace(/[^a-zA-Z0-9_. -]/g, "_").slice(0, 120);
      listing = await artworkRequest("upload", file, name);
    } catch (error) {
      failures.push(file.name + ": " + error.message);
    }
  }
  artworkInput.value = "";
  if (failures.length) {
    if (listing) renderArtwork(listing);
    throw new Error(failures.join(" "));
  }
  return listing;
}));

// Keep dashboard drafts and independent background drafts alive while switching workspaces.
(() => {
  const tabs = [...document.querySelectorAll('[data-workspace]')];
  function select(tab) {
    for (const item of tabs) { const selected = item === tab; item.setAttribute('aria-selected', selected); item.tabIndex = selected ? 0 : -1; }
    for (const panel of document.querySelectorAll('[data-workspace-panel]')) panel.hidden = panel.dataset.workspacePanel !== tab.dataset.workspace;
    for (const action of ['undo', 'restore', 'apply']) document.getElementById(action).hidden = tab.dataset.workspace !== 'dashboard';
    if (tab.dataset.workspace === 'backgrounds') document.getElementById('video-library').open = true;
  }
  tabs.forEach((tab, index) => {
    tab.onclick = () => select(tab);
    tab.onkeydown = event => {
      const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : -1;
      if (next < 0) return; event.preventDefault(); select(tabs[next]); tabs[next].focus();
    };
  });
})();
