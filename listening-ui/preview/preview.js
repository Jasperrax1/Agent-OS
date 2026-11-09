(() => {
  "use strict";

  const listeningAt = 2.06;
  const spokenOffset = listeningAt - .65;
  const checkingAt = 4.8 + spokenOffset;
  const duration = 8.6 + spokenOffset;
  const fullText = "给我生成一个飞机大战的小游戏";
  const finalText = `${fullText}。`;
  const steps = [
    { phase: "ready", time: 0, text: "", description: "待机 · 播放完整流程，或选择一个阶段查看。" },
    { phase: "entering", time: 0, text: "", description: "入场 · 底部扩光、向上扫光、渐变边缘、两侧白光依次出现。" },
    { phase: "listening", time: listeningAt, text: fullText, description: "聆听 · 底光持续流动，调高模拟音量可观察涟漪。" },
    { phase: "checking", time: checkingAt, text: finalText, description: "校验 · 收音结束，保留最终转写。" },
    { phase: "generating", time: 5.46 + spokenOffset, text: finalText, description: "生成 · 完整播放中，这一阶段持续约 2.2 秒。" },
    { phase: "complete", time: 7.66 + spokenOffset, text: "", description: "完成 · 本轮动效已结束，可以重播或单独查看任意阶段。" },
  ];
  const speech = [
    [1.35, "给我"],
    [1.82, "给我生成"],
    [2.18, "给我生成一个飞机"],
    [2.63, "给我生成一个飞机大战的"],
    [3.02, "给我生成一个飞机大战的小有些"],
    [3.6, fullText],
  ];
  const accents = [[1.4, 0.21], [1.87, 0.22], [2.29, 0.24], [2.74, 0.22], [3.08, 0.17], [3.55, 0.2], [3.9, 0.12]];

  const stage = document.getElementById("motion");
  const play = document.getElementById("play");
  const reset = document.getElementById("reset");
  const speed = document.getElementById("speed");
  const timeline = document.getElementById("timeline");
  const elapsed = document.getElementById("elapsed");
  const status = document.getElementById("phase-status");
  const level = document.getElementById("level");
  const scene = document.getElementById("scene");
  const media = document.getElementById("media");
  const defaultMedia = document.getElementById("default-media");
  const phaseButtons = document.querySelectorAll(".phase-button");
  const fallbackScene = scene.firstElementChild;

  if (!window.ListeningUI) {
    status.textContent = "动效组件未加载。请保持 preview 与 runtime 文件夹相邻。";
    play.disabled = reset.disabled = level.disabled = true;
    phaseButtons.forEach((button) => { button.disabled = true; });
    return;
  }

  let request = 0;
  let playing = false;
  let selected = "";
  let lastLevel = -1;
  let lastText = "";
  let mediaUrl = "";
  let frozen = [];
  timeline.max = duration;
  const motion = window.ListeningUI.mount(stage, {
    backdrop: scene,
    onStop: () => {
      if (selected === "listening") replay(checkingAt);
    },
  });

  function setPlaying(value) {
    playing = value;
    play.textContent = value ? "从头播放" : "播放完整流程";
    level.disabled = value;
    speed.disabled = value;
  }

  function cancelAnimation() {
    cancelAnimationFrame(request);
    request = 0;
    frozen.forEach((animation) => animation.play());
    frozen = [];
    setPlaying(false);
  }

  function render(step, text, strength, time) {
    const phaseChanged = selected !== step.phase;
    const quantized = Math.round(strength * 100) / 100;
    if (phaseChanged || lastLevel !== quantized || lastText !== text) {
      motion.update({ phase: step.phase, text, level: quantized });
      lastLevel = quantized;
      lastText = text;
    }
    if (phaseChanged) {
      selected = step.phase;
      phaseButtons.forEach((button) => button.setAttribute("aria-pressed", String(button.dataset.phase === selected)));
      status.textContent = step.description;
    }
    const timeLabel = `${time.toFixed(1)} / ${duration.toFixed(1)} 秒`;
    if (elapsed.value !== timeLabel || time === duration) {
      timeline.value = time;
      elapsed.value = timeLabel;
    }
  }

  function simulatedLevel(time) {
    let amplitude = 0;
    for (const [center, width] of accents) {
      const gap = Math.abs(time - spokenOffset - center) / width;
      if (gap < 1) amplitude += (1 - gap) ** 1.3;
    }
    const syllables = 0.67 + 0.22 * Math.sin(time * 51) + 0.1 * Math.sin(time * 113);
    return Math.max(0, Math.min(1, amplitude * syllables));
  }

  function renderTimeline(time) {
    const step = steps.findLast((item) => item.time <= time);
    if (step.phase === "listening") {
      const phrase = speech.findLast(([start]) => start + spokenOffset <= time);
      render(step, phrase?.[1] ?? "", simulatedLevel(time), time);
      return;
    }
    render(step, step.text, 0, time);
  }

  function replay(offset = 0, end = duration) {
    cancelAnimation();
    const rate = Number(speed.value);
    stage.style.setProperty("--lui-time-scale", String(1 / rate));
    if (offset === 0) {
      render(steps[0], "", 0, 0);
      stage.getBoundingClientRect();
    }
    setPlaying(true);
    const endMs = end * 1000;
    let playbackMs = offset * 1000;
    let previous;
    let lastRender = -Infinity;
    function tick(now) {
      if (previous !== undefined) {
        playbackMs = Math.min(endMs, playbackMs + (now - previous) * rate);
      }
      previous = now;
      if (now - lastRender >= 30 || playbackMs === endMs) {
        renderTimeline(playbackMs / 1000);
        lastRender = now;
      }
      if (playbackMs < endMs) {
        request = requestAnimationFrame(tick);
      } else {
        request = 0;
        setPlaying(false);
      }
    }
    request = requestAnimationFrame(tick);
  }

  function jump(phase) {
    if (phase === "entering") {
      replay(0, listeningAt);
      return;
    }
    cancelAnimation();
    stage.style.setProperty("--lui-time-scale", "1");
    const step = steps.find((item) => item.phase === phase);
    const previewStep = () => {
      request = 0;
      render(step, step.text, phase === "listening" ? Number(level.value) / 100 : 0, step.time);
    };
    if (selected === phase && phase !== "ready") {
      render(steps[0], "", 0, 0);
      stage.getBoundingClientRect();
      request = requestAnimationFrame(previewStep);
    } else {
      previewStep();
    }
  }

  window.__maTimeline = {
    seek(seconds) {
      cancelAnimation();
      const time = Math.min(duration, Math.max(0, Number(seconds) || 0));
      stage.style.setProperty("--lui-time-scale", "1");
      render(steps[0], "", 0, 0);
      stage.getBoundingClientRect();
      renderTimeline(time);
      const step = steps.findLast((item) => item.time <= time);
      frozen = stage.getAnimations({ subtree: true }).filter((animation) => animation.playState === "running");
      for (const animation of frozen) {
        animation.pause();
        animation.currentTime = (time - step.time) * 1000;
      }
      return time;
    },
  };

  play.addEventListener("click", () => replay());
  reset.addEventListener("click", () => jump("ready"));
  phaseButtons.forEach((button) => button.addEventListener("click", () => jump(button.dataset.phase)));
  level.addEventListener("input", () => {
    if (!playing) {
      const listening = steps[2];
      render(listening, listening.text, Number(level.value) / 100, listening.time);
    }
  });

  function restoreScene() {
    if (mediaUrl) URL.revokeObjectURL(mediaUrl);
    mediaUrl = "";
    scene.replaceChildren(fallbackScene);
    media.value = "";
    defaultMedia.hidden = true;
  }

  media.addEventListener("change", () => {
    const file = media.files[0];
    if (!file || (!file.type.startsWith("image/") && !file.type.startsWith("video/"))) return;
    if (mediaUrl) URL.revokeObjectURL(mediaUrl);
    mediaUrl = URL.createObjectURL(file);
    const visual = document.createElement(file.type.startsWith("video/") ? "video" : "img");
    if (visual instanceof HTMLVideoElement) {
      visual.muted = true;
      visual.playsInline = true;
      visual.loop = true;
      visual.autoplay = true;
    } else {
      visual.alt = "";
    }
    visual.addEventListener("error", () => {
      if (scene.firstElementChild !== visual) return;
      restoreScene();
      status.textContent = "本地画面无法读取，已恢复默认画面。";
    });
    visual.src = mediaUrl;
    scene.replaceChildren(visual);
    defaultMedia.hidden = false;
  });
  defaultMedia.addEventListener("click", restoreScene);
  jump("ready");
})();
