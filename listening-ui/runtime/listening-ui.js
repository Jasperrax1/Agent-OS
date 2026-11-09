(function () {
  "use strict";

  const phases = new Set(["ready", "entering", "listening", "checking", "generating", "complete"]);
  const invitation = "我在听，请说";
  let filterSerial = 0;

  function element(doc, tag, name) {
    const node = doc.createElement(tag);
    node.className = `muse-listening__${name}`;
    return node;
  }

  function installProgressiveBlur(host, backdrop) {
    if (!backdrop) return () => {};
    const doc = host.ownerDocument;
    const id = `muse-listening-progressive-${++filterSerial}`;
    const filters = doc.createElementNS("http://www.w3.org/2000/svg", "svg");
    filters.setAttribute("width", "0");
    filters.setAttribute("height", "0");
    filters.setAttribute("aria-hidden", "true");
    filters.style.position = "absolute";
    const originalFilter = backdrop.style.filter;

    const alphaRamp = (start, end) => "data:image/svg+xml," + encodeURIComponent(
      `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="384"><defs>
        <linearGradient id="r" x2="0" y2="1">
          <stop offset="${start}" stop-opacity="0"/>
          <stop offset="${end}" stop-opacity="1"/>
        </linearGradient>
      </defs><rect width="512" height="384" fill="url(#r)"/></svg>`,
    );
    // Each radius samples the clean scene. Its alpha ramp blends smoothly over the previous result.
    const band = (name, start, end, radius, base) => `
      <feImage href="${alphaRamp(start, end)}" x="0" y="0" width="1" height="1"
        preserveAspectRatio="none" result="${name}Mask"/>
      <feGaussianBlur in="SourceGraphic" stdDeviation="${radius}" edgeMode="duplicate" result="${name}Blur"/>
      <feComposite in="${name}Blur" in2="${name}Mask" operator="in" result="${name}Band"/>
      <feComposite in="${name}Band" in2="${base}" operator="over" result="${name}"/>`;
    filters.innerHTML = `<defs><filter id="${id}" x="0%" y="0%" width="100%" height="100%"
      primitiveUnits="objectBoundingBox" color-interpolation-filters="sRGB">
      ${band("shallow", ".50", ".82", ".012 .016", "SourceGraphic")}
      ${band("deep", ".63", "1", ".036 .048", "shallow")}
    </filter></defs>`;
    host.append(filters);
    return (enabled) => {
      backdrop.style.filter = enabled ? `url("#${id}")` : originalFilter;
    };
  }

  function mount(host, { onStop, backdrop } = {}) {
    if (!host || !host.ownerDocument || typeof host.append !== "function") {
      throw new TypeError("ListeningUI.mount requires a host element.");
    }
    const doc = host.ownerDocument;
    const root = doc.createElement("div");
    root.className = "muse-listening-ui";
    root.dataset.phase = "ready";

    const setProgressiveBlur = installProgressiveBlur(root, backdrop);
    const generationFog = element(doc, "div", "generation-fog");
    const ambient = element(doc, "div", "ambient");
    const generation = element(doc, "div", "generation");
    const entry = element(doc, "div", "entry");
    const ripples = element(doc, "div", "ripples");
    ambient.append(element(doc, "div", "ambient-wave"), ripples);

    const field = element(doc, "div", "generation-light");
    for (const color of ["blue", "cyan", "mint", "ivory", "lilac"]) {
      field.append(element(doc, "div", `cloud muse-listening__cloud--${color}`));
    }
    generation.append(field);
    for (const layer of [generationFog, ambient, generation, entry]) layer.setAttribute("aria-hidden", "true");

    const caption = element(doc, "div", "caption");
    caption.dataset.hasText = "false";
    caption.setAttribute("aria-hidden", "true");
    caption.setAttribute("role", "status");
    caption.setAttribute("aria-atomic", "true");
    const captionWords = element(doc, "div", "caption-words");
    const captionText = element(doc, "span", "caption-text");
    captionText.dir = "auto";
    const stableText = doc.createTextNode("");
    const changedText = element(doc, "span", "caption-changed");
    captionText.append(stableText, changedText);
    captionWords.append(captionText);
    caption.append(element(doc, "div", "caption-edge"), captionWords);

    const stop = element(doc, "button", "stop");
    stop.type = "button";
    stop.textContent = "点击结束收音";
    stop.disabled = true;
    stop.setAttribute("aria-hidden", "true");

    root.append(generationFog, ambient, generation, entry, caption, stop);
    host.append(root);

    let phase = "ready";
    let text = "";
    let paintedText = "";
    let showInvitation = true;
    let level = 0;
    let destroyed = false;
    let lastRippleAt = -Infinity;
    const reducedMotion = doc.defaultView.matchMedia("(prefers-reduced-motion: reduce)");

    function addRipple(strength) {
      const now = doc.defaultView.performance.now();
      if (reducedMotion.matches || now - lastRippleAt < 280 || ripples.childElementCount >= 2) return;
      lastRippleAt = now;
      const wave = element(doc, "div", "ripple");
      wave.style.setProperty("--lui-ripple-intensity", String(.28 + strength * .36));
      const remove = () => wave.remove();
      wave.addEventListener("animationend", remove, { once: true });
      wave.addEventListener("animationcancel", remove, { once: true });
      ripples.append(wave);
    }

    function stopListening() {
      if (phase === "listening" && typeof onStop === "function") onStop();
    }
    stop.addEventListener("click", stopListening);

    function renderText(next, hint) {
      if (next === paintedText) return;
      const before = Array.from(paintedText);
      const after = Array.from(next);
      paintedText = next;
      if (!after.length) return; // The old line remains during its exit fade.

      let same = 0;
      while (same < before.length && same < after.length && before[same] === after[same]) same++;
      const width = after.reduce((sum, letter) => sum + (letter.charCodeAt(0) < 128 ? 0.63 : 1), 2.2);
      caption.style.setProperty("--lui-caption-width", `${width}em`);
      const animateFrom = hint ? after.length : Math.max(same, after.length - 7);
      stableText.data = after.slice(0, animateFrom).join("");
      const suffix = doc.createDocumentFragment();
      after.slice(animateFrom).forEach((letter, index) => {
        const glyph = element(doc, "span", "caption-glyph");
        glyph.textContent = letter;
        glyph.style.setProperty("--lui-glyph-delay", `${index * 14}ms`);
        suffix.append(glyph);
      });
      changedText.replaceChildren(suffix);
    }

    function update({ phase: nextPhase, text: nextText, level: nextLevel, showHint } = {}) {
      if (destroyed) return;
      if (phases.has(nextPhase) && nextPhase !== phase) {
        phase = nextPhase;
        root.dataset.phase = phase;
        setProgressiveBlur(phase === "listening" || phase === "checking");
        if (phase === "ready") entry.replaceChildren();
        if (phase === "entering") {
          entry.replaceChildren(...["entry-spark", "entry-wash", "entry-edge", "entry-rail muse-listening__entry-rail--left", "entry-rail muse-listening__entry-rail--right"]
            .map((name) => element(doc, "div", name)));
        }
        if (phase !== "listening") {
          ripples.replaceChildren();
          lastRippleAt = -Infinity;
        }
        const showStop = phase === "listening";
        stop.disabled = !showStop || typeof onStop !== "function";
        stop.setAttribute("aria-hidden", String(!showStop));
      }
      if (typeof nextText === "string" || nextText === null) {
        text = (nextText || "").trim();
      }
      if (typeof showHint === "boolean") showInvitation = showHint;
      const hint = phase === "listening" && !text && showInvitation;
      const line = hint ? invitation : phase === "listening" || phase === "checking" ? text : "";
      if (line !== paintedText) {
        renderText(line, hint);
        caption.dataset.hasText = String(Boolean(line));
      }
      const hidden = String(!line);
      if (caption.getAttribute("aria-hidden") !== hidden) caption.setAttribute("aria-hidden", hidden);
      if (typeof nextLevel === "number" && Number.isFinite(nextLevel)) {
        const next = Math.round(Math.min(1, Math.max(0, nextLevel)) * 100) / 100;
        if (next !== level) {
          if (phase === "listening" && next >= .20 && (level < .20 || next - level >= .07)) addRipple(next);
          root.style.setProperty("--lui-response-time", next > level ? "80ms" : "220ms");
          level = next;
          root.style.setProperty("--lui-level", String(level));
        }
      }
    }

    function destroy() {
      if (destroyed) return;
      destroyed = true;
      stop.removeEventListener("click", stopListening);
      setProgressiveBlur(false);
      root.remove();
    }

    return { update, destroy };
  }

  window.ListeningUI = Object.freeze({ mount });
})();
