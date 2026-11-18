/* TextSend 网页桥。HTTP 不是安全上下文，不能用 crypto.subtle。
 * 密封格式与桌面 AesGcm 相同：nonce(12) || ciphertext || tag(16)。
 * 密钥：HKDF-SHA256(ikm=k, salt=会话 id 的 UTF-8, info="textsend-webbridge-v1", 32)。
 */
(function (root) {
  "use strict";

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

  function setLink(kind, text) {
    var link = document.getElementById("link");
    if (!link) return;
    link.className = "chip " + kind;
    link.textContent = text;
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
    var status = document.getElementById("status");
    var box = document.getElementById("box");
    var sendBtn = document.getElementById("send");
    var pickImg = document.getElementById("pickImg");
    var pickFile = document.getElementById("pickFile");
    var pickDir = document.getElementById("pickDir");
    var inImg = document.getElementById("inImg");
    var inFile = document.getElementById("inFile");
    var inDir = document.getElementById("inDir");
    var fileQueue = [];
    var fileBusy = false;
    var alive = true;
    function lockPicks(off) {
      [pickImg, pickFile, pickDir].forEach(function (b) {
        if (b) b.disabled = off;
      });
    }
    try {
      selfTest();
    } catch (e) {
      status.textContent = "页面加密自检失败，请重新从电脑打开。";
      setLink("dead", "页面不可用");
      sendBtn.disabled = true;
      box.disabled = true;
      lockPicks(true);
      return;
    }
    var params = new URLSearchParams(location.search);
    var sid = params.get("s") || "";
    var hash = location.hash.charAt(0) === "#" ? location.hash.slice(1) : location.hash;
    var keyB64 = new URLSearchParams(hash).get("k") || "";
    if (!sid || !keyB64) {
      status.textContent = "请用电脑上「兼容模式」的二维码打开，不要手打地址。";
      setLink("dead", "未连接");
      sendBtn.disabled = true;
      box.disabled = true;
      lockPicks(true);
      return;
    }
    var key;
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
    setLink("wait", "连接中");
    var dropped = false;
    function markDown(text) {
      dropped = true;
      setLink("down", text);
    }
    function markDead(text) {
      alive = false;
      dropped = true;
      fileQueue = [];
      setLink("dead", text);
      sendBtn.disabled = true;
      lockPicks(true);
    }
    var es = new EventSource("/api/events?s=" + encodeURIComponent(sid));
    var downTimer = null;
    es.onopen = function () {
      if (downTimer) {
        clearTimeout(downTimer);
        downTimer = null;
      }
      dropped = false;
      setLink("ok", "已连接");
    };
    es.addEventListener("msg", function (ev) {
      try {
        var plain = open(key, stdB64ToBytes(String(ev.data).trim()));
        var msg = JSON.parse(new TextDecoder().decode(plain));
        if (msg && msg.type === "text" && typeof msg.body === "string") addIncoming(msg.body, msg.ts);
        if (!dropped) setLink("ok", "已连接");
      } catch (e) {
        status.textContent = "有一条下行解不开。会话可能已换，请重新扫码。";
      }
    });
    es.addEventListener("bye", function () {
      es.close();
      markDead("会话已失效，请重新扫码");
    });
    es.onerror = function () {
      if (es.readyState === EventSource.CLOSED) {
        markDead("和电脑断开了");
        return;
      }
      if (downTimer) return;
      downTimer = setTimeout(function () {
        downTimer = null;
        if (es.readyState === EventSource.OPEN) return;
        markDown("和电脑断开了，正在重连");
        fetch("/api/meta?s=" + encodeURIComponent(sid), { cache: "no-store" }).then(function (r) {
          if (r.status === 401) {
            es.close();
            markDead("会话已失效，请重新扫码");
          }
        }).catch(function () {
          markDown("和电脑断开了，正在重连");
        });
      }, 700);
    };

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
      var sealed;
      try {
        sealed = seal(key, new TextEncoder().encode(payload));
      } catch (e) {
        sendBtn.disabled = false;
        status.textContent = "加密失败";
        return;
      }
      fetch("/api/send?s=" + encodeURIComponent(sid), {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: sealed
      }).then(function (res) {
        if (res.status === 204) {
          box.value = "";
          status.textContent = "已发到电脑";
          addSent(text, sentAt);
        } else if (res.status === 401) {
          status.textContent = "会话已失效，请重新扫码。";
        } else {
          status.textContent = "发送失败（" + res.status + "）";
        }
      }).catch(function () {
        status.textContent = "发送失败";
        markDown("和电脑断开了，正在重连");
      }).then(function () {
        sendBtn.disabled = false;
      });
    }

    sendBtn.addEventListener("click", send);
    box.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        send();
      }
    });

    var FILE_MAX = 32 * 1024 * 1024;
    function readFile(file) {
      return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () { resolve(new Uint8Array(reader.result)); };
        reader.onerror = function () { reject(new Error("读不到 " + (file.name || "文件"))); };
        reader.readAsArrayBuffer(file);
      });
    }
    function packFile(name, rel, mime, clip, bytes) {
      var head = new TextEncoder().encode(JSON.stringify({
        v: 1,
        type: "file",
        name: name || "file",
        rel: rel || "",
        mime: mime || "",
        clip: !!clip
      }));
      if (head.length > 8192) throw new Error("文件名太长");
      var out = new Uint8Array(2 + head.length + bytes.length);
      out[0] = (head.length >> 8) & 255;
      out[1] = head.length & 255;
      out.set(head, 2);
      out.set(bytes, 2 + head.length);
      return out;
    }
    function looksImage(file) {
      if (file.type && file.type.indexOf("image/") === 0) return true;
      return /\.(png|jpe?g|gif|bmp|webp|tiff?)$/i.test(file.name || "");
    }
    function uploadOne(job) {
      var file = job.file;
      var fallback = file.type === "image/jpeg" ? "粘贴.jpg"
        : file.type === "image/gif" ? "粘贴.gif"
        : file.type === "image/webp" ? "粘贴.webp"
        : (file.type && file.type.indexOf("image/") === 0 ? "粘贴.png" : "file");
      var label = file.webkitRelativePath || file.name || fallback;
      if (file.size > FILE_MAX) return Promise.reject(new Error(label + " 超过 32MB"));
      status.textContent = "正在加密 " + label;
      return readFile(file).then(function (bytes) {
        if (!alive) throw new Error("会话已失效，请重新扫码。");
        var plain = packFile(file.name || fallback, job.asDir ? (file.webkitRelativePath || label) : "", file.type || "", job.clip, bytes);
        var sealed = seal(key, plain);
        status.textContent = "正在发送 " + label;
        return fetch("/api/file?s=" + encodeURIComponent(sid), {
          method: "POST",
          headers: { "Content-Type": "application/octet-stream" },
          body: sealed
        }).then(function (res) {
          if (res.status === 204) {
            addSent(label, Date.now());
            return;
          }
          if (res.status === 401) throw new Error("会话已失效，请重新扫码。");
          if (res.status === 413) throw new Error(label + " 超过 32MB");
          throw new Error(label + " 发送失败（" + res.status + "）");
        });
      });
    }
    function pumpFiles() {
      if (fileBusy || !alive) return;
      var job = fileQueue.shift();
      if (!job) {
        lockPicks(false);
        return;
      }
      fileBusy = true;
      lockPicks(true);
      setTimeout(function () {
        uploadOne(job).then(function () {
          fileBusy = false;
          if (!fileQueue.length && alive) {
            lockPicks(false);
            status.textContent = "已发到电脑";
          }
          pumpFiles();
        }).catch(function (err) {
          fileQueue = [];
          fileBusy = false;
          if (alive) lockPicks(false);
          status.textContent = err && err.message ? err.message : "发送失败";
        });
      }, 30);
    }
    function enqueue(list, asDir) {
      if (!alive || !list || !list.length) return;
      var files = [];
      for (var i = 0; i < list.length; i++) files.push(list[i]);
      var singleImage = !asDir && files.length === 1 && looksImage(files[0]);
      for (var j = 0; j < files.length; j++) {
        fileQueue.push({ file: files[j], asDir: !!asDir, clip: singleImage });
      }
      pumpFiles();
    }
    function bindPick(btn, input, asDir) {
      if (!btn || !input) return;
      btn.addEventListener("click", function () { input.click(); });
      input.addEventListener("change", function () {
        enqueue(input.files, asDir);
        input.value = "";
      });
    }
    bindPick(pickImg, inImg, false);
    bindPick(pickFile, inFile, false);
    bindPick(pickDir, inDir, true);
    document.addEventListener("paste", function (e) {
      if (!alive) return;
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
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.TsWebBridge = api;
  if (typeof document !== "undefined") boot();
})(typeof globalThis !== "undefined" ? globalThis : this);
