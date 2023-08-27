"use strict";
const QR = require("./mqrcode/mqrcode.cjs");
const path = require("path");
const fs = require('fs');
const system = require("./system");

/**
 * 保存二维码到。。。
 * @param {*} ip 
 * @param {*} port 
 * @param {*} saveto 文件夹路径
 * @returns 
 */
function generateQR(ip, port, saveto) {
    // let p = "../../ui/assets/qrcode.png";
    let tsm = system.getTimestamp();
    let p = "tsqrcode-" + tsm + ".png";
    let fp = path.join(saveto, p)
    try {
        let content = String(ip) + ":" + String(port);
        console.log("qrimg.js:generateQR: " + content + " => " + fp);
        QR.createQRSync(content, fp, 512, "png");
        return fp;
    } catch (error) {
        console.log(error);
        return "../assets/favicon.png";
    }
}


module.exports = {
    generateQR,
}

