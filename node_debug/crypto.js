"use strict";
const maes = require("./maes/maes.cjs");
const profile = require("./profile");

const IV = "";
const KEY = profile.AES_TOKEN;

// 导出加密器
const Cipher = new maes.cfbCipher(KEY, IV, 32);

// 消息加密
function encrypt(msg) {
    let emsg = Cipher.encrypt(msg);
    // console.log("加密：" + emsg);
    return emsg;
}

// 消息解密
function decrypt(msg) {
    let dmsg = Cipher.decrypt(msg);
    // console.log("解密：" + dmsg);
    return dmsg;
}

/**
 * 解密JSON
 * @param {*} jsonstr 
 * @returns 
 */
function decryptJSON(jsonstr) {
    try {
        // {id, data, notes}
        let json = JSON.parse(jsonstr);
        let id = decrypt(json["id"]).split("☯☯")[0];
        let data = "";
        for (let i = 0; i < json["data"].length; i++) {
            const el = json["data"][i];
            data += decrypt(el);
        }
        let notes = decrypt(json["notes"]).split("☯☯")[0];
        return [id, data, notes]
    } catch (error) {
        console.log("解密失败( JSON => " + jsonstr + " <= JSON ): " + error);
        return undefined;
    }
}


module.exports = {
    Cipher,
    encrypt,
    decrypt,
    decryptJSON,
}
