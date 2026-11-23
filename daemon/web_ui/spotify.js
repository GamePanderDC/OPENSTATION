(() => {
  "use strict";

  const byId = (id) => document.getElementById(id);
  const buttons = (selector) => Array.from(document.querySelectorAll(selector));
  const tv = new URLSearchParams(location.search).get("tv") === "1";
  document.body.classList.toggle("spotify-tv", tv);
  byId("spTvLogin").hidden = !tv;
  byId("spWebLogin").hidden = tv;

  let linked;
  let configured;
  let redirect = "";
  let playing = null;
  let playbackReadAt = 0;
  let section = "search";
  let kind = "track";
  let term = "";
  let context = null;
  let rows = [];
  let nextOffset = null;
  let browseGeneration = 0;
  let pollTimer;
  let visible = document.visibilityState !== "hidden";
  let refreshPending = false;
  let actionPending = false;
  let receiverActionPending = false;
  let seekDrag = false;
  let lastSavedId = "";

  async function request(path, payload) {
    const options = payload === undefined ? {} : {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload),
    };
    const result = await fetch(path, options);
    let data = {};
    try { data = await result.json(); } catch (_) {}
    if (!result.ok) throw new Error(data.error || "Станция не ответила. Повторите попытку.");
    return data;
  }
  const spotify = (path, payload) => request("/api/music/spotify/" + path, payload);

  function notice(message) {
    byId("spNotice").textContent = message || "";
    byId("spNotice").hidden = !message;
  }

  function setAccount(open) {
    byId("spAccount").hidden = !open;
    byId("spAccountToggle").setAttribute("aria-expanded", String(open));
  }

  function time(ms) {
    const seconds = Math.floor(Math.max(0, ms || 0) / 1000);
    return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
  }

  function renderClock() {
    if (!playing || seekDrag) return;
    const duration = Math.max(1, playing.durationMs || 0);
    const elapsed = playing.playing ? Date.now() - playbackReadAt : 0;
    const position = Math.min(duration, (playing.positionMs || 0) + elapsed);
    byId("spSeek").max = String(duration);
    byId("spSeek").value = String(position);
    byId("spPosition").textContent = time(position);
    byId("spDuration").textContent = time(duration);
  }

  function renderPlayback(track) {
    if (playing?.id !== track?.id || playing?.positionMs !== track?.positionMs ||
        playing?.playing !== track?.playing) playbackReadAt = Date.now();
    playing = track;
    byId("spTitle").textContent = track?.title || "Музыка на станции";
    byId("spArtist").textContent = track?.detail || "Выберите трек из каталога или включите музыку через Spotify Connect.";
    const image = byId("spCover");
    if (track?.cover && image.getAttribute("src") !== track.cover) image.src = track.cover;
    image.hidden = !track?.cover;
    const link = byId("spTrackLink");
    if (track?.link) link.href = track.link;
    link.hidden = !track?.link;
    byId("spTransport").hidden = !track;
    byId("spSave").hidden = track?.kind !== "track";
    byId("spSave").textContent = lastSavedId && lastSavedId === track?.id ? "♥" : "♡";
    byId("spPlayPause").textContent = track?.playing ? "Пауза" : "Играть";
    renderClock();
  }

  function renderAccount(status) {
    redirect = status.redirect || "";
    byId("spRedirect").textContent = redirect;
    if (configured !== !!status.clientConfigured) {
      configured = !!status.clientConfigured;
      byId("spClientSetup").open = !configured;
    }
    const linkStatus = {
      401: "Сессия Spotify завершилась. Выполните вход заново.",
      403: "Spotify ограничил доступ. Проверьте Premium и список пользователей приложения в Spotify for Developers.",
      429: "Spotify временно ограничил запросы. Подождите немного.",
    };
    byId("spApiStatus").textContent = status.spotifyStatus
      ? (linkStatus[status.spotifyStatus] || "Spotify не ответил. Проверьте интернет на станции.") : "";
    byId("spApiStatus").hidden = !status.spotifyStatus;
    byId("spCatalog").hidden = !status.linked;
    byId("spLinked").hidden = !status.linked;
    byId("spLogin").hidden = !!status.linked;
    buttons("[data-section]").forEach((button) => { button.disabled = !status.linked; });
    if (linked !== !!status.linked) {
      linked = !!status.linked;
      setAccount(!linked);
      if (linked) {
        byId("spFinish").hidden = true;
        showSection(section, !tv);
      } else {
        clearCatalog();
      }
    }
    if (typeof status.playback?.saved === "boolean") lastSavedId = status.playback.saved ? status.playback.id : "";
    renderPlayback(status.playback);
  }

  function renderReceiver(receiver) {
    const state = receiver?.state;
    const descriptions = {
      missing: "Установите приёмник для воспроизведения на станции.",
      installing: "Скачивание и установка…",
      stopped: "Приёмник выключен.",
      running: "Приёмник активен («OpenStation Max»). Играйте из каталога ниже или через Spotify Connect.",
      error: "Приёмник не отвечает.",
    };
    byId("spReceiverState").textContent = receiver?.message || descriptions[state] || "Статус подключения недоступен.";
    const button = byId("spReceiver");
    button.hidden = !receiver || state === "unsupported" || state === "installing";
    button.disabled = receiverActionPending;
    button.dataset.action = state === "running" ? "stop" : receiver?.installed ? "start" : "install";
    button.textContent = { install: "Установить", start: "Включить", stop: "Выключить" }[button.dataset.action];
  }

  async function refresh() {
    clearTimeout(pollTimer);
    if (!visible || refreshPending) return;
    refreshPending = true;
    let delay = 9000;
    try {
      const [account, receiver] = await Promise.allSettled([spotify("status"), request("/api/music/receivers")]);
      if (account.status === "fulfilled") renderAccount(account.value);
      else notice(account.reason.message);
      if (receiver.status === "fulfilled") {
        renderReceiver(receiver.value.spotify);
        if (receiver.value.spotify?.state === "installing") delay = 1800;
      }
    } finally {
      refreshPending = false;
      if (visible) pollTimer = setTimeout(refresh, delay);
    }
  }

  function updateVisibility() {
    if (visible) refresh();
    else clearTimeout(pollTimer);
  }
  window.spotifyVisible = (active) => { visible = active; updateVisibility(); };
  window.setSpotifySetupQr = (url, png) => {
    byId("spSetupAddress").textContent = "Откройте веб-пульт на телефоне: " + url;
    byId("spSetupQr").src = "data:image/png;base64," + png;
    byId("spSetupQr").hidden = false;
  };
  document.addEventListener("visibilitychange", () => {
    visible = document.visibilityState !== "hidden";
    updateVisibility();
  });
  if (window.parent !== window) {
    window.addEventListener("message", (event) => {
      if (event.source === window.parent && event.origin === location.origin &&
          event.data?.type === "openstation-spotify-visibility") window.spotifyVisible(event.data.active);
    });
    const sendHeight = () => window.parent.postMessage({
      type: "openstation-spotify-height", height: document.querySelector("main").scrollHeight + 4,
    }, location.origin);
    if (window.ResizeObserver) new ResizeObserver(sendHeight).observe(document.querySelector("main"));
    window.addEventListener("resize", sendHeight);
    sendHeight();
  }

  async function action(callback) {
    if (actionPending) return;
    actionPending = true;
    notice("");
    try { await callback(); }
    catch (error) { notice(error.message); }
    finally { actionPending = false; }
  }

  function clearCatalog() {
    browseGeneration++;
    rows = [];
    nextOffset = null;
    byId("spResults").replaceChildren();
    byId("spResultsStatus").textContent = "";
    byId("spMore").hidden = true;
  }

  function showSection(next, focusSearch = false) {
    section = next;
    context = null;
    clearCatalog();
    byId("spContext").hidden = true;
    byId("spSearch").hidden = section !== "search";
    byId("spTypes").hidden = section !== "search";
    buttons("[data-section]").forEach((button) => {
      const selected = button.dataset.section === section;
      button.classList.toggle("active", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    if (section !== "search" || term) loadPage();
    else if (focusSearch) byId("spQuery").focus();
  }

  function openContext(item) {
    context = item;
    clearCatalog();
    byId("spContext").hidden = false;
    byId("spContextTitle").textContent = item.title;
    byId("spSearch").hidden = true;
    byId("spTypes").hidden = true;
    byId("spBack").focus();
    loadPage();
  }

  function renderRows(newRows) {
    const fragment = document.createDocumentFragment();
    for (const item of newRows) {
      const isTrack = item.kind === "track" || item.kind === "episode";
      const row = document.createElement("div");
      row.className = "sp-result";
      const button = document.createElement("button");
      button.className = "sp-item";
      button.type = "button";
      button.disabled = isTrack && item.playable === false;
      const cover = document.createElement(item.cover ? "img" : "span");
      if (item.cover) {
        cover.src = item.cover;
        cover.loading = "lazy";
        cover.alt = "";
      } else {
        cover.className = "sp-art-placeholder";
      }
      const text = document.createElement("span");
      text.className = "sp-item-text";
      const title = document.createElement("strong");
      title.textContent = item.title;
      const description = document.createElement("span");
      description.className = "hint";
      description.textContent = item.detail || ({ album: "Альбом", playlist: "Плейлист" }[item.kind] || time(item.durationMs));
      text.append(title, description);
      button.append(cover, text);
      button.addEventListener("click", () => isTrack ? play(item) : openContext(item));
      const link = document.createElement("a");
      link.className = "sp-open";
      link.href = item.link;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.textContent = "↗";
      link.title = "Открыть в Spotify";
      link.setAttribute("aria-label", "Открыть в Spotify: " + item.title);
      row.append(button, link);
      fragment.append(row);
    }
    byId("spResults").append(fragment);
  }

  async function loadPage(offset = 0) {
    const generation = ++browseGeneration;
    const status = byId("spResultsStatus");
    const more = byId("spMore");
    more.hidden = true;
    status.textContent = "Загрузка…";
    try {
      const page = await spotify("browse", {
        section: context?.kind || section, id: context?.id || "", kind, query: term, offset,
      });
      if (generation !== browseGeneration) return;
      const loaded = Array.isArray(page.items) ? page.items : [];
      rows = rows.concat(loaded);
      renderRows(loaded);
      nextOffset = page.nextOffset;
      more.hidden = typeof nextOffset !== "number";
      status.textContent = rows.length
        ? (page.total >= 0 ? "Показано " + rows.length + " из " + page.total : "Показано " + rows.length)
        : more.hidden ? "Ничего не найдено" : "На этой странице нет доступных записей.";
    } catch (error) {
      if (generation !== browseGeneration) return;
      status.textContent = error.message;
      if (offset > 0 && typeof nextOffset === "number") more.hidden = false;
    }
  }

  function playbackCommand(payload) {
    action(async () => {
      await spotify("command", payload);
      clearTimeout(pollTimer);
      pollTimer = setTimeout(refresh, 1000);
    });
  }
  window.spotifyTransport = (verb) => playbackCommand({
    action: verb === "toggle" ? (playing?.playing ? "pause" : "resume") : verb,
  });

  function play(item) {
    const isTrack = item.kind === "track" || item.kind === "episode";
    const index = rows.indexOf(item);
    const nextTracks = isTrack && !context && index >= 0
      ? rows.slice(index + 1).filter((row) => row.kind === "track" && row.playable !== false).map((row) => row.id)
      : [];
    playbackCommand({
      action: "play", kind: item.kind, id: item.id, contextKind: isTrack ? context?.kind : undefined,
      contextId: isTrack ? context?.id : undefined, nextTracks,
    });
  }

  byId("spAccountToggle").addEventListener("click", () => setAccount(byId("spAccount").hidden));
  const tvBtn = byId("spLaunchTv");
  if (tvBtn) {
    tvBtn.addEventListener("click", async () => {
      try {
        await fetch("/api/apps/launch", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ package: "com.spotify.tv.android" })
        });
      } catch (_) {}
    });
  }
  byId("spBegin").addEventListener("click", () => action(async () => {
    const { url } = await spotify("login", { clientId: byId("spClient").value.trim() });
    byId("spLoginLink").href = url;
    byId("spFinish").hidden = false;
    byId("spLoginLink").focus();
  }));
  byId("spFinishForm").addEventListener("submit", (event) => {
    event.preventDefault();
    action(async () => {
      const parsed = new URL(byId("spAuthUrl").value.trim());
      if (parsed.origin + parsed.pathname !== redirect || !parsed.searchParams.has("state"))
        throw new Error("Вставьте полный адрес 127.0.0.1, который появился после входа.");
      if (parsed.searchParams.has("error")) throw new Error("Spotify не разрешил вход. Начните вход заново.");
      await spotify("finish", { state: parsed.searchParams.get("state"), code: parsed.searchParams.get("code") });
      byId("spAuthUrl").value = "";
      await refresh();
    });
  });
  byId("spLogout").addEventListener("click", () => action(async () => {
    await spotify("logout", {});
    lastSavedId = "";
    await refresh();
  }));
  byId("spReceiver").addEventListener("click", async () => {
    if (receiverActionPending) return;
    receiverActionPending = true;
    byId("spReceiver").disabled = true;
    notice("");
    try {
      await request("/api/music/receivers", { receiver: "spotify", action: byId("spReceiver").dataset.action });
    } catch (error) { notice(error.message); }
    finally {
      receiverActionPending = false;
      byId("spReceiver").disabled = false;
      refresh();
    }
  });
  buttons("[data-section]").forEach((button) => button.addEventListener("click", () => showSection(button.dataset.section)));
  buttons("[data-kind]").forEach((button) => button.addEventListener("click", () => {
    kind = button.dataset.kind;
    buttons("[data-kind]").forEach((option) => {
      option.classList.toggle("active", option === button);
      option.setAttribute("aria-pressed", String(option === button));
    });
    clearCatalog();
    if (term) loadPage();
  }));
  byId("spSearch").addEventListener("submit", (event) => {
    event.preventDefault();
    term = byId("spQuery").value.trim();
    clearCatalog();
    if (term) loadPage();
  });
  byId("spBack").addEventListener("click", () => showSection(section));
  byId("spPlayAll").addEventListener("click", () => { if (context) play(context); });
  byId("spMore").addEventListener("click", () => { if (typeof nextOffset === "number") loadPage(nextOffset); });
  buttons("[data-transport]").forEach((button) => button.addEventListener("click", () => window.spotifyTransport(button.dataset.transport)));
  byId("spSeek").addEventListener("input", () => {
    seekDrag = true;
    byId("spPosition").textContent = time(Number(byId("spSeek").value));
  });
  byId("spSeek").addEventListener("change", () => {
    seekDrag = false;
    playbackCommand({ action: "seek", positionMs: Number(byId("spSeek").value) });
  });
  byId("spSave").addEventListener("click", () => action(async () => {
    if (!playing || playing.kind !== "track") return;
    const id = playing.id;
    const removing = id === lastSavedId;
    await spotify("command", { action: removing ? "unsave" : "save", kind: "track", id });
    lastSavedId = removing ? "" : id;
    notice(removing ? "Трек удалён из избранного." : "Трек добавлен в избранное.");
    renderPlayback(playing);
    if (section === "saved" && !context) showSection(section);
  }));

  if (tv) {
    document.addEventListener("keydown", (event) => {
      const directions = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
      const direction = directions[event.key];
      if (!direction) return;
      const focused = document.activeElement;
      if (focused.tagName === "INPUT" && direction[0]) return;
      const candidates = buttons("button, input, a[href], summary").filter((node) =>
        !node.disabled && node.getClientRects().length && node !== focused);
      const current = focused.getBoundingClientRect();
      const cx = (current.left + current.right) / 2;
      const cy = (current.top + current.bottom) / 2;
      let best = null;
      let score = Infinity;
      for (const candidate of candidates) {
        const rect = candidate.getBoundingClientRect();
        const dx = (rect.left + rect.right) / 2 - cx;
        const dy = (rect.top + rect.bottom) / 2 - cy;
        const along = dx * direction[0] + dy * direction[1];
        const across = Math.abs(dx * direction[1] + dy * direction[0]);
        const rank = along + across * 3;
        if (along > 4 && rank < score) { best = candidate; score = rank; }
      }
      if (best) {
        event.preventDefault();
        best.focus({ preventScroll: true });
        best.scrollIntoView({ block: "nearest" });
      }
    });
  }
  setInterval(() => { if (visible) renderClock(); }, 500);
  refresh();
})();
