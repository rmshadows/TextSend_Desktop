/* TextSend 网页桥。HTTP 不是安全上下文，不能用 crypto.subtle。
 * 密封格式与桌面 AesGcm 相同：nonce(12) || ciphertext || tag(16)。
 * 密钥：HKDF-SHA256(ikm=k, salt=会话 id 的 UTF-8, info="textsend-webbridge-v1", 32)。
 */
(function (root) {
  "use strict";

  var hostWord = "电脑";
  function applyHost(name) {
    if (name) hostWord = String(name);
    var sendTitle = document.getElementById("sendTitle");
    if (sendTitle) sendTitle.textContent = "发给" + hostWord;
    var inTitle = document.getElementById("inTitle");
    if (inTitle) inTitle.textContent = hostWord + "发来的";
    var hint = document.getElementById("dlHint");
    if (hint) hint.textContent = hostWord + "发来的文件收完后，交给浏览器保存。关掉则点「保存」再放进下载目录。";
  }


  var INFO = "textsend-webbridge-v1";
  var SBOX = [
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
  ];
  var K256 = [
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
  ];

  function xtime(a) {
    a &= 255;
    return ((a << 1) & 255) ^ ((a & 128) ? 0x1b : 0);
  }

  function concat(a, b) {
    var o = new Uint8Array(a.length + b.length);
    o.set(a, 0);
    o.set(b, a.length);
    return o;
  }

  function rotr(x, n) {
    return ((x >>> n) | (x << (32 - n))) >>> 0;
  }

  function sha256(msg) {
    var bytes = msg instanceof Uint8Array ? msg : new Uint8Array(msg);
    var bitLen = bytes.length * 8;
    var withOne = bytes.length + 1 + 8;
    var zeros = (64 - (withOne % 64)) % 64;
    var buf = new Uint8Array(bytes.length + 1 + zeros + 8);
    buf.set(bytes);
    buf[bytes.length] = 0x80;
    var view = new DataView(buf.buffer);
    view.setUint32(buf.length - 8, Math.floor(bitLen / 4294967296));
    view.setUint32(buf.length - 4, bitLen >>> 0);
    var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
    var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
    var w = new Uint32Array(64);
    for (var off = 0; off < buf.length; off += 64) {
      for (var i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
      for (var j = 16; j < 64; j++) {
        var s0 = rotr(w[j - 15], 7) ^ rotr(w[j - 15], 18) ^ (w[j - 15] >>> 3);
        var s1 = rotr(w[j - 2], 17) ^ rotr(w[j - 2], 19) ^ (w[j - 2] >>> 10);
        w[j] = (w[j - 16] + s0 + w[j - 7] + s1) >>> 0;
      }
      var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
      for (var r = 0; r < 64; r++) {
        var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        var ch = ((e & f) ^ ((~e >>> 0) & g)) >>> 0;
        var t1 = (h + S1 + ch + K256[r] + w[r]) >>> 0;
        var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        var maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        var t2 = (S0 + maj) >>> 0;
        h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0;
      h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0;
    }
    var out = new Uint8Array(32);
    var ov = new DataView(out.buffer);
    var hs = [h0, h1, h2, h3, h4, h5, h6, h7];
    for (var k = 0; k < 8; k++) ov.setUint32(k * 4, hs[k]);
    return out;
  }

  function hmacSha256(key, msg) {
    var k = key;
    if (k.length > 64) k = sha256(k);
    var block = new Uint8Array(64);
    block.set(k);
    var ipad = new Uint8Array(64);
    var opad = new Uint8Array(64);
    for (var i = 0; i < 64; i++) {
      ipad[i] = block[i] ^ 0x36;
      opad[i] = block[i] ^ 0x5c;
    }
    return sha256(concat(opad, sha256(concat(ipad, msg))));
  }

  function hkdf(ikm, salt, info, length) {
    var prk = hmacSha256(salt, ikm);
    var infoBytes = typeof info === "string" ? new TextEncoder().encode(info) : info;
    var t = new Uint8Array(0);
    var out = new Uint8Array(length);
    var pos = 0;
    var counter = 1;
    while (pos < length) {
      var input = new Uint8Array(t.length + infoBytes.length + 1);
      input.set(t, 0);
      input.set(infoBytes, t.length);
      input[input.length - 1] = counter;
      t = hmacSha256(prk, input);
      var n = Math.min(t.length, length - pos);
      out.set(t.subarray(0, n), pos);
      pos += n;
      counter++;
    }
    return out;
  }

  function expandKey(key) {
    if (key.length !== 32) throw new Error("key");
    var w = new Uint8Array(240);
    w.set(key);
    var bytes = 32;
    var rcon = 1;
    var temp = new Uint8Array(4);
    while (bytes < 240) {
      temp[0] = w[bytes - 4];
      temp[1] = w[bytes - 3];
      temp[2] = w[bytes - 2];
      temp[3] = w[bytes - 1];
      if (bytes % 32 === 0) {
        var t0 = temp[0];
        temp[0] = SBOX[temp[1]] ^ rcon;
        temp[1] = SBOX[temp[2]];
        temp[2] = SBOX[temp[3]];
        temp[3] = SBOX[t0];
        rcon = xtime(rcon);
      } else if (bytes % 32 === 16) {
        temp[0] = SBOX[temp[0]];
        temp[1] = SBOX[temp[1]];
        temp[2] = SBOX[temp[2]];
        temp[3] = SBOX[temp[3]];
      }
      for (var i = 0; i < 4; i++) {
        w[bytes] = w[bytes - 32] ^ temp[i];
        bytes++;
      }
    }
    return w;
  }

  function addRoundKey(s, rk, round) {
    var o = s.slice();
    var off = round * 16;
    for (var i = 0; i < 16; i++) o[i] ^= rk[off + i];
    return o;
  }

  function subBytes(s) {
    var o = new Uint8Array(16);
    for (var i = 0; i < 16; i++) o[i] = SBOX[s[i]];
    return o;
  }

  function shiftRows(s) {
    var o = new Uint8Array(16);
    for (var r = 0; r < 4; r++) {
      for (var c = 0; c < 4; c++) o[r + 4 * c] = s[r + 4 * ((c + r) % 4)];
    }
    return o;
  }

  function mixColumns(s) {
    var o = new Uint8Array(16);
    for (var c = 0; c < 4; c++) {
      var i = 4 * c;
      var s0 = s[i], s1 = s[i + 1], s2 = s[i + 2], s3 = s[i + 3];
      var t = s0 ^ s1 ^ s2 ^ s3;
      o[i] = s0 ^ t ^ xtime(s0 ^ s1);
      o[i + 1] = s1 ^ t ^ xtime(s1 ^ s2);
      o[i + 2] = s2 ^ t ^ xtime(s2 ^ s3);
      o[i + 3] = s3 ^ t ^ xtime(s3 ^ s0);
    }
    return o;
  }

  function aesEncryptBlock(roundKeys, block) {
    var s = addRoundKey(block, roundKeys, 0);
    for (var round = 1; round < 14; round++) {
      s = subBytes(s);
      s = shiftRows(s);
      s = mixColumns(s);
      s = addRoundKey(s, roundKeys, round);
    }
    s = subBytes(s);
    s = shiftRows(s);
    return addRoundKey(s, roundKeys, 14);
  }

  function xor16(a, b) {
    var o = new Uint8Array(16);
    for (var i = 0; i < 16; i++) o[i] = a[i] ^ b[i];
    return o;
  }

  function shiftRight1(v) {
    var o = new Uint8Array(16);
    var carry = 0;
    for (var i = 0; i < 16; i++) {
      var b = v[i];
      o[i] = ((b >>> 1) | carry) & 255;
      carry = (b & 1) ? 128 : 0;
    }
    return o;
  }

  function gmul(x, y) {
    var z = new Uint8Array(16);
    var v = y.slice();
    for (var i = 0; i < 128; i++) {
      if ((x[i >> 3] & (1 << (7 - (i & 7)))) !== 0) {
        for (var j = 0; j < 16; j++) z[j] ^= v[j];
      }
      var lsb = v[15] & 1;
      v = shiftRight1(v);
      if (lsb) v[0] ^= 0xe1;
    }
    return z;
  }

  function ghash(h, cipher) {
    var y = new Uint8Array(16);
    var off = 0;
    while (off < cipher.length) {
      var block = new Uint8Array(16);
      var take = Math.min(16, cipher.length - off);
      block.set(cipher.subarray(off, off + take));
      y = gmul(xor16(y, block), h);
      off += 16;
    }
    var len = new Uint8Array(16);
    var bits = cipher.length * 8;
    var view = new DataView(len.buffer);
    view.setUint32(8, Math.floor(bits / 4294967296));
    view.setUint32(12, bits >>> 0);
    return gmul(xor16(y, len), h);
  }

  function inc32(block) {
    var o = block.slice();
    for (var i = 15; i >= 12; i--) {
      o[i] = (o[i] + 1) & 255;
      if (o[i] !== 0) break;
    }
    return o;
  }

  function ctrXor(roundKeys, nonce, data) {
    var out = new Uint8Array(data.length);
    var counter = new Uint8Array(16);
    counter.set(nonce, 0);
    counter[15] = 1;
    var off = 0;
    while (off < data.length) {
      counter = inc32(counter);
      var stream = aesEncryptBlock(roundKeys, counter);
      var n = Math.min(16, data.length - off);
      for (var i = 0; i < n; i++) out[off + i] = data[off + i] ^ stream[i];
      off += n;
    }
    return out;
  }

  function tagOf(roundKeys, nonce, ct) {
    var h = aesEncryptBlock(roundKeys, new Uint8Array(16));
    var s = ghash(h, ct);
    var j0 = new Uint8Array(16);
    j0.set(nonce, 0);
    j0[15] = 1;
    return xor16(s, aesEncryptBlock(roundKeys, j0));
  }

  function ctEq(a, b) {
    if (a.length !== b.length) return false;
    var d = 0;
    for (var i = 0; i < a.length; i++) d |= a[i] ^ b[i];
    return d === 0;
  }

  function sealWithNonce(key, nonce, plain) {
    var rk = expandKey(key);
    var ct = ctrXor(rk, nonce, plain);
    var tag = tagOf(rk, nonce, ct);
    var out = new Uint8Array(12 + ct.length + 16);
    out.set(nonce, 0);
    out.set(ct, 12);
    out.set(tag, 12 + ct.length);
    return out;
  }

  function seal(key, plain) {
    if (!root.crypto || !root.crypto.getRandomValues) throw new Error("no random");
    var nonce = new Uint8Array(12);
    root.crypto.getRandomValues(nonce);
    return sealWithNonce(key, nonce, plain);
  }

  function open(key, sealed) {
    if (sealed.length < 28) throw new Error("short");
    var nonce = sealed.subarray(0, 12);
    var ct = sealed.subarray(12, sealed.length - 16);
    var tag = sealed.subarray(sealed.length - 16);
    var rk = expandKey(key);
    var expect = tagOf(rk, nonce, ct);
    if (!ctEq(tag, expect)) throw new Error("tag");
    return ctrXor(rk, nonce, ct);
  }

  function hexToBytes(hex) {
    var out = new Uint8Array(hex.length / 2);
    for (var i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
  }

  function bytesToHex(bytes) {
    var s = "";
    for (var i = 0; i < bytes.length; i++) s += bytes[i].toString(16).padStart(2, "0");
    return s;
  }

  function selfTest() {
    var empty = bytesToHex(sha256(new Uint8Array(0)));
    if (empty !== "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") {
      throw new Error("sha256");
    }
    var abc = bytesToHex(sha256(new TextEncoder().encode("abc")));
    if (abc !== "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") {
      throw new Error("sha256");
    }
    var key = hexToBytes("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    var block = hexToBytes("00112233445566778899aabbccddeeff");
    var enc = bytesToHex(aesEncryptBlock(expandKey(key), block));
    if (enc !== "8ea2b7ca516745bfeafc49904b496089") throw new Error("aes");
    var gcmKey = new Uint8Array(32);
    for (var i = 0; i < 32; i++) gcmKey[i] = i;
    var gcmNonce = new Uint8Array(12);
    for (var n = 0; n < 12; n++) gcmNonce[n] = n + 1;
    var gcm = bytesToHex(sealWithNonce(gcmKey, gcmNonce, new TextEncoder().encode("hello webbridge"))).slice(24);
    if (gcm !== "6d8f36b983b487e32ec0112e74748f60f01434e1eca694f503f4fcec5c820d") throw new Error("gcm");
    var hk = bytesToHex(hkdf(gcmKey, new TextEncoder().encode("sess"), INFO, 32));
    if (hk !== "a812be02b63cf780d3b8429fd95209837f61551639f84362e359b6a1caebd433") throw new Error("hkdf");
  }

  function b64urlToBytes(s) {
    var t = String(s).replace(/-/g, "+").replace(/_/g, "/");
    while (t.length % 4) t += "=";
    var bin = atob(t);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesToStdB64(bytes) {
    var s = "";
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }

  function stdB64ToBytes(s) {
    var bin = atob(s);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function randId() {
    var b = new Uint8Array(16);
    root.crypto.getRandomValues(b);
    return bytesToHex(b);
  }

  function copyText(text) {
    var ta = document.createElement("textarea");
    ta.value = text == null ? "" : String(text);
    ta.setAttribute("autocomplete", "off");
    ta.setAttribute("autocorrect", "off");
    ta.setAttribute("autocapitalize", "off");
    ta.setAttribute("spellcheck", "false");
    ta.style.cssText = "position:fixed;top:0;left:0;width:2px;height:2px;padding:0;border:0;outline:0;box-shadow:none;background:transparent;font-size:16px;";
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try { ta.setSelectionRange(0, ta.value.length); } catch (e) { /* 老浏览器没有这个方法 */ }
    var via = false;
    function onCopy(ev) {
      try {
        ev.clipboardData.setData("text/plain", ta.value);
        ev.preventDefault();
        via = true;
      } catch (err) { /* 有的浏览器不允许改剪贴板数据 */ }
    }
    document.addEventListener("copy", onCopy, true);
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e2) { ok = false; }
    document.removeEventListener("copy", onCopy, true);
    ta.remove();
    return via || ok;
  }

  function fmtTime(ts) {
    var n = typeof ts === "number" ? ts : Number(ts);
    var d = new Date(n > 0 ? n : Date.now());
    if (isNaN(d.getTime())) d = new Date();
    function p(v) { return v < 10 ? "0" + v : String(v); }
    return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  var floatKind = "wait";
  var floatBusy = false;
  var floatPct = 0;
  var floatPrefs = { show: true, send: false, dlPrompt: false, lockZoom: false, lockScale: 1, x: null, y: null };
  var VIEW_FREE = "width=device-width, initial-scale=1, viewport-fit=cover";
  function blockZoomGesture(e) { e.preventDefault(); }
  function blockPinch(e) {
    if (e.touches && e.touches.length > 1) e.preventDefault();
  }
  var floatDragged = false;
  function loadFloatPrefs() {
    try {
      var raw = localStorage.getItem("ts-web-ui");
      if (!raw) return;
      var o = JSON.parse(raw);
      if (o.show === false) floatPrefs.show = false;
      if (o.send) floatPrefs.send = true;
      if (o.dlPrompt) floatPrefs.dlPrompt = true;
      if (o.lockZoom) floatPrefs.lockZoom = true;
      if (typeof o.lockScale === "number" && o.lockScale > 0) floatPrefs.lockScale = o.lockScale;
      if (typeof o.x === "number" && typeof o.y === "number") {
        floatPrefs.x = o.x;
        floatPrefs.y = o.y;
      }
    } catch (e) { /* 读不到就用默认 */ }
  }
  function saveFloatPrefs() {
    try { localStorage.setItem("ts-web-ui", JSON.stringify(floatPrefs)); } catch (e) {}
  }
  function viewMeta() {
    return document.querySelector('meta[name="viewport"]');
  }
  function applyZoomLock() {
    var meta = viewMeta();
    var on = !!floatPrefs.lockZoom;
    if (meta) {
      if (on) {
        var s = String(Math.round(floatPrefs.lockScale * 1000) / 1000);
        meta.setAttribute("content", "width=device-width, initial-scale=" + s + ", minimum-scale=" + s + ", maximum-scale=" + s + ", user-scalable=no, viewport-fit=cover");
      } else {
        meta.setAttribute("content", VIEW_FREE);
      }
    }
    if (on) {
      document.addEventListener("gesturestart", blockZoomGesture, { passive: false });
      document.addEventListener("gesturechange", blockZoomGesture, { passive: false });
      document.addEventListener("touchmove", blockPinch, { passive: false });
    } else {
      document.removeEventListener("gesturestart", blockZoomGesture);
      document.removeEventListener("gesturechange", blockZoomGesture);
      document.removeEventListener("touchmove", blockPinch);
    }
    var btn = document.getElementById("floatLock");
    if (btn) {
      btn.textContent = on ? "已固定" : "固定";
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    }
    var box = document.getElementById("optZoom");
    if (box) box.checked = on;
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(placeFloat);
  }
  function setZoomLock(on) {
    floatPrefs.lockZoom = !!on;
    if (on) {
      var scale = window.visualViewport && window.visualViewport.scale ? window.visualViewport.scale : 1;
      if (!(scale > 0)) scale = 1;
      floatPrefs.lockScale = Math.max(0.5, Math.min(scale, 5));
    }
    saveFloatPrefs();
    applyZoomLock();
  }
  function placeFloat() {
    var dock = document.getElementById("floatDock") || document.getElementById("floatDot");
    if (!dock) return;
    var w = dock.offsetWidth;
    var h = dock.offsetHeight;
    if (!w || !h) return;
    var vv = window.visualViewport;
    var originLeft = vv ? vv.offsetLeft : 0;
    var originTop = vv ? vv.offsetTop : 0;
    var vw = vv ? vv.width : window.innerWidth;
    var vh = vv ? vv.height : window.innerHeight;
    var x = floatPrefs.x;
    var y = floatPrefs.y;
    if (x == null || y == null) {
      x = vw - w - 14;
      y = vh - h - 14;
    }
    x = Math.max(8, Math.min(vw - w - 8, x));
    y = Math.max(8, Math.min(vh - h - 8, y));
    dock.style.left = Math.round(originLeft + x) + "px";
    dock.style.top = Math.round(originTop + y) + "px";
    dock.style.right = "auto";
    dock.style.bottom = "auto";
  }
  function paintFloat() {
    if (typeof document === "undefined") return;
    var dot = document.getElementById("floatDot");
    var label = document.getElementById("floatText");
    var main = document.getElementById("floatMain");
    if (!dot) return;
    if (!floatPrefs.show) {
      dot.hidden = true;
      var menuOff = document.getElementById("floatMenu");
      if (menuOff) menuOff.hidden = true;
      return;
    }
    var header = document.querySelector(".top");
    var away = !header || header.getBoundingClientRect().bottom < 4;
    var prog = document.getElementById("progressWrap");
    var rect = prog ? prog.getBoundingClientRect() : null;
    var progAway = !!(floatBusy && rect && (rect.bottom < 48 || rect.top > window.innerHeight - 24));
    var dock = document.getElementById("floatDock");
    var dragging = (dock && dock.classList.contains("dragging")) || dot.classList.contains("dragging");
    if (!dragging) dot.hidden = false;
    var kind = floatKind || "wait";
    if (floatBusy && progAway) kind = "busy";
    var text = "";
    if (floatBusy && progAway) text = Math.round(floatPct || 0) + "%";
    else if (away && kind === "down") text = "重连中";
    else if (away && kind === "dead") text = "未连接";
    else if (away && kind === "wait") text = "连接中";
    var solo = !text;
    dot.className = "float-dot " + kind + (solo ? " solo" : "") + (dot.classList.contains("dragging") ? " dragging" : "");
    if (label) {
      label.textContent = text;
      label.hidden = !text;
    }
    if (main) main.setAttribute("aria-label", text || (kind === "ok" ? "已连接" : "连接状态"));
    placeFloat();
  }
  function showAppVersion(ver) {
    if (!ver) return;
    var line = document.getElementById("verLine");
    if (line) line.textContent = hostWord + "端 " + ver;
    var mode = document.getElementById("modeLabel");
    if (!mode) return;
    var base = mode.textContent.indexOf("未加密") >= 0 ? "兼容 · 未加密"
      : (mode.textContent.indexOf("证书") >= 0 ? "兼容 · 证书" : "兼容模式");
    mode.textContent = base + " · " + ver;
  }
  function noteProgress(on, pct) {
    floatBusy = !!on;
    floatPct = on ? (pct || 0) : 0;
    paintFloat();
  }
  function setLink(kind, text) {
    floatKind = kind || "wait";
    var link = document.getElementById("link");
    if (!link) return;
    link.className = "chip " + kind;
    link.textContent = text;
    paintFloat();
  }

  function addCard(hostId, emptyId, text, ts) {
    var host = document.getElementById(hostId);
    var empty = document.getElementById(emptyId);
    if (!host) return;
    if (empty) empty.hidden = true;
    var box = document.createElement("div");
    box.className = "msg";
    var main = document.createElement("div");
    main.className = "main";
    var when = document.createElement("div");
    when.className = "when";
    when.textContent = fmtTime(ts);
    var pre = document.createElement("pre");
    pre.textContent = text;
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "ghost";
    btn.textContent = "复制";
    btn.addEventListener("click", function () {
      var ok = copyText(text);
      if (!ok && window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(String(text)).then(function () {
          btn.textContent = "已复制";
        }).catch(function () {
          btn.textContent = "长按复制";
        });
      } else {
        btn.textContent = ok ? "已复制" : "长按复制";
      }
      setTimeout(function () { btn.textContent = "复制"; }, 1200);
    });
    main.appendChild(when);
    main.appendChild(pre);
    box.appendChild(main);
    box.appendChild(btn);
    host.insertBefore(box, host.firstChild);
    while (host.children.length > 40) host.removeChild(host.lastChild);
  }

  function addIncoming(text, ts) {
    addCard("incoming", "empty", text, ts);
  }

  function addSent(text, ts) {
    addCard("sent", "sentEmpty", text, ts);
  }

  function boot() {
    applyHost();
    var caFold = document.getElementById("caFold");
    var caSteps = document.getElementById("caSteps");
    if (caFold && caSteps) {
      caFold.addEventListener("click", function () {
        var open = caFold.getAttribute("aria-expanded") === "true";
        caFold.setAttribute("aria-expanded", open ? "false" : "true");
        caSteps.hidden = open;
      });
    }
    var status = document.getElementById("status");
    var box = document.getElementById("box");
    var sendBtn = document.getElementById("send");
    var pickImg = document.getElementById("pickImg");
    var pickFile = document.getElementById("pickFile");
    var pickDir = document.getElementById("pickDir");
    var inImg = document.getElementById("inImg");
    var inFile = document.getElementById("inFile");
    var inDir = document.getElementById("inDir");
    var warnEl = document.getElementById("warn");
    var titleEl = document.querySelector("h1");
    var fileBusy = false;
    var alive = true;
    var activeXhr = null;
    var stopAsked = false;
    var currentJob = null;
    var pending = [];
    var sendSeq = 0;
    var sendTotal = 0;
    var sendDone = 0;
    var dropzone = document.getElementById("dropzone");
    function lockPicks(off) {
      [pickImg, pickFile, pickDir].forEach(function (b) {
        if (!b) return;
        if (b.tagName === "BUTTON") b.disabled = off;
        else b.setAttribute("aria-disabled", off ? "true" : "false");
      });
      if (dropzone) {
        dropzone.setAttribute("aria-disabled", off ? "true" : "false");
        if (off) dropzone.classList.remove("over");
      }
      var sendCard = document.getElementById("sendCard");
      if (off && sendCard) sendCard.classList.remove("over");
    }
    var params = new URLSearchParams(location.search);
    var sid = params.get("s") || "";
    var token = params.get("t") || "";
    var modeHint = (params.get("m") || "").toUpperCase();
    var hash = location.hash.charAt(0) === "#" ? location.hash.slice(1) : location.hash;
    var keyB64 = new URLSearchParams(hash).get("k") || "";
    var modeA = modeHint === "A" || (!!token && !keyB64);
    if (modeA) {
      if (!sid || !token) {
        status.textContent = "请用" + hostWord + "上「A 明文」二维码打开，不要手打地址。";
        setLink("dead", "未连接");
        sendBtn.disabled = true;
        box.disabled = true;
        lockPicks(true);
        return;
      }
      var modeLabel = document.getElementById("modeLabel");
      if (modeLabel) modeLabel.textContent = "兼容 · 未加密";
      if (warnEl) {
        warnEl.className = "warn danger";
        warnEl.innerHTML = "<b>未加密。</b>同一局域网的人可能看到内容。要加密请扫" + hostWord + "上的 B。";
      }
    } else {
      try {
        selfTest();
      } catch (e) {
        status.textContent = "页面加密自检失败，请重新从" + hostWord + "打开。";
        setLink("dead", "页面不可用");
        sendBtn.disabled = true;
        box.disabled = true;
        lockPicks(true);
        return;
      }
      if (location.protocol === "https:") {
        var tlsLabel = document.getElementById("modeLabel");
        if (tlsLabel) tlsLabel.textContent = "兼容 · 证书";
      }
      if (!sid || !keyB64) {
        status.textContent = "请用" + hostWord + "上「兼容模式」的二维码打开，不要手打地址。";
        setLink("dead", "未连接");
        sendBtn.disabled = true;
        box.disabled = true;
        lockPicks(true);
        return;
      }
      if (location.protocol === "https:") {
        var caRow = document.getElementById("caRow");
        var installCa = document.getElementById("installCa");
        if (caRow && installCa) {
          installCa.href = "/ca.crt?s=" + encodeURIComponent(sid);
          caRow.hidden = false;
        }
      }
    }
    var key = null;
    if (!modeA) {
      try {
        var ikm = b64urlToBytes(keyB64);
        if (ikm.length !== 32) throw new Error("key");
        key = hkdf(ikm, new TextEncoder().encode(sid), INFO, 32);
      } catch (e) {
        status.textContent = "密钥无效，请重新扫码。";
        setLink("dead", "密钥无效");
        sendBtn.disabled = true;
        box.disabled = true;
        lockPicks(true);
        return;
      }
    }
    function qAuth() {
      var q = "s=" + encodeURIComponent(sid);
      if (modeA) q += "&t=" + encodeURIComponent(token);
      return q;
    }
    function authHeaders(extra) {
      var h = extra ? Object.assign({}, extra) : {};
      if (modeA) h["Authorization"] = "Bearer " + token;
      return h;
    }
    setLink("wait", "连接中");
    var dropped = false;
    function markDown(text) {
      dropped = true;
      setLink("down", text);
    }
    function markDead(text) {
      alive = false;
      dropped = true;
      sendSeq++;
      pending = [];
      fileBusy = false;
      setProgress(false);
      renderPending();
      setLink("dead", text);
      sendBtn.disabled = true;
      lockPicks(true);
      ["dlSelected", "dlAll", "clearIn"].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) b.disabled = true;
      });
    }
    var es = null;
    var downTimer = null;
    var pickerArmed = false;
    function quietCloseEvents() {
      if (downTimer) {
        clearTimeout(downTimer);
        downTimer = null;
      }
      if (!es) return;
      es.onerror = null;
      es.onopen = null;
      try { es.close(); } catch (err) {}
      es = null;
    }
    function openEvents() {
      quietCloseEvents();
      var stream = new EventSource("/api/events?" + qAuth());
      es = stream;
      stream.onopen = function () {
        if (stream !== es) return;
        if (downTimer) {
          clearTimeout(downTimer);
          downTimer = null;
        }
        dropped = false;
        setLink("ok", "已连接");
        pullMeta();
        pullOutbox();
      };
      stream.addEventListener("msg", function (ev) {
        try {
          var msg;
          if (modeA) {
            msg = JSON.parse(String(ev.data));
          } else {
            var plain = open(key, stdB64ToBytes(String(ev.data).trim()));
            msg = JSON.parse(new TextDecoder().decode(plain));
          }
          if (msg && msg.type === "text" && typeof msg.body === "string") addIncoming(msg.body, msg.ts);
          else if (msg && msg.type === "file" && msg.id) addIncomingFile(msg);
          else if (msg && msg.type === "file-gone" && msg.id) removeIncomingFile(msg.id);
          else if (msg && msg.type === "file-clear") clearIncomingFiles();
          if (!dropped) setLink("ok", "已连接");
        } catch (e) {
          status.textContent = modeA
            ? "有一条下行读不了。会话可能已换，请重新扫码。"
            : "有一条下行解不开。会话可能已换，请重新扫码。";
        }
      });
      stream.addEventListener("bye", function () {
        if (stream !== es) return;
        try { stream.close(); } catch (err) {}
        markDead("会话已失效，请重新扫码");
      });
      stream.onerror = function () {
        if (stream !== es) return;
        if (document.visibilityState === "hidden") return;
        if (stream.readyState === EventSource.CLOSED) {
          markDead("和" + hostWord + "断开了");
          return;
        }
        if (downTimer) return;
        downTimer = setTimeout(function () {
          downTimer = null;
          if (!es || es.readyState === EventSource.OPEN) return;
          if (document.visibilityState === "hidden") return;
          markDown("和" + hostWord + "断开了，正在重连");
          fetch("/api/meta?" + qAuth(), { cache: "no-store", headers: authHeaders() }).then(function (r) {
            if (r.status === 401) {
              if (es) {
                try { es.close(); } catch (err) {}
              }
              markDead("会话已失效，请重新扫码");
            }
          }).catch(function () {
            markDown("和" + hostWord + "断开了，正在重连");
          });
        }, 700);
      };
    }
    function pullMeta() {
      fetch("/api/meta?" + qAuth(), { cache: "no-store", headers: authHeaders() }).then(function (r) {
        if (!r.ok) return null;
        return r.json();
      }).then(function (info) {
        if (info && info.host) applyHost(String(info.host));
        if (info && info.app) showAppVersion(String(info.app));
      }).catch(function () {});
    }
    pullMeta();
    pullOutbox();
    openEvents();
    window.addEventListener("pagehide", quietCloseEvents);
    window.addEventListener("pageshow", function () {
      harvestPicked();
      if (!es || es.readyState === EventSource.CLOSED) openEvents();
    });
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "hidden") {
        if (downTimer) {
          clearTimeout(downTimer);
          downTimer = null;
        }
        return;
      }
      harvestPicked();
      if (!es || es.readyState === EventSource.CLOSED) openEvents();
    });

    function send() {
      var text = box.value;
      if (!text) return;
      if (new TextEncoder().encode(text).length > 65536) {
        status.textContent = "文字超过 64KB";
        return;
      }
      sendBtn.disabled = true;
      var sentAt = Date.now();
      var payload = JSON.stringify({
        v: 1,
        type: "text",
        id: randId(),
        ts: sentAt,
        body: text
      });
      var body;
      var headers;
      if (modeA) {
        body = payload;
        headers = authHeaders({ "Content-Type": "application/json; charset=utf-8" });
      } else {
        try {
          body = seal(key, new TextEncoder().encode(payload));
        } catch (e) {
          sendBtn.disabled = false;
          status.textContent = "加密失败";
          return;
        }
        headers = { "Content-Type": "application/octet-stream" };
      }
      fetch("/api/send?" + qAuth(), {
        method: "POST",
        headers: headers,
        body: body
      }).then(function (res) {
        if (res.status === 204) {
          box.value = "";
          status.textContent = "已发到" + hostWord;
          addSent(text, sentAt);
        } else if (res.status === 401) {
          status.textContent = "会话已失效，请重新扫码。";
        } else {
          status.textContent = "发送失败（" + res.status + "）";
        }
      }).catch(function () {
        status.textContent = "发送失败";
        markDown("和" + hostWord + "断开了，正在重连");
      }).then(function () {
        sendBtn.disabled = false;
      });
    }

    sendBtn.addEventListener("click", send);
    var floatSend = document.getElementById("floatSend");
    if (floatSend) floatSend.addEventListener("click", send);
    box.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        send();
      }
    });

    var pendingBox = document.getElementById("pendingBox");
    var pendingList = document.getElementById("pendingList");
    var pendingCount = document.getElementById("pendingCount");
    var sendPendingBtn = document.getElementById("sendPending");
    var clearPendingBtn = document.getElementById("clearPending");
    var progressWrap = document.getElementById("progressWrap");
    var progressBar = document.getElementById("progressBar");
    var progressText = document.getElementById("progressText");
    function fmtSize(n) {
      if (n < 1024) return n + " B";
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
      if (n < 1024 * 1024 * 1024) return (n / (1024 * 1024)).toFixed(1) + " MB";
      return (n / (1024 * 1024 * 1024)).toFixed(2) + " GB";
    }
    function fmtSpeed(bps) {
      if (!bps || bps <= 0) return "—";
      if (bps < 1024) return Math.round(bps) + " B/s";
      if (bps < 1024 * 1024) return (bps / 1024).toFixed(1) + " KB/s";
      return (bps / (1024 * 1024)).toFixed(1) + " MB/s";
    }
    function fmtEta(sec) {
      sec = Math.max(0, Math.round(sec));
      if (sec < 60) return sec + " 秒";
      var m = Math.floor(sec / 60);
      var s = sec % 60;
      if (m < 60) return m + " 分 " + (s < 10 ? "0" : "") + s + " 秒";
      var h = Math.floor(m / 60);
      return h + " 小时 " + (m % 60) + " 分";
    }
    function jobLabel(job) {
      var file = job.file;
      var fallback = file.type === "image/jpeg" ? "粘贴.jpg"
        : file.type === "image/gif" ? "粘贴.gif"
        : file.type === "image/webp" ? "粘贴.webp"
        : (file.type && file.type.indexOf("image/") === 0 ? "粘贴.png" : "file");
      return file.webkitRelativePath || file.name || fallback;
    }
    function setProgress(on, pct, text) {
      noteProgress(!!on && !!progressWrap, pct);
      if (!progressWrap) return;
      if (!on) {
        progressWrap.classList.remove("on");
        if (progressBar) progressBar.style.width = "0%";
        if (progressText) progressText.textContent = "";
        return;
      }
      progressWrap.classList.add("on");
      if (progressBar) progressBar.style.width = Math.max(0, Math.min(100, pct || 0)) + "%";
      if (progressText) progressText.textContent = text || "";
    }
    function renderPending() {
      if (!pendingBox || !pendingList) return;
      pendingBox.hidden = pending.length === 0 && !fileBusy;
      if (pendingCount) pendingCount.textContent = String(pending.length);
      pendingList.innerHTML = "";
      for (var i = 0; i < pending.length; i++) {
        (function (idx) {
          var job = pending[idx];
          var row = document.createElement("div");
          row.className = "pending-item";
          var name = document.createElement("div");
          name.className = "name";
          name.textContent = jobLabel(job);
          var meta = document.createElement("span");
          meta.className = "meta";
          meta.textContent = fmtSize(job.file.size || 0);
          var rm = document.createElement("button");
          rm.type = "button";
          rm.textContent = "移除";
          rm.disabled = fileBusy;
          rm.addEventListener("click", function () {
            if (fileBusy) return;
            pending.splice(idx, 1);
            renderPending();
            status.textContent = pending.length ? ("待发 " + pending.length + " 个") : "";
          });
          row.appendChild(name);
          row.appendChild(meta);
          row.appendChild(rm);
          pendingList.appendChild(row);
        })(i);
      }
      if (sendPendingBtn) sendPendingBtn.disabled = fileBusy || pending.length === 0 || !alive;
      if (clearPendingBtn) clearPendingBtn.disabled = fileBusy || pending.length === 0;
    }
    function looksImage(file) {
      if (file.type && file.type.indexOf("image/") === 0) return true;
      return /\.(png|jpe?g|gif|bmp|webp|tiff?|heic|heif)$/i.test(file.name || "");
    }
    function postBody(url, body, onProg, seq) {
      return new Promise(function (resolve, reject) {
        if (stopAsked || (seq != null && seq !== sendSeq)) {
          reject(new Error("已取消"));
          return;
        }
        var xhr = new XMLHttpRequest();
        activeXhr = xhr;
        function clear() {
          if (activeXhr === xhr) activeXhr = null;
        }
        xhr.open("POST", url);
        var headers = authHeaders({ "Content-Type": "application/octet-stream" });
        Object.keys(headers).forEach(function (k) { xhr.setRequestHeader(k, headers[k]); });
        var t0 = Date.now();
        var lastT = t0;
        var lastLoaded = 0;
        xhr.upload.onprogress = function (ev) {
          if (stopAsked || (seq != null && seq !== sendSeq) || !ev.lengthComputable || !onProg) return;
          var now = Date.now();
          var dt = Math.max(1, now - lastT);
          var speed = ((ev.loaded - lastLoaded) * 1000) / dt;
          lastT = now;
          lastLoaded = ev.loaded;
          var elapsed = Math.max(1, now - t0);
          var avg = (ev.loaded * 1000) / elapsed;
          onProg(ev.loaded, ev.total, avg || speed);
        };
        xhr.onload = function () {
          clear();
          resolve({ status: xhr.status, text: xhr.responseText || "" });
        };
        xhr.onerror = function () { clear(); reject(new Error("网络错误")); };
        xhr.onabort = function () { clear(); reject(new Error("已取消")); };
        xhr.send(body);
      });
    }
    var pool = [];
    var poolCursor = 0;
    var poolFailed = false;
    var sealSeq = 0;
    var sealWait = {};
    function sealOnPage(plain) {
      return new Promise(function (resolve, reject) {
        setTimeout(function () {
          try { resolve(seal(key, plain)); }
          catch (err) { reject(new Error("加密失败")); }
        }, 0);
      });
    }
    function dropWorker(w) {
      var i = pool.indexOf(w);
      if (i >= 0) pool.splice(i, 1);
      try { w.terminate(); } catch (e) {}
    }
    function openWorker() {
      var w = new Worker(new URL("/bridge.js", location.href).href);
      w.onmessage = function (ev) {
        var job = sealWait[ev.data.n];
        if (!job) return;
        delete sealWait[ev.data.n];
        if (ev.data.ok && (ev.data.sealed || ev.data.plain)) job.resolve(ev.data.sealed || ev.data.plain);
        else job.fail();
      };
      w.onerror = function (ev) {
        if (ev && ev.preventDefault) ev.preventDefault();
        dropWorker(w);
        if (!pool.length) poolFailed = true;
        Object.keys(sealWait).forEach(function (n) {
          var job = sealWait[n];
          delete sealWait[n];
          if (job) job.fail();
        });
      };
      return w;
    }
    function ensurePool() {
      if (modeA || pool.length || poolFailed || typeof Worker === "undefined") return;
      var n = (typeof navigator !== "undefined" && navigator.hardwareConcurrency) || 4;
      if (n < 2) n = 2;
      if (n > 4) n = 4;
      try {
        for (var i = 0; i < n; i++) pool.push(openWorker());
      } catch (e) {
        pool.forEach(function (w) { try { w.terminate(); } catch (e2) {} });
        pool = [];
        poolFailed = true;
      }
    }
    function sealOrRaw(bytes) {
      if (modeA || !key) return Promise.resolve(bytes);
      var plain = new Uint8Array(bytes);
      ensurePool();
      if (!pool.length) return sealOnPage(plain);
      var worker = pool[poolCursor % pool.length];
      poolCursor++;
      return new Promise(function (resolve, reject) {
        var n = ++sealSeq;
        var settled = false;
        function ok(out) {
          if (settled) return;
          settled = true;
          resolve(out);
        }
        function fail() {
          if (settled) return;
          settled = true;
          sealOnPage(plain).then(ok, reject);
        }
        sealWait[n] = { resolve: ok, fail: fail };
        try {
          var copy = new Uint8Array(plain);
          worker.postMessage({ n: n, key: new Uint8Array(key), plain: copy }, [copy.buffer]);
        } catch (err) {
          delete sealWait[n];
          dropWorker(worker);
          fail();
        }
      });
    }
    var OUT_CHUNK = 1024 * 1024;
    var PREVIEW_MAX = 20 * 1024 * 1024;
    var dlBusy = false;
    var dlCtrl = null;
    var dlCurrentId = "";
    var dlSelected = document.getElementById("dlSelected");
    var dlAll = document.getElementById("dlAll");
    var stopInBtn = document.getElementById("stopIn");
    var clearInBtn = document.getElementById("clearIn");
    function pullOutbox() {
      fetch("/api/out/list?" + qAuth(), { cache: "no-store", headers: authHeaders() }).then(function (r) {
        if (!r.ok) return null;
        return r.arrayBuffer();
      }).then(function (buf) {
        if (!buf || !alive) return;
        var bytes = new Uint8Array(buf);
        var plain = modeA ? bytes : open(key, bytes);
        var msg = JSON.parse(new TextDecoder().decode(plain));
        var items = msg && msg.items;
        if (!items) return;
        for (var i = 0; i < items.length; i++) addIncomingFile(items[i]);
      }).catch(function () {});
    }
    function incomingHost() {
      return document.getElementById("incoming");
    }
    function syncEmpty() {
      var host = incomingHost();
      var empty = document.getElementById("empty");
      if (empty && host) empty.hidden = host.children.length > 0;
    }
    function cardOf(id) {
      var host = incomingHost();
      if (!host) return null;
      var cards = host.querySelectorAll(".in-file");
      for (var i = 0; i < cards.length; i++) {
        if (cards[i].getAttribute("data-id") === id) return cards[i];
      }
      return null;
    }
    function allFileItems() {
      var host = incomingHost();
      var out = [];
      if (!host) return out;
      var cards = host.querySelectorAll(".in-file");
      for (var i = 0; i < cards.length; i++) {
        if (cards[i]._item) out.push(cards[i]._item);
      }
      return out;
    }
    function selectedFileItems() {
      var host = incomingHost();
      var out = [];
      if (!host) return out;
      var cards = host.querySelectorAll(".in-file");
      for (var i = 0; i < cards.length; i++) {
        var box = cards[i].querySelector("input");
        if (box && box.checked && cards[i]._item) out.push(cards[i]._item);
      }
      return out;
    }
    function paintCardBtn(card) {
      if (!card) return;
      var btn = card._btn;
      if (!btn) return;
      var id = card._item && card._item.id;
      if (dlBusy && id && id === dlCurrentId) {
        btn.textContent = "停止";
        btn.className = "stop";
        btn.disabled = false;
        return;
      }
      if (card._blob) {
        btn.textContent = "保存";
        btn.className = "ghost";
        btn.disabled = !alive || dlBusy;
        return;
      }
      btn.textContent = "下载";
      btn.className = "ghost";
      btn.disabled = !alive || dlBusy;
    }
    function paintInTools() {
      var n = allFileItems().length;
      var sel = selectedFileItems().length;
      var off = !alive || dlBusy;
      if (dlSelected) dlSelected.disabled = off || sel === 0;
      if (dlAll) dlAll.disabled = off || n === 0;
      if (stopInBtn) stopInBtn.hidden = !dlBusy;
      if (clearInBtn) clearInBtn.disabled = off || (incomingHost() && incomingHost().children.length === 0);
      var host = incomingHost();
      if (!host) return;
      var cards = host.querySelectorAll(".in-file");
      for (var i = 0; i < cards.length; i++) paintCardBtn(cards[i]);
    }
    function stopIncoming() {
      if (dlCtrl) dlCtrl.abort();
    }
    function setNote(card, text) {
      if (!card || !card.isConnected) return;
      var note = card.querySelector(".file-note");
      if (note) note.textContent = text || "";
    }
    function mimeOf(name) {
      var n = String(name || "").toLowerCase();
      if (/\.png$/.test(n)) return "image/png";
      if (/\.gif$/.test(n)) return "image/gif";
      if (/\.webp$/.test(n)) return "image/webp";
      if (/\.jpe?g$/.test(n)) return "image/jpeg";
      if (/\.bmp$/.test(n)) return "image/bmp";
      return "application/octet-stream";
    }
    function openOnPage(sealed) {
      return new Promise(function (resolve, reject) {
        setTimeout(function () {
          try { resolve(open(key, sealed)); }
          catch (err) { reject(new Error("解不开")); }
        }, 0);
      });
    }
    function openOrRaw(bytes) {
      if (modeA || !key) return Promise.resolve(bytes);
      var sealed = new Uint8Array(bytes);
      ensurePool();
      if (!pool.length) return openOnPage(sealed);
      var worker = pool[poolCursor % pool.length];
      poolCursor++;
      return new Promise(function (resolve, reject) {
        var n = ++sealSeq;
        var settled = false;
        function ok(out) {
          if (settled) return;
          settled = true;
          resolve(out);
        }
        function fail() {
          if (settled) return;
          settled = true;
          openOnPage(sealed).then(ok, reject);
        }
        sealWait[n] = { resolve: ok, fail: fail };
        try {
          var copy = new Uint8Array(sealed);
          worker.postMessage({ n: n, op: "open", key: new Uint8Array(key), sealed: copy }, [copy.buffer]);
        } catch (err) {
          delete sealWait[n];
          dropWorker(worker);
          fail();
        }
      });
    }
    function fetchChunk(id, index, signal) {
      return fetch("/api/out/chunk?" + qAuth() + "&id=" + encodeURIComponent(id) + "&i=" + index, {
        cache: "no-store",
        headers: authHeaders(),
        signal: signal
      }).then(function (r) {
        if (r.status === 404) throw new Error("文件已不在" + hostWord + "上");
        if (!r.ok) throw new Error("下载失败");
        return r.arrayBuffer();
      }).then(function (buf) {
        return openOrRaw(new Uint8Array(buf));
      });
    }
    function pullFile(item, onProg, signal) {
      var size = item.size || 0;
      var chunks = size === 0 ? 0 : Math.ceil(size / OUT_CHUNK);
      return new Promise(function (resolve, reject) {
        var failed = false;
        function die(err) {
          if (failed) return;
          failed = true;
          reject(err || new DOMException("已停止", "AbortError"));
        }
        if (signal) {
          if (signal.aborted) {
            die();
            return;
          }
          signal.addEventListener("abort", function () { die(); });
        }
        if (!chunks) {
          resolve(new Blob([], { type: mimeOf(item.name) }));
          return;
        }
        var parts = new Array(chunks);
        var next = 0;
        var inflight = 0;
        var parallel = 2;
        function pump() {
          if (failed) return;
          if (signal && signal.aborted) {
            die();
            return;
          }
          if (next >= chunks && inflight === 0) {
            resolve(new Blob(parts, { type: mimeOf(item.name) }));
            return;
          }
          while (inflight < parallel && next < chunks) {
            (function (i) {
              inflight++;
              fetchChunk(item.id, i, signal).then(function (bytes) {
                if (failed || (signal && signal.aborted)) return;
                parts[i] = bytes;
                inflight--;
                if (onProg) {
                  var done = 0;
                  for (var k = 0; k < parts.length; k++) {
                    if (parts[k]) done += parts[k].length;
                  }
                  onProg(Math.min(size, done), size);
                }
                pump();
              }, function (err) {
                if (signal && signal.aborted) {
                  die();
                  return;
                }
                die(err);
              });
            })(next);
            next++;
          }
        }
        pump();
      });
    }
    function anchorDownload(blob, name) {
      var url = URL.createObjectURL(blob);
      var a = document.createElement("a");
      a.href = url;
      a.download = name || "file";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
    }
    function isAbort(err) {
      return !!(err && (err.name === "AbortError" || err.message === "已停止"));
    }
    function downloadItems(items) {
      if (!items || !items.length || dlBusy || !alive) return;
      dlBusy = true;
      dlCtrl = new AbortController();
      var signal = dlCtrl.signal;
      paintInTools();
      var i = 0;
      function finish() {
        dlBusy = false;
        dlCtrl = null;
        dlCurrentId = "";
        paintInTools();
      }
      function next() {
        if (!alive || signal.aborted) {
          finish();
          return;
        }
        if (i >= items.length) {
          finish();
          status.textContent = floatPrefs.dlPrompt
            ? "已接收 " + items.length + " 个"
            : "已接收 " + items.length + " 个，点保存";
          return;
        }
        var it = items[i++];
        dlCurrentId = it.id;
        paintInTools();
        setNote(cardOf(it.id), "下载中");
        pullFile(it, function (done, total) {
          setNote(cardOf(it.id), "下载 " + fmtSize(done) + " / " + fmtSize(total));
        }, signal).then(function (blob) {
          if (signal.aborted) return;
          var card = cardOf(it.id);
          if (card) card._blob = blob;
          if (floatPrefs.dlPrompt) {
            anchorDownload(blob, it.name);
            setNote(card, "已交给浏览器保存");
          } else {
            setNote(card, "已接收，点保存");
          }
          setTimeout(next, 200);
        }).catch(function (err) {
          if (isAbort(err) || signal.aborted) {
            setNote(cardOf(it.id), "已停止");
            status.textContent = "已停止";
            finish();
            return;
          }
          setNote(cardOf(it.id), (err && err.message) || "下载失败");
          status.textContent = (err && err.message) || "下载失败";
          finish();
        });
      }
      next();
    }
    function addIncomingFile(msg) {
      if (!msg || !msg.id) return;
      if (cardOf(String(msg.id))) return;
      var host = incomingHost();
      if (!host) return;
      var item = {
        id: String(msg.id),
        name: msg.name ? String(msg.name) : "file",
        rel: msg.rel ? String(msg.rel) : "",
        size: typeof msg.size === "number" ? msg.size : 0,
        kind: msg.kind === "image" ? "image" : "file",
        ts: msg.ts || Date.now()
      };
      var box = document.createElement("div");
      box.className = "msg in-file";
      box.setAttribute("data-id", item.id);
      box._item = item;
      var pick = document.createElement("label");
      pick.className = "pick";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.addEventListener("change", paintInTools);
      pick.appendChild(cb);
      var main = document.createElement("div");
      main.className = "main";
      var when = document.createElement("div");
      when.className = "when";
      when.textContent = fmtTime(item.ts) + "  ·  " + fmtSize(item.size);
      var fname = document.createElement("div");
      fname.className = "fname";
      fname.textContent = item.name;
      main.appendChild(when);
      main.appendChild(fname);
      if (item.rel && item.rel !== item.name) {
        var rel = document.createElement("div");
        rel.className = "rel";
        rel.textContent = item.rel;
        main.appendChild(rel);
      }
      var note = document.createElement("p");
      note.className = "file-note";
      main.appendChild(note);
      var img = document.createElement("img");
      img.className = "preview";
      img.alt = "";
      img.hidden = true;
      main.appendChild(img);
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ghost";
      btn.textContent = "下载";
      box._btn = btn;
      btn.addEventListener("click", function () {
        if (dlBusy && dlCurrentId === item.id) {
          stopIncoming();
          return;
        }
        if (box._blob) {
          anchorDownload(box._blob, item.name);
          setNote(box, "已保存");
          return;
        }
        downloadItems([item]);
      });
      box.appendChild(pick);
      box.appendChild(main);
      box.appendChild(btn);
      host.insertBefore(box, host.firstChild);
      syncEmpty();
      paintInTools();
      if (item.kind === "image" && item.size > 0 && item.size <= PREVIEW_MAX) {
        setNote(box, "预览中");
        pullFile(item, null).then(function (blob) {
          if (!box.isConnected) return;
          var url = URL.createObjectURL(blob);
          img.src = url;
          img.hidden = false;
          box._preview = url;
          setNote(box, "");
        }).catch(function () {
          setNote(box, "预览失败，可下载");
        });
      } else if (item.kind === "image") {
        setNote(box, "图片较大，请下载后查看");
      }
    }
    function removeIncomingFile(id) {
      var card = cardOf(String(id));
      if (!card) return;
      if (card._preview) URL.revokeObjectURL(card._preview);
      card.remove();
      syncEmpty();
      paintInTools();
    }
    function clearIncomingFiles() {
      var host = incomingHost();
      if (!host) return;
      var cards = host.querySelectorAll(".in-file");
      for (var i = 0; i < cards.length; i++) {
        if (cards[i]._preview) URL.revokeObjectURL(cards[i]._preview);
      }
      while (host.firstChild) host.removeChild(host.firstChild);
      syncEmpty();
      paintInTools();
    }
    if (dlSelected) dlSelected.addEventListener("click", function () { downloadItems(selectedFileItems()); });
    if (dlAll) dlAll.addEventListener("click", function () { downloadItems(allFileItems()); });
    if (stopInBtn) stopInBtn.addEventListener("click", stopIncoming);
    if (clearInBtn) clearInBtn.addEventListener("click", function () {
      if (!alive || dlBusy) return;
      fetch("/api/out/clear?" + qAuth(), { method: "POST", headers: authHeaders() }).then(function (r) {
        if (!r.ok) throw new Error("清空失败");
        clearIncomingFiles();
      }).catch(function (err) {
        status.textContent = (err && err.message) || "清空失败";
      });
    });
    paintInTools();
    function readSlice(file, off, len) {
      return new Promise(function (resolve, reject) {
        var blob = file.slice(off, Math.min(file.size, off + len));
        var reader = new FileReader();
        reader.onload = function () { resolve(new Uint8Array(reader.result)); };
        reader.onerror = function () { reject(new Error("读不到文件")); };
        reader.readAsArrayBuffer(blob);
      });
    }
    function fileStoreKey(label, file) {
      return "ts-file:" + label + "\n" + file.size + "\n" + (file.lastModified || 0);
    }
    function stableId(label, file) {
      var k = fileStoreKey(label, file);
      try {
        var old = localStorage.getItem(k);
        if (old && /^[0-9a-f]{32}$/i.test(old)) return old;
        var id = randId();
        localStorage.setItem(k, id);
        return id;
      } catch (e) {
        return randId();
      }
    }
    function forgetId(label, file) {
      try { localStorage.removeItem(fileStoreKey(label, file)); } catch (e) {}
    }
    function pcStopped(res) {
      return !!(res && String(res.text || "").indexOf("cancelled") >= 0);
    }
    function postRetry(url, body, onProg, tries, seq) {
      return postBody(url, body, onProg, seq).catch(function (err) {
        if (stopAsked || (seq != null && seq !== sendSeq) || (err && err.message === "已取消")) throw err;
        if (tries <= 0) throw err;
        return new Promise(function (r) { setTimeout(r, 500); }).then(function () {
          return postRetry(url, body, onProg, tries - 1, seq);
        });
      });
    }
    function uploadOne(job, index, total, seq) {
      ensurePool();
      var file = job.file;
      var fallback = file.type === "image/jpeg" ? "粘贴.jpg"
        : file.type === "image/gif" ? "粘贴.gif"
        : file.type === "image/webp" ? "粘贴.webp"
        : (file.type && file.type.indexOf("image/") === 0 ? "粘贴.png" : "file");
      var label = jobLabel(job);
      var prefix = "(" + index + "/" + total + ") ";
      var id = stableId(label, file);
      var name = file.name || fallback;
      var rel = job.asDir ? (file.webkitRelativePath || label) : "";
      var speed = { ema: 0, t: Date.now(), b: 0 };
      var jumps = 0;
      function show(done, phase) {
        var now = Date.now();
        var dt = (now - speed.t) / 1000;
        if (dt >= 0.3) {
          var inst = (done - speed.b) / dt;
          if (inst >= 0) speed.ema = speed.ema ? speed.ema * 0.55 + inst * 0.45 : inst;
          speed.t = now;
          speed.b = done;
        }
        var pct = file.size > 0 ? (done * 100 / file.size) : 100;
        var eta = speed.ema > 1 && done < file.size ? (file.size - done) / speed.ema : 0;
        var line = prefix + label
          + "  " + fmtSize(done) + " / " + fmtSize(file.size || 0)
          + "  ·  " + pct.toFixed(0) + "%"
          + "  ·  " + fmtSpeed(speed.ema)
          + (eta >= 1 ? "  ·  剩余 " + fmtEta(eta) : "");
        if (phase) line = phase + "  " + line;
        if (!modeA && pool.length > 1) line += "  ·  " + pool.length + " 线程";
        status.textContent = line;
        setProgress(true, pct, line);
      }
      function halted() {
        return stopAsked || seq !== sendSeq;
      }
      function begin() {
        if (!alive) return Promise.reject(new Error("会话已失效，请重新扫码。"));
        if (halted()) return Promise.reject(new Error("已取消"));
        show(speed.b, "准备");
        var meta = new TextEncoder().encode(JSON.stringify({
          v: 1, type: "file-begin", id: id, name: name, rel: rel, size: file.size || 0, clip: !!job.clip
        }));
        return sealOrRaw(meta).then(function (body) {
          if (halted()) throw new Error("已取消");
          return postRetry("/api/file/begin?" + qAuth(), body, null, 2, seq);
        }).then(function (res) {
          if (res.status === 401) throw new Error("会话已失效，请重新扫码。");
          if (res.status === 413) throw new Error(label + " 超过 20GB");
          if (res.status !== 200) throw new Error(label + " 无法开始（" + res.status + "）");
          var info;
          try { info = JSON.parse(res.text || "{}"); }
          catch (e) { throw new Error(label + " 开始失败"); }
          var next = typeof info.next === "number" ? info.next : 0;
          var chunk = info.chunk > 0 ? info.chunk : (1024 * 1024);
          if (next > 0 && next < file.size) show(next, "续传");
          speed.b = next;
          speed.t = Date.now();
          return sendFrom(next, chunk);
        });
      }
      function sendFrom(start, chunk) {
        if (!alive) return Promise.reject(new Error("会话已失效，请重新扫码。"));
        if (halted()) return Promise.reject(new Error("已取消"));
        if (start >= file.size) return finish();
        var parallel = modeA ? 2 : Math.max(1, pool.length || 1);
        return new Promise(function (resolve, reject) {
          var nextRead = start;
          var nextSend = start;
          var inflight = 0;
          var buffered = 0;
          var sending = false;
          var ready = {};
          var failed = false;
          function fail(err) {
            if (failed) return;
            failed = true;
            reject(err instanceof Error ? err : new Error("发送失败"));
          }
          function done() {
            if (failed || sending) return;
            if (nextSend >= file.size && inflight === 0 && nextRead >= file.size) {
              failed = true;
              finish().then(resolve, reject);
            }
          }
          function pumpRead() {
            if (failed) return;
            if (halted()) {
              fail(new Error("已取消"));
              return;
            }
            while (inflight + buffered < parallel && nextRead < file.size) {
              var off = nextRead;
              var len = Math.min(chunk, file.size - off);
              nextRead += len;
              inflight++;
              // off/len 必须按次抄下来。var 在循环里是同一个变量，回调会全写到最后一块上，传输就停住。
              (function (at, n) {
                readSlice(file, at, n).then(function (bytes) {
                  if (halted()) throw new Error("已取消");
                  return sealOrRaw(bytes);
                }).then(function (body) {
                  if (failed) return;
                  ready[at] = body;
                  inflight--;
                  buffered++;
                  pumpSend();
                  pumpRead();
                  done();
                }).catch(function (err) {
                  fail(err instanceof Error ? err : new Error("加密失败"));
                });
              })(off, len);
            }
          }
          function pumpSend() {
            if (failed || sending) return;
            if (halted()) {
              fail(new Error("已取消"));
              return;
            }
            if (ready[nextSend] == null) {
              done();
              return;
            }
            var off = nextSend;
            var body = ready[off];
            delete ready[off];
            buffered--;
            var len = Math.min(chunk, file.size - off);
            var part = Math.floor(off / chunk);
            sending = true;
            show(off, "发送");
            pumpRead();
            postRetry("/api/file/chunk?" + qAuth() + "&id=" + encodeURIComponent(id) + "&i=" + part, body, function (loaded) {
              show(Math.min(file.size, off + (loaded || 0)), "发送");
            }, 2, seq).then(function (res) {
              sending = false;
              if (failed) return;
              if (pcStopped(res)) {
                fail(new Error(hostWord + "已停止"));
                return;
              }
              if (res.status === 204) {
                nextSend = off + len;
                show(Math.min(file.size, nextSend), "发送");
                if (nextSend >= file.size) {
                  done();
                  return;
                }
                pumpSend();
                pumpRead();
                return;
              }
              if ((res.status === 409 || res.status === 404) && jumps < 2) {
                jumps++;
                failed = true;
                begin().then(resolve, reject);
                return;
              }
              if (res.status === 401) {
                fail(new Error("会话已失效，请重新扫码。"));
                return;
              }
              fail(new Error(label + " 发送失败（" + res.status + "）"));
            }).catch(function (err) {
              sending = false;
              fail(err instanceof Error ? err : new Error("发送失败"));
            });
          }
          show(start, modeA ? "读取" : "加密");
          pumpRead();
        });
      }
      function finish() {
        if (halted()) return Promise.reject(new Error("已取消"));
        show(file.size, "保存");
        return postRetry("/api/file/finish?" + qAuth() + "&id=" + encodeURIComponent(id), new Uint8Array(0), null, 2, seq)
          .then(function (res) {
            if (pcStopped(res)) throw new Error(hostWord + "已停止");
            if (res.status === 204) {
              forgetId(label, file);
              addSent(label, Date.now());
              return;
            }
            if (res.status === 409 && jumps < 2) {
              jumps++;
              return begin();
            }
            if (res.status === 401) throw new Error("会话已失效，请重新扫码。");
            throw new Error(label + " 保存失败（" + res.status + "）");
          });
      }
      return begin();
    }
    function pumpFiles(seq) {
      if (seq !== sendSeq) return;
      if (!alive) {
        fileBusy = false;
        lockPicks(false);
        renderPending();
        return;
      }
      if (!pending.length) {
        fileBusy = false;
        lockPicks(false);
        setProgress(false);
        status.textContent = "已发到" + hostWord;
        renderPending();
        return;
      }
      fileBusy = true;
      lockPicks(true);
      renderPending();
      var job = pending.shift();
      currentJob = job;
      sendDone++;
      var index = sendDone;
      var all = sendTotal;
      setTimeout(function () {
        if (seq !== sendSeq || stopAsked) {
          if (stopAsked) finishStop(job);
          return;
        }
        uploadOne(job, index, all, seq).then(function () {
          if (seq !== sendSeq || stopAsked) {
            if (stopAsked) finishStop(job);
            return;
          }
          currentJob = null;
          pumpFiles(seq);
        }).catch(function (err) {
          var cancelled = stopAsked || (err && err.message === "已取消");
          if (cancelled) {
            finishStop(job);
            return;
          }
          if (seq !== sendSeq) return;
          pending.unshift(job);
          currentJob = null;
          if (sendDone > 0) sendDone--;
          fileBusy = false;
          lockPicks(false);
          setProgress(false);
          renderPending();
          var msg = err && err.message ? err.message : "发送失败";
          status.textContent = msg + "。再点「发送待发」会从断点继续。";
        });
      }, 20);
    }
    function finishStop(job) {
      if (job && pending.indexOf(job) < 0) pending.unshift(job);
      currentJob = null;
      stopAsked = false;
      fileBusy = false;
      lockPicks(false);
      setProgress(false);
      renderPending();
      status.textContent = "已停止。待发还在列表里。想继续再点「发送待发」，会从断点接着传。";
    }
    function tellPcStop() {
      if (!alive || !currentJob) return;
      var id = stableId(jobLabel(currentJob), currentJob.file);
      try {
        var xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/cancel?" + qAuth() + "&id=" + encodeURIComponent(id));
        var headers = authHeaders({ "Content-Type": "application/octet-stream" });
        Object.keys(headers).forEach(function (k) { xhr.setRequestHeader(k, headers[k]); });
        xhr.send(new Uint8Array(0));
      } catch (e) { /* 告诉不上对面，半截还在，下次再发会续上 */ }
    }
    function stopTransfer() {
      if (!fileBusy && !activeXhr) return;
      tellPcStop();
      stopAsked = true;
      sendSeq++;
      status.textContent = "正在停止…";
      var xhr = activeXhr;
      activeXhr = null;
      if (xhr) {
        try { xhr.abort(); } catch (e) {}
      } else if (currentJob) {
        finishStop(currentJob);
      }
    }
    function startPendingSend() {
      if (!alive || fileBusy || !pending.length) return;
      sendSeq++;
      sendTotal = pending.length;
      sendDone = 0;
      var seq = sendSeq;
      status.textContent = "开始发送 " + pending.length + " 个文件…";
      pumpFiles(seq);
    }
    function enqueue(list, asDir) {
      if (!alive || !list || !list.length) {
        status.textContent = "没有选到文件（若点了图片却没反应，请再用「文件」选一次）";
        return;
      }
      var files = [];
      for (var i = 0; i < list.length; i++) files.push(list[i]);
      var added = 0;
      for (var j = 0; j < files.length; j++) {
        var f = files[j];
        if (!f) continue;
        pending.push({
          file: f,
          asDir: !!asDir,
          clip: !asDir && files.length === 1 && looksImage(f)
        });
        added++;
      }
      renderPending();
      if (!added) {
        status.textContent = "没有选到文件";
        return;
      }
      status.textContent = "已加入待发 " + added + " 个。点「发送待发」开始传。";
    }
    function iosNoFolder() {
      var ua = navigator.userAgent || "";
      return /iPad|iPhone|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
    }
    function touchPhone() {
      var ua = navigator.userAgent || "";
      return (navigator.maxTouchPoints || 0) > 0 && /Android|iPhone|iPad|iPod|Mobile/i.test(ua);
    }
    function bindPick(btn, input, asDir) {
      if (!btn || !input) return;
      var direct = !!(btn.contains && btn.contains(input));
      if (!asDir && touchPhone()) input.removeAttribute("accept");
      if (asDir && iosNoFolder()) {
        var blockFolder = function (e) {
          e.preventDefault();
          status.textContent = "这部手机的浏览器不能选文件夹，请用「文件」多选。";
        };
        (direct ? input : btn).addEventListener("click", blockFolder);
      } else if (direct) {
        input.webkitdirectory = true;
        try { input.setAttribute("webkitdirectory", ""); } catch (err) {}
        input.addEventListener("click", function (e) {
          if (!alive || fileBusy) {
            e.preventDefault();
            return;
          }
          pickerArmed = true;
        });
      } else {
        btn.addEventListener("click", function (e) {
          e.preventDefault();
          if (!alive || fileBusy) return;
          try {
            input.value = "";
            pickerArmed = true;
            input.click();
          } catch (err) {
            pickerArmed = false;
            status.textContent = "打不开系统选择框";
          }
        });
      }
      input.addEventListener("change", function () {
        pickerArmed = false;
        var live = input.files;
        var copy = [];
        if (live) {
          for (var i = 0; i < live.length; i++) copy.push(live[i]);
        }
        if (!copy.length) return;
        var folder = asDir;
        var fellBack = false;
        if (asDir && !copy[0].webkitRelativePath) {
          folder = false;
          fellBack = true;
        }
        enqueue(copy, folder);
        if (fellBack) {
          status.textContent = "这个浏览器不能选文件夹，已按普通文件加入 " + copy.length + " 个。";
        }
        try { input.value = ""; } catch (err) {}
      });
    }
    function harvestPicked() {
      if (!pickerArmed) return;
      var took = false;
      function take(input, asDir) {
        if (!input || !input.files || !input.files.length) return;
        var copy = [];
        for (var i = 0; i < input.files.length; i++) copy.push(input.files[i]);
        var folder = asDir;
        var fellBack = asDir && !copy[0].webkitRelativePath;
        if (fellBack) folder = false;
        enqueue(copy, folder);
        if (fellBack) status.textContent = "这个浏览器不能选文件夹，已按普通文件加入 " + copy.length + " 个。";
        try { input.value = ""; } catch (err) {}
        took = true;
      }
      take(inImg, false);
      take(inFile, false);
      take(inDir, true);
      if (took) pickerArmed = false;
    }
    bindPick(pickImg, inImg, false);
    bindPick(pickFile, inFile, false);
    bindPick(pickDir, inDir, true);

    function readEntryFile(entry) {
      return new Promise(function (resolve, reject) {
        entry.file(resolve, reject);
      });
    }
    function readDirEntries(reader) {
      return new Promise(function (resolve, reject) {
        var all = [];
        function next() {
          reader.readEntries(function (batch) {
            if (!batch.length) {
              resolve(all);
              return;
            }
            for (var i = 0; i < batch.length; i++) all.push(batch[i]);
            next();
          }, reject);
        }
        next();
      });
    }
    function walkEntry(entry, prefix, out) {
      if (!entry) return Promise.resolve();
      if (entry.isFile) {
        return readEntryFile(entry).then(function (file) {
          try {
            Object.defineProperty(file, "webkitRelativePath", {
              configurable: true,
              value: (prefix ? prefix + "/" : "") + entry.name
            });
          } catch (err) {}
          out.push({ file: file, asDir: !!prefix });
        });
      }
      if (!entry.isDirectory) return Promise.resolve();
      var path = (prefix ? prefix + "/" : "") + entry.name;
      return readDirEntries(entry.createReader()).then(function (kids) {
        var chain = Promise.resolve();
        kids.forEach(function (kid) {
          chain = chain.then(function () { return walkEntry(kid, path, out); });
        });
        return chain;
      });
    }
    function collectDrop(dt) {
      var out = [];
      var items = dt && dt.items;
      if (items && items.length && items[0].webkitGetAsEntry) {
        var jobs = [];
        for (var i = 0; i < items.length; i++) {
          var entry = items[i].webkitGetAsEntry && items[i].webkitGetAsEntry();
          if (entry) jobs.push(walkEntry(entry, "", out));
        }
        if (jobs.length) {
          return Promise.all(jobs).then(function () { return out; });
        }
      }
      var files = dt && dt.files;
      if (files && files.length) {
        for (var j = 0; j < files.length; j++) {
          if (files[j]) out.push({ file: files[j], asDir: false });
        }
      }
      return Promise.resolve(out);
    }
    function enqueueDropItems(items) {
      if (!items || !items.length) {
        status.textContent = "拖拽里没有可用文件";
        return;
      }
      var asDir = items.some(function (it) { return it.asDir; });
      var list = items.map(function (it) { return it.file; });
      // 目录拖入时保留相对路径；纯文件按非目录入队
      if (asDir) {
        var added = 0;
        for (var i = 0; i < items.length; i++) {
          var it = items[i];
          if (!it || !it.file) continue;
          pending.push({ file: it.file, asDir: true, clip: false });
          added++;
        }
        renderPending();
        status.textContent = added
          ? ("已加入待发 " + added + " 个。点「发送待发」开始传。")
          : "拖拽里没有可用文件";
        return;
      }
      enqueue(list, false);
    }
    function bindDrop(el) {
      if (!el) return;
      var depth = 0;
      function allow(e) {
        e.preventDefault();
        e.stopPropagation();
      }
      el.addEventListener("dragenter", function (e) {
        allow(e);
        if (!alive || fileBusy) return;
        depth++;
        el.classList.add("over");
      });
      el.addEventListener("dragover", function (e) {
        allow(e);
        if (e.dataTransfer) e.dataTransfer.dropEffect = "copy";
      });
      el.addEventListener("dragleave", function (e) {
        allow(e);
        depth = Math.max(0, depth - 1);
        if (depth === 0) el.classList.remove("over");
      });
      el.addEventListener("drop", function (e) {
        allow(e);
        depth = 0;
        el.classList.remove("over");
        if (!alive || fileBusy) return;
        status.textContent = "正在读取拖入的文件…";
        collectDrop(e.dataTransfer).then(function (items) {
          enqueueDropItems(items);
        }).catch(function () {
          status.textContent = "读取拖入文件失败";
        });
      });
    }
    // 整页拦截，避免浏览器直接打开文件。发给电脑这一整块都能放，包括输入框。
    ["dragenter", "dragover", "drop"].forEach(function (type) {
      document.addEventListener(type, function (e) {
        e.preventDefault();
      });
    });
    bindDrop(document.getElementById("sendCard"));

    var stopSendBtn = document.getElementById("stopSend");
    if (stopSendBtn) stopSendBtn.addEventListener("click", stopTransfer);
    if (sendPendingBtn) {
      sendPendingBtn.addEventListener("click", startPendingSend);
    }
    if (clearPendingBtn) {
      clearPendingBtn.addEventListener("click", function () {
        if (fileBusy) return;
        pending = [];
        renderPending();
        setProgress(false);
        status.textContent = "已清空待发";
      });
    }
    document.addEventListener("paste", function (e) {
      if (!alive || fileBusy) return;
      var items = e.clipboardData && e.clipboardData.items;
      if (!items) return;
      var found = [];
      for (var i = 0; i < items.length; i++) {
        if (items[i].kind === "file" && items[i].type.indexOf("image/") === 0) {
          var f = items[i].getAsFile();
          if (f) found.push(f);
        }
      }
      if (!found.length) return;
      e.preventDefault();
      enqueue(found, false);
    });
    renderPending();
  }

  var api = {
    INFO: INFO,
    sha256: sha256,
    hkdf: hkdf,
    sealWithNonce: sealWithNonce,
    open: open,
    selfTest: selfTest,
    bytesToHex: bytesToHex,
    hexToBytes: hexToBytes
  };
  if (typeof document !== "undefined") {
    loadFloatPrefs();
    var floatDot = document.getElementById("floatDot");
    var floatMain = document.getElementById("floatMain");
    var settings = document.getElementById("settings");
    var optFloat = document.getElementById("optFloat");
    var optDlPrompt = document.getElementById("optDlPrompt");
    var optZoom = document.getElementById("optZoom");
    var floatLock = document.getElementById("floatLock");
    if (optFloat) optFloat.checked = floatPrefs.show !== false;
    if (optDlPrompt) optDlPrompt.checked = !!floatPrefs.dlPrompt;
    applyZoomLock();
    function openSettings() { if (settings) settings.hidden = false; }
    function closeSettings() { if (settings) settings.hidden = true; }
    var openBtn = document.getElementById("openSettings");
    var closeBtn = document.getElementById("closeSettings");
    if (openBtn) openBtn.addEventListener("click", openSettings);
    if (closeBtn) closeBtn.addEventListener("click", closeSettings);
    if (settings) {
      settings.addEventListener("click", function (e) {
        if (e.target === settings) closeSettings();
      });
    }
    if (optFloat) {
      optFloat.addEventListener("change", function () {
        floatPrefs.show = !!optFloat.checked;
        saveFloatPrefs();
        paintFloat();
      });
    }
    if (optDlPrompt) {
      optDlPrompt.addEventListener("change", function () {
        floatPrefs.dlPrompt = !!optDlPrompt.checked;
        saveFloatPrefs();
      });
    }
    if (optZoom) {
      optZoom.addEventListener("change", function () {
        setZoomLock(!!optZoom.checked);
      });
    }
    function closeFloatMenu() {
      var menu = document.getElementById("floatMenu");
      if (menu) menu.hidden = true;
    }
    function toggleFloatMenu() {
      var menu = document.getElementById("floatMenu");
      var dock = document.getElementById("floatDock");
      if (!menu || !dock) return;
      if (!menu.hidden) {
        menu.hidden = true;
        return;
      }
      if (dock.getBoundingClientRect().top < 140) menu.classList.add("below");
      else menu.classList.remove("below");
      menu.hidden = false;
    }
    if (floatLock) {
      floatLock.addEventListener("click", function () {
        setZoomLock(!floatPrefs.lockZoom);
        closeFloatMenu();
      });
    }
    var floatSendBtn = document.getElementById("floatSend");
    if (floatSendBtn) floatSendBtn.addEventListener("click", closeFloatMenu);
    var floatDock = document.getElementById("floatDock") || floatDot;
    if (floatDock && floatMain) {
      var HOLD_MS = 450;
      var MOVE_PX = 10;
      var press = null;
      var holdTimer = null;
      function armSend() {
        if (!press || press.moved || press.held) return;
        press.held = true;
        floatMain.classList.remove("arming");
        var box = document.getElementById("box");
        if (!box || !String(box.value || "").length) {
          var status = document.getElementById("status");
          if (status) status.textContent = "先写点文字";
          return;
        }
        var btn = document.getElementById("floatSend");
        if (btn) btn.click();
      }
      floatMain.addEventListener("pointerdown", function (e) {
        if (e.button != null && e.button !== 0) return;
        if (e.preventDefault) e.preventDefault();
        var rect = floatDock.getBoundingClientRect();
        press = { x: e.clientX, y: e.clientY, left: rect.left, top: rect.top, moved: false, held: false };
        floatDragged = false;
        floatMain.classList.add("arming");
        if (floatMain.setPointerCapture) floatMain.setPointerCapture(e.pointerId);
        clearTimeout(holdTimer);
        holdTimer = setTimeout(armSend, HOLD_MS);
      });
      floatMain.addEventListener("pointermove", function (e) {
        if (!press) return;
        var dx = e.clientX - press.x;
        var dy = e.clientY - press.y;
        if (!press.moved && Math.abs(dx) + Math.abs(dy) < MOVE_PX) return;
        if (!press.moved) {
          press.moved = true;
          clearTimeout(holdTimer);
          floatMain.classList.remove("arming");
          closeFloatMenu();
          if (!press.held) floatDock.classList.add("dragging");
        }
        if (press.held) return;
        floatPrefs.x = press.left + dx;
        floatPrefs.y = press.top + dy;
        placeFloat();
      });
      function endPress(cancelled) {
        if (!press) return;
        clearTimeout(holdTimer);
        var moved = press.moved;
        var held = press.held;
        floatMain.classList.remove("arming");
        if (moved && !held) saveFloatPrefs();
        floatDragged = moved || held;
        press = null;
        floatDock.classList.remove("dragging");
        if (!cancelled && !moved && !held) toggleFloatMenu();
        paintFloat();
      }
      floatMain.addEventListener("pointerup", function () { endPress(false); });
      floatMain.addEventListener("pointercancel", function () { endPress(true); });
      floatMain.addEventListener("contextmenu", function (e) { e.preventDefault(); });
    }
    document.addEventListener("pointerdown", function (e) {
      var menu = document.getElementById("floatMenu");
      var dock = document.getElementById("floatDock");
      if (!menu || menu.hidden) return;
      if (dock && dock.contains(e.target)) return;
      menu.hidden = true;
    });
    window.addEventListener("scroll", paintFloat, { passive: true });
    window.addEventListener("resize", placeFloat);
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", placeFloat);
      window.visualViewport.addEventListener("scroll", placeFloat);
    }
    window.addEventListener("focusin", function () { setTimeout(placeFloat, 60); });
    window.addEventListener("focusout", function () { setTimeout(placeFloat, 60); });
    paintFloat();
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(placeFloat);
  }
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.TsWebBridge = api;
  if (typeof document === "undefined" && typeof importScripts === "function") {
    root.onmessage = function (e) {
      try {
        var d = e.data || {};
        var keyBytes = new Uint8Array(d.key);
        if (d.op === "open") {
          var plain = open(keyBytes, new Uint8Array(d.sealed));
          root.postMessage({ n: d.n, ok: 1, plain: plain }, [plain.buffer]);
          return;
        }
        var sealed = seal(keyBytes, new Uint8Array(d.plain));
        root.postMessage({ n: d.n, ok: 1, sealed: sealed }, [sealed.buffer]);
      } catch (err) {
        root.postMessage({ n: e.data && e.data.n, ok: 0 });
      }
    };
  } else if (typeof document !== "undefined") {
    boot();
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
