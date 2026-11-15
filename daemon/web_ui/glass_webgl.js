(function () {
  "use strict";

  var canvas = document.getElementById("webglGlassCanvas");
  if (!canvas) return;

  var gl =
    canvas.getContext("webgl", {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      powerPreference: "high-performance",
      preserveDrawingBuffer: false,
    }) ||
    canvas.getContext("experimental-webgl", {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
    });

  if (!gl) return;

  function compileShader(type, src) {
    var s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      gl.deleteShader(s);
      return null;
    }
    return s;
  }

  function linkProgram(vsSrc, fsSrc) {
    var vs = compileShader(gl.VERTEX_SHADER, vsSrc);
    var fs = compileShader(gl.FRAGMENT_SHADER, fsSrc);
    if (!vs || !fs) return null;
    var p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      gl.deleteProgram(p);
      return null;
    }
    return p;
  }

  var PREC =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n";

  var VS_FULL =
    "attribute vec2 aPos;\n" +
    "varying vec2 vUv;\n" +
    "void main() {\n" +
    "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
    "  vUv = aPos * 0.5 + 0.5;\n" +
    "}\n";

  var VS_LENS =
    "attribute vec2 aPos;\n" +
    "uniform vec4 uQuadNdc;\n" +
    "void main() {\n" +
    "  vec2 t = aPos * 0.5 + 0.5;\n" +
    "  vec2 ndc = mix(uQuadNdc.xy, uQuadNdc.zw, t);\n" +
    "  gl_Position = vec4(ndc, 0.0, 1.0);\n" +
    "}\n";

  // Spatial backdrop with crisp luminous aurora filaments & horizon geometry so Snell refraction visibly warps lines
  var FS_SCENE =
    PREC +
    "varying vec2 vUv;\n" +
    "uniform vec2 uRes;\n" +
    "uniform float uTime;\n" +
    "uniform float uEnergy;\n" +
    "uniform vec3 uAccent;\n" +
    "float starGrid(vec2 uv, float scale, float seed) {\n" +
    "  vec2 gv = fract(uv * scale) - 0.5;\n" +
    "  vec2 id = floor(uv * scale);\n" +
    "  float n = fract(sin(dot(id, vec2(127.1 + seed, 311.7))) * 43758.5453);\n" +
    "  vec2 off = vec2(fract(n * 17.3), fract(n * 43.7)) - 0.5;\n" +
    "  float d = length(gv - off * 0.65);\n" +
    "  return smoothstep(0.11, 0.0, d) * smoothstep(0.55, 0.98, n);\n" +
    "}\n" +
    "void main() {\n" +
    "  float aspect = uRes.x / max(1.0, uRes.y);\n" +
    "  vec2 p = vec2((vUv.x - 0.5) * aspect, vUv.y - 0.5);\n" +
    "  vec3 col = mix(vec3(0.022, 0.033, 0.068), vec3(0.035, 0.058, 0.115), smoothstep(-0.5, 0.5, p.y));\n" +
    "  float t = uTime * 0.22;\n" +
    "  vec2 c1 = vec2(-0.55 * aspect * 0.48 + sin(t * 0.7) * 0.08, 0.28 + cos(t * 0.5) * 0.05);\n" +
    "  vec2 c2 = vec2(0.52 * aspect * 0.48 + cos(t * 0.6) * 0.08, -0.26 + sin(t * 0.45) * 0.06);\n" +
    "  col += uAccent * exp(-dot(p - c1, p - c1) * 3.1) * (0.28 + 0.12 * uEnergy);\n" +
    "  col += vec3(0.66, 0.33, 0.97) * exp(-dot(p - c2, p - c2) * 3.0) * (0.26 + 0.14 * uEnergy);\n" +
    "  // Crisp glowing horizon arc + diagonal light ribbons so refractive lens bevels visibly bend geometry\n" +
    "  float ribbon1 = exp(-pow((p.y - 0.14 * sin(p.x * 2.2 + t)) * 6.5, 2.0));\n" +
    "  float ribbon2 = exp(-pow((p.y + 0.22 - 0.10 * cos(p.x * 2.8 - t * 0.8)) * 8.5, 2.0));\n" +
    "  col += mix(uAccent, vec3(0.50, 0.86, 1.0), 0.4) * ribbon1 * 0.16;\n" +
    "  col += vec3(0.86, 0.65, 0.98) * ribbon2 * 0.12;\n" +
    "  float stars = starGrid(vec2(p.x, p.y + t * 0.02), 8.0, 1.0) * 0.48 + starGrid(p * 1.4, 15.0, 7.3) * 0.28;\n" +
    "  col += vec3(0.84, 0.94, 1.0) * stars;\n" +
    "  gl_FragColor = vec4(col, 1.0);\n" +
    "}\n";

  var FS_KAWASE_DOWN =
    PREC +
    "varying vec2 vUv;\n" +
    "uniform sampler2D uTex;\n" +
    "uniform vec2 uHalfPixel;\n" +
    "void main() {\n" +
    "  vec4 sum = texture2D(uTex, vUv) * 4.0;\n" +
    "  sum += texture2D(uTex, clamp(vUv - uHalfPixel * 2.0, vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + uHalfPixel * 2.0, vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(uHalfPixel.x * 2.0, -uHalfPixel.y * 2.0), vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv - vec2(uHalfPixel.x * 2.0, -uHalfPixel.y * 2.0), vec2(0.001), vec2(0.999)));\n" +
    "  gl_FragColor = sum * 0.125;\n" +
    "}\n";

  var FS_KAWASE_UP =
    PREC +
    "varying vec2 vUv;\n" +
    "uniform sampler2D uTex;\n" +
    "uniform vec2 uHalfPixel;\n" +
    "void main() {\n" +
    "  vec4 sum = texture2D(uTex, clamp(vUv + vec2(-uHalfPixel.x * 2.0, 0.0), vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(-uHalfPixel.x, uHalfPixel.y), vec2(0.001), vec2(0.999))) * 2.0;\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(0.0, uHalfPixel.y * 2.0), vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(uHalfPixel.x, uHalfPixel.y), vec2(0.001), vec2(0.999))) * 2.0;\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(uHalfPixel.x * 2.0, 0.0), vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(uHalfPixel.x, -uHalfPixel.y), vec2(0.001), vec2(0.999))) * 2.0;\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(0.0, -uHalfPixel.y * 2.0), vec2(0.001), vec2(0.999)));\n" +
    "  sum += texture2D(uTex, clamp(vUv + vec2(-uHalfPixel.x, -uHalfPixel.y), vec2(0.001), vec2(0.999))) * 2.0;\n" +
    "  gl_FragColor = sum / 12.0;\n" +
    "}\n";

  var FS_BLIT =
    PREC +
    "varying vec2 vUv;\n" +
    "uniform sampler2D uTex;\n" +
    "void main() {\n" +
    "  gl_FragColor = texture2D(uTex, vUv);\n" +
    "}\n";

  // True physical Snell's Law Liquid Glass lens shader (tan(theta_i - theta_t) + Cauchy dispersion + Schlick Fresnel)
  var FS_LENS =
    PREC +
    "uniform vec2 uRes;\n" +
    "uniform sampler2D uSharpTex;\n" +
    "uniform sampler2D uBlurTex;\n" +
    "uniform vec4 uRect;\n" +     // cx_gl, cy_gl, halfW, halfH
    "uniform vec4 uParams;\n" +   // radiusPx, bevelPx, thicknessPx, rimAlpha
    "uniform vec4 uTint;\n" +     // rgb, alpha
    "float sdRoundBox(vec2 p, vec2 b, float r) {\n" +
    "  vec2 q = abs(p) - b + vec2(r);\n" +
    "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
    "}\n" +
    "vec2 sdRoundBoxGrad(vec2 p, vec2 b, float r) {\n" +
    "  vec2 s = vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);\n" +
    "  vec2 q = abs(p) - b + vec2(r);\n" +
    "  vec2 g = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : ((q.x > q.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));\n" +
    "  return s * g;\n" +
    "}\n" +
    "void main() {\n" +
    "  vec2 rel = gl_FragCoord.xy - uRect.xy;\n" +
    "  float rad = min(uParams.x, min(uRect.z, uRect.w));\n" +
    "  float sd = sdRoundBox(rel, uRect.zw, rad);\n" +
    "  float insideMask = 1.0 - smoothstep(-0.8, 0.8, sd);\n" +
    "  float shadowPx = 18.0;\n" +
    "  float dOut = max(sd, 0.0);\n" +
    "  float shFade = 1.0 - smoothstep(shadowPx * 1.35, shadowPx * 2.35, dOut);\n" +
    "  float shA = exp(-dOut / (shadowPx * 0.42)) * shFade * 0.24 * (1.0 - insideMask);\n" +
    "  if (insideMask <= 0.002) {\n" +
    "    if (shA <= 0.002) discard;\n" +
    "    vec3 sc = vec3(0.0, 0.01, 0.03) * shA;\n" +
    "    gl_FragColor = vec4(sc, shA);\n" +
    "    return;\n" +
    "  }\n" +
    "  vec2 screenUv = gl_FragCoord.xy / uRes;\n" +
    "  vec2 invRes = 1.0 / uRes;\n" +
    "  vec2 n = sdRoundBoxGrad(rel, uRect.zw, rad);\n" +
    "  float minHalf = max(10.0, min(uRect.z, uRect.w));\n" +
    "  float bevel = clamp(uParams.y, 14.0, minHalf);\n" +
    "  float u = clamp(-sd / bevel, 0.0, 1.0);\n" +
    "  vec2 domeRel = rel / max(uRect.zw, vec2(1.0));\n" +
    "  float bevelSin = clamp((1.0 - u) * 0.90, 0.0, 0.90);\n" +
    "  float domeSin = clamp(length(domeRel) * 0.22, 0.0, 0.34);\n" +
    "  float sinThetaI = clamp(max(bevelSin, domeSin * (0.45 + 0.55 * (1.0 - u))), 0.0, 0.94);\n" +
    "  float thetaI = asin(sinThetaI);\n" +
    "  float thicknessPx = max(24.0, uParams.z);\n" +
    "  float dispSpan = 0.045 * (1.0 - 0.55 * u);\n" +
    "  float dR = thetaI - asin(clamp(sinThetaI / (1.48 - dispSpan), 0.0, 0.99));\n" +
    "  float dG = thetaI - asin(clamp(sinThetaI / 1.52, 0.0, 0.99));\n" +
    "  float dB = thetaI - asin(clamp(sinThetaI / (1.56 + dispSpan), 0.0, 0.99));\n" +
    "  vec2 domeDir = normalize(domeRel + vec2(0.0001));\n" +
    "  vec2 dir = normalize(mix(domeDir, n, smoothstep(0.05, 0.42, bevelSin)) + vec2(0.0001));\n" +
    "  // GLSL ES 1.00 uses sin/cos -> tan = sin(x)/cos(x) cleanly across all WebGL1 drivers\n" +
    "  float tanR = sin(dR) / max(0.05, cos(dR));\n" +
    "  float tanG = sin(dG) / max(0.05, cos(dG));\n" +
    "  float tanB = sin(dB) / max(0.05, cos(dB));\n" +
    "  vec2 uvR = clamp(screenUv - dir * (thicknessPx * tanR) * invRes, vec2(0.002), vec2(0.998));\n" +
    "  vec2 uvG = clamp(screenUv - dir * (thicknessPx * tanG) * invRes, vec2(0.002), vec2(0.998));\n" +
    "  vec2 uvB = clamp(screenUv - dir * (thicknessPx * tanB) * invRes, vec2(0.002), vec2(0.998));\n" +
    "  vec3 sharp = vec3(texture2D(uSharpTex, uvR).r, texture2D(uSharpTex, uvG).g, texture2D(uSharpTex, uvB).b);\n" +
    "  vec3 blurred = texture2D(uBlurTex, uvG).rgb;\n" +
    "  float frost = mix(0.08, 0.68, smoothstep(0.0, 0.62, u));\n" +
    "  vec3 bg = mix(sharp, blurred, frost);\n" +
    "  vec3 body = mix(bg, uTint.rgb, uTint.a);\n" +
    "  float cosThetaI = sqrt(max(0.0, 1.0 - sinThetaI * sinThetaI));\n" +
    "  float schlick = 0.04 + 0.96 * pow(clamp(1.0 - cosThetaI, 0.0, 1.0), 4.0);\n" +
    "  float tl = clamp(dot(n, normalize(vec2(-0.62, 0.78))), 0.0, 1.0);\n" +
    "  float br = clamp(dot(n, normalize(vec2(0.62, -0.78))), 0.0, 1.0);\n" +
    "  float rim = exp(-abs(sd + 0.9) * 1.15) * (pow(tl, 1.45) * 0.95 + pow(br, 2.0) * 0.35 + 0.08) * uParams.w * (0.72 + 0.45 * schlick);\n" +
    "  body += vec3(0.90, 0.97, 1.0) * rim;\n" +
    "  vec3 premul = body * insideMask + vec3(0.0, 0.01, 0.03) * shA;\n" +
    "  float outA = clamp(insideMask + shA, 0.0, 1.0);\n" +
    "  gl_FragColor = vec4(premul, outA);\n" +
    "}\n";

  var progScene = linkProgram(VS_FULL, FS_SCENE);
  var progDown = linkProgram(VS_FULL, FS_KAWASE_DOWN);
  var progUp = linkProgram(VS_FULL, FS_KAWASE_UP);
  var progBlit = linkProgram(VS_FULL, FS_BLIT);
  var progLens = linkProgram(VS_LENS, FS_LENS);
  if (!progScene || !progDown || !progUp || !progBlit || !progLens) return;

  var quadBuf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
  gl.bufferData(
    gl.ARRAY_BUFFER,
    new Float32Array([-1, -1, 1, -1, -1, 1, 1, -1, 1, 1, -1, 1]),
    gl.STATIC_DRAW
  );

  function createFbo(w, h) {
    var tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    var fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { fbo: fbo, tex: tex, w: w, h: h };
  }

  function destroyFbo(obj) {
    if (!obj) return;
    gl.deleteFramebuffer(obj.fbo);
    gl.deleteTexture(obj.tex);
  }

  var viewW = 0;
  var viewH = 0;
  var dpr = 1;
  var fboScene = null;
  var fboQ1 = null;
  var fboQ2 = null;
  var fboUp1 = null;
  var fboBlur = null;

  function ensureSize() {
    var curDpr = Math.min(window.devicePixelRatio || 1, 1.5);
    var cssW = Math.max(320, window.innerWidth || 1280);
    var cssH = Math.max(240, window.innerHeight || 720);
    var w = Math.round(cssW * curDpr);
    var h = Math.round(cssH * curDpr);
    if (w === viewW && h === viewH && fboScene) return;

    viewW = w;
    viewH = h;
    dpr = curDpr;
    canvas.width = w;
    canvas.height = h;

    destroyFbo(fboScene);
    destroyFbo(fboQ1);
    destroyFbo(fboQ2);
    destroyFbo(fboUp1);
    destroyFbo(fboBlur);

    var bw = Math.max(256, Math.round(w * 0.5));
    var bh = Math.max(144, Math.round(h * 0.5));
    var q1w = Math.max(128, Math.round(bw * 0.5));
    var q1h = Math.max(72, Math.round(bh * 0.5));
    var q2w = Math.max(64, Math.round(q1w * 0.5));
    var q2h = Math.max(36, Math.round(q1h * 0.5));

    fboScene = createFbo(bw, bh);
    fboQ1 = createFbo(q1w, q1h);
    fboQ2 = createFbo(q2w, q2h);
    fboUp1 = createFbo(q1w, q1h);
    fboBlur = createFbo(bw, bh);
  }

  function bindQuad(program) {
    gl.useProgram(program);
    gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
    var loc = gl.getAttribLocation(program, "aPos");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  }

  function runKawase(prog, srcTex, dst, hx, hy) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fbo);
    gl.viewport(0, 0, dst.w, dst.h);
    bindQuad(prog);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, srcTex);
    gl.uniform1i(gl.getUniformLocation(prog, "uTex"), 0);
    gl.uniform2f(gl.getUniformLocation(prog, "uHalfPixel"), hx, hy);
    gl.drawArrays(gl.TRIANGLES, 0, 6);
  }

  var stateEnergy = 0.2;
  var accentColor = [0.0, 0.83, 1.0];
  window.setWebGlAssistantState = function (st, playing) {
    if (st === "speaking") {
      stateEnergy = 1.0;
      accentColor = [0.75, 0.52, 0.99];
    } else if (st === "listening") {
      stateEnergy = 0.85;
      accentColor = [0.18, 0.83, 0.75];
    } else if (st === "thinking") {
      stateEnergy = 0.65;
      accentColor = [0.91, 0.78, 0.52];
    } else if (playing) {
      stateEnergy = 0.45;
      accentColor = [0.0, 0.83, 1.0];
    } else {
      stateEnergy = 0.18;
      accentColor = [0.0, 0.83, 1.0];
    }
  };

  function collectVisibleLenses() {
    var nodes = document.querySelectorAll(
      ".sidebar, .appbar, .topbar, .glass-container, .card, .conn-banner:not([hidden]), .alarm-banner:not([hidden])"
    );
    var out = [];
    var winW = window.innerWidth || 1280;
    var winH = window.innerHeight || 720;
    for (var i = 0; i < nodes.length && out.length < 28; i++) {
      var el = nodes[i];
      if (el.offsetParent === null && getComputedStyle(el).position !== "fixed") continue;
      var r = el.getBoundingClientRect();
      if (r.width < 12 || r.height < 12) continue;
      if (r.bottom < -30 || r.top > winH + 30 || r.right < -30 || r.left > winW + 30) continue;

      var isHeroGlass = el.classList.contains("glass-container");
      var radiusCss = isHeroGlass ? 28 : el.classList.contains("conn-banner") ? 18 : 22;
      var bevelCss = isHeroGlass ? 28 : 20;
      var thickCss = isHeroGlass ? 42 : 28;
      var tintA = isHeroGlass ? 0.24 : el.classList.contains("conn-banner") ? 0.34 : 0.36;
      var rimA = isHeroGlass ? 0.58 : 0.32;
      var tintR = el.classList.contains("conn-banner") ? 0.28 : 0.035;
      var tintG = el.classList.contains("conn-banner") ? 0.05 : 0.055;
      var tintB = el.classList.contains("conn-banner") ? 0.10 : 0.115;

      out.push({
        l: r.left * dpr,
        t: r.top * dpr,
        r: r.right * dpr,
        b: r.bottom * dpr,
        rad: radiusCss * dpr,
        bevel: bevelCss * dpr,
        thick: thickCss * dpr,
        rim: rimA,
        tint: [tintR, tintG, tintB, tintA],
      });
    }
    return out;
  }

  document.documentElement.classList.add("webgl-glass");
  var t0 = performance.now();
  var lastFrameMs = 0;

  function renderFrame(now) {
    requestAnimationFrame(renderFrame);
    if (document.hidden) return;
    if (now - lastFrameMs < 15) return;
    lastFrameMs = now;

    ensureSize();
    if (!fboScene) return;

    var timeSec = (now - t0) * 0.001;

    // 1. Sharp dynamic aurora scene -> fboScene
    gl.disable(gl.BLEND);
    gl.bindFramebuffer(gl.FRAMEBUFFER, fboScene.fbo);
    gl.viewport(0, 0, fboScene.w, fboScene.h);
    bindQuad(progScene);
    gl.uniform2f(gl.getUniformLocation(progScene, "uRes"), fboScene.w, fboScene.h);
    gl.uniform1f(gl.getUniformLocation(progScene, "uTime"), timeSec);
    gl.uniform1f(gl.getUniformLocation(progScene, "uEnergy"), stateEnergy);
    gl.uniform3f(
      gl.getUniformLocation(progScene, "uAccent"),
      accentColor[0],
      accentColor[1],
      accentColor[2]
    );
    gl.drawArrays(gl.TRIANGLES, 0, 6);

    // 2. Dual-Kawase pyramid -> fboBlur
    runKawase(progDown, fboScene.tex, fboQ1, 1.4 / fboScene.w, 1.4 / fboScene.h);
    runKawase(progDown, fboQ1.tex, fboQ2, 1.8 / fboQ1.w, 1.8 / fboQ1.h);
    runKawase(progUp, fboQ2.tex, fboUp1, 1.8 / fboQ2.w, 1.8 / fboQ2.h);
    runKawase(progUp, fboUp1.tex, fboBlur, 1.6 / fboQ1.w, 1.6 / fboQ1.h);

    // 3. Present sharp cosmic background to screen
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, viewW, viewH);
    bindQuad(progBlit);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, fboScene.tex);
    gl.uniform1i(gl.getUniformLocation(progBlit, "uTex"), 0);
    gl.drawArrays(gl.TRIANGLES, 0, 6);

    // 4. Composite Snell's-law Liquid Glass lenses for visible panels & cards
    var lenses = collectVisibleLenses();
    if (!lenses.length) return;

    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    bindQuad(progLens);
    gl.uniform2f(gl.getUniformLocation(progLens, "uRes"), viewW, viewH);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, fboScene.tex);
    gl.uniform1i(gl.getUniformLocation(progLens, "uSharpTex"), 0);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, fboBlur.tex);
    gl.uniform1i(gl.getUniformLocation(progLens, "uBlurTex"), 1);

    var uQuadNdc = gl.getUniformLocation(progLens, "uQuadNdc");
    var uRect = gl.getUniformLocation(progLens, "uRect");
    var uParams = gl.getUniformLocation(progLens, "uParams");
    var uTint = gl.getUniformLocation(progLens, "uTint");

    var pad = 54 * dpr;
    for (var i = 0; i < lenses.length; i++) {
      var s = lenses[i];
      var ndcL = ((s.l - pad) / viewW) * 2.0 - 1.0;
      var ndcR = ((s.r + pad) / viewW) * 2.0 - 1.0;
      var ndcT = 1.0 - ((s.t - pad) / viewH) * 2.0;
      var ndcB = 1.0 - ((s.b + pad) / viewH) * 2.0;

      var w = s.r - s.l;
      var h = s.b - s.t;
      var cx = s.l + w * 0.5;
      var cyGl = viewH - (s.t + h * 0.5);

      gl.uniform4f(uQuadNdc, ndcL, ndcB, ndcR, ndcT);
      gl.uniform4f(uRect, cx, cyGl, w * 0.5, h * 0.5);
      gl.uniform4f(uParams, s.rad, s.bevel, s.thick, s.rim);
      gl.uniform4f(uTint, s.tint[0], s.tint[1], s.tint[2], s.tint[3]);
      gl.drawArrays(gl.TRIANGLES, 0, 6);
    }
    gl.disable(gl.BLEND);
  }

  requestAnimationFrame(renderFrame);
})();
