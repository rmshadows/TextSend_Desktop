const CHARACTER = "UTF-8";

/**
 * 字符串转Byte数组 (UTF-8) 
 * https://segmentfault.com/a/1190000021311212
 * @param {*} str 字符串
 * @returns 
 */
function stringToBytes1(str) {
    let bytes = new Array();
    let len, c;
    len = str.length;
    for (let i = 0; i < len; i++) {
        c = str.charCodeAt(i);
        if (c >= 0x010000 && c <= 0x10FFFF) {
            bytes.push(((c >> 18) & 0x07) | 0xF0);
            bytes.push(((c >> 12) & 0x3F) | 0x80);
            bytes.push(((c >> 6) & 0x3F) | 0x80);
            bytes.push((c & 0x3F) | 0x80);
        } else if (c >= 0x000800 && c <= 0x00FFFF) {
            bytes.push(((c >> 12) & 0x0F) | 0xE0);
            bytes.push(((c >> 6) & 0x3F) | 0x80);
            bytes.push((c & 0x3F) | 0x80);
        } else if (c >= 0x000080 && c <= 0x0007FF) {
            bytes.push(((c >> 6) & 0x1F) | 0xC0);
            bytes.push((c & 0x3F) | 0x80);
        } else {
            bytes.push(c & 0xFF);
        }
    }
    return bytes;
}


/**
 * Byte数组 (UTF-8) 转字符串
 * https://www.cnblogs.com/haishikugua/p/12850341.html
 * @param {*} arr UTF字符串的Byte数组 
 * @returns 
 */
function bytesToString1(arr) {
    if (typeof arr === 'string') {
        return arr;
    }
    let str = ''
    let _arr = arr;
    for (let i = 0; i < _arr.length; i++) {
        let one = _arr[i].toString(2),
            v = one.match(/^1+?(?=0)/);
        if (v && one.length == 8) {
            let bytesLength = v[0].length;
            let store = _arr[i].toString(2).slice(7 - bytesLength);
            for (let st = 1; st < bytesLength; st++) {
                store += _arr[st + i].toString(2).slice(2);
            }
            str += String.fromCharCode(parseInt(store, 2));
            i += bytesLength - 1;
        } else {
            str += String.fromCharCode(_arr[i]);
        }
    }
    return str;
}


/**
 * 将字符串格式化为UTF8编码的字节
 * https://juejin.cn/post/6859553038420901895
 * @param {*} str 
 * @param {*} isGetBytes 
 * @returns 
 */
function stringToBytes(str, isGetBytes = true) {
    let back = [];
    let byteSize = 0;
    for (let i = 0; i < str.length; i++) {
        let code = str.codePointAt(i);
        if (0x00 <= code && code <= 0x7f) {
            byteSize += 1;
            back.push(code);
        } else if (0x80 <= code && code <= 0x7ff) {
            byteSize += 2;
            back.push((192 | (31 & (code >> 6))));
            back.push((128 | (63 & code)))
        } else if ((0x800 <= code && code <= 0xd7ff)
            || (0xe000 <= code && code <= 0xffff)) {
            byteSize += 3;
            back.push((224 | (15 & (code >> 12))));
            back.push((128 | (63 & (code >> 6))));
            back.push((128 | (63 & code)))
        } else if ((0x10000 <= code && code <= 0x10ffff)) {
            byteSize += 4;
            back.push((240 | (7 & (code >> 18))));
            back.push((128 | (63 & (code >> 12))));
            back.push((128 | (63 & (code >> 6))));
            back.push((128 | (63 & (code))));
        }
    }
    for (let i = 0; i < back.length; i++) {
        back[i] &= 0xff;
    }
    if (isGetBytes) {
        return back
    }
    if (byteSize <= 0xff) {
        return [0, byteSize].concat(back);
    } else {
        return [byteSize >> 8, byteSize & 0xff].concat(back);
    }
}

/**
 * 读取UTF8编码的字节，并专为Unicode的字符串
 * https://juejin.cn/post/6859553038420901895
 * @param {*} arr 
 * @returns 
 */
function bytesToString(arr) {
    if (typeof arr === 'string') {
        return arr;
    }
    let UTF = '';
    //   let _arr = this.init(arr);
    let _arr = arr;
    for (let i = 0; i < _arr.length; i++) {
        let one = _arr[i].toString(2);
        let v = one.match(/^1+?(?=0)/);
        if (v && one.length == 8) {
            let bytesLength = v[0].length;
            let store = _arr[i].toString(2).slice(7 - bytesLength);
            for (let st = 1; st < bytesLength; st++) {
                store += _arr[st + i].toString(2).slice(2)
            }
            UTF += String.fromCharCode(parseInt(store, 2));
            i += bytesLength - 1
        } else {
            UTF += String.fromCharCode(_arr[i])
        }
    }
    return UTF;
}


/**
 * 密钥长度补全
 * 把所给的String密钥转为PWD_SIZE长度 的 Byte数组并填充 0
 * @param {*} stringKey 
 * @param {*} length 
 * @returns 
 */
function keyPadding(stringKey, length) {
    let result = [];
    if (stringKey != null) {
        let pwd_bytes = []; //一个中文3位长度，一数字1位
        try {
            pwd_bytes = stringToBytes(stringKey);
            if (pwd_bytes.length > length) {
                throw Error("字符串长度超出");
            }
            result = pwd_bytes.slice();
        } catch (e) {
            console.log("keyPadding:  " + e);
        }
        if (stringKey.length < length) {
            for (let i = result.length; i < length; i++) {
                result.push(0);
            }
        } else {
            result = pwd_bytes;
        }
    }
    return result;
}


/**
 * hex=>bytes[]
 * @param {*} hex 
 * @returns 
 */
function hex2bytes(hex) {
    let pos = 0, len = hex.length;
    if (len % 2 != 0) {
        return null
    }
    len / 2;
    let bytes = new Array();
    for (let i = 0; i < len; i++) {
        let s = hex.substr(pos, 2);
        let v = parseInt(s, 16);
        bytes.push(v);
        pos += 2;
    }
    return bytes
}

/**
 * bytes[]=>hex
 * @param {*} bytes 
 * @returns 
 */
function bytes2hex(bytes) {
    let hex = "", len = bytes.length;
    for (let i = 0; i < len; i++) {
        let tmp, num = bytes[i];
        if (num < 0) {
            tmp = (255 + num + 1).toString(16);
        } else {
            tmp = num.toString(16);
        }
        if (tmp.length == 1) {
            return "0" + tmp;
        }
        hex += tmp;
    }
    return hex.toUpperCase();
}

/**
 * arrayBufferArray
 * @param {*} arrayBuffer 
 * @returns 
 */
function arrayBufferToArray1(arrayBuffer) {
    return Array.prototype.slice.call(new Uint8Array(arrayBuffer));
}

/**
 * arrayArrayBuffer
 * https://zhuanlan.zhihu.com/p/475830221
 * @param {*} arr 
 * @returns 
 */
function arrayToArrayBuffer(arr) {
    let arrayBuffer = new Uint8Array(arr).buffer;
    return arrayBuffer;
}

module.exports = {
    arrayToArrayBuffer,
    arrayBufferToArray1,
    bytes2hex,
    hex2bytes,
    keyPadding,
    bytesToString,
    stringToBytes,
    bytesToString1,
    stringToBytes1,
    CHARACTER,
}





