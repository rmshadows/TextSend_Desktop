let crypto;
try {
    crypto = require('node:crypto');
} catch (err) {
    console.error('crypto support is disabled!');
}
const tools = require("./aes_tools.cjs")


/**
 * 返回加密器
 * @param {*} SecuritykeyStr 
 * @param {*} initVectorStr 
 * @param {*} pwdLength 16/24/32 = 128/192/256
 * @param {*} algorithm 
 * @returns 
 */
function cbcCipher(SecuritykeyStr, initVectorStr, pwdLength = 16){
    try {
        // 转字符串为byte[] padding
        let bSecuritykeyStr = tools.keyPadding(SecuritykeyStr, pwdLength);
        let binitVectorStr = tools.keyPadding(initVectorStr, 16);
        // 转array到arraybuffer
        let Securitykey = tools.arrayToArrayBuffer(bSecuritykeyStr);
        let initVector = tools.arrayToArrayBuffer(binitVectorStr);
        // console.log(Securitykey);
        // crypto.randomBytes(16);
        let algorithm = ""
        if (pwdLength == 16) {
            algorithm = "aes-128-cbc"
        } else if (pwdLength == 24) {
            algorithm = "aes-192-cbc"
        } else if (pwdLength == 32) {
            algorithm = "aes-256-cbc"
        }
        return crypto.createCipheriv(algorithm, Securitykey, initVector);
    } catch (error) {
        console.log("cbcCipher创建失败：" + error);
    }
    return undefined
}

/**
 * 返回解密器
 * @param {*} SecuritykeyStr 
 * @param {*} initVectorStr 
 * @param {*} pwdLength 
 * @returns 
 */
function cbcDecipher(SecuritykeyStr, initVectorStr, pwdLength = 16){
    try {
        // 转字符串为byte[] padding
        let bSecuritykeyStr = tools.keyPadding(SecuritykeyStr, pwdLength);
        let binitVectorStr = tools.keyPadding(initVectorStr, 16);
        // 转array到arraybuffer
        let Securitykey = tools.arrayToArrayBuffer(bSecuritykeyStr);
        let initVector = tools.arrayToArrayBuffer(binitVectorStr);
        // console.log(Securitykey);
        // crypto.randomBytes(16);
        let algorithm = ""
        if (pwdLength == 16) {
            algorithm = "aes-128-cbc"
        } else if (pwdLength == 24) {
            algorithm = "aes-192-cbc"
        } else if (pwdLength == 32) {
            algorithm = "aes-256-cbc"
        }
        return crypto.createDecipheriv(algorithm, Securitykey, initVector);
    } catch (error) {
        console.log("cbcDecipher创建失败：" + error);
    }
    return undefined
}


/**
 * 加密信息
 * @param {*} cipher 加密器
 * @param {*} message 要加密的信息
 * @returns 
 */
function encrypt(cipher, message){
    try {
        let encryptedData = cipher.update(message, "utf-8", "hex");
        encryptedData += cipher.final("hex");
        encryptedData = encryptedData.toUpperCase();
        // console.log("Encrypted message: " + encryptedData);
        return encryptedData;
    } catch (error) {
        console.log("CBC加密失败：" + error);
        return undefined
    }
}


/**
 * 解密的方法
 * @param {*} decipher 
 * @param {*} hexmessage 
 * @returns 
 */
function decrypt(decipher, hexmessage){
    try {
        // the decipher function
        let decryptedData = decipher.update(hexmessage.toLowerCase(), "hex", "utf-8");
        decryptedData += decipher.final("utf8");
        // console.log("Decrypted message: " + decryptedData);
        return decryptedData;
    } catch (error) {
        console.log("CBC解密失败：" + error);
        return undefined
    }
}

/**
 * 临时加密的方法
 * @param {*} message 
 * @param {*} key 
 * @param {*} iv 
 * @param {*} length 
 * @returns 
 */
function tEncrypt(message, key, iv, length = 16){
    try {
        let cipher = cbcCipher(key, iv, length);
        let encryptedData = cipher.update(message, "utf-8", "hex");
        encryptedData += cipher.final("hex");
        encryptedData = encryptedData.toUpperCase();
        // console.log("CBC Encrypted message: " + encryptedData);
        return encryptedData;
    } catch (error) {
        console.log("CBC临时加密失败：" + error);
        return undefined
    }
}


/**
 * 临时解密的方法
 * @param {*} decipher 
 * @param {*} hexmessage 
 * @returns 
 */
function tDecrypt(hexmessage, key, iv, length = 16){
    try {
        // the decipher function
        let decipher = cbcDecipher(key, iv, length);
        let decryptedData = decipher.update(hexmessage.toLowerCase(), "hex", "utf-8");
        decryptedData += decipher.final("utf8");
        // console.log("Decrypted message: " + decryptedData);
        return decryptedData;
    } catch (error) {
        console.log("CBC临时解密失败：" + error);
        return undefined
    }
}

module.exports = {
    tDecrypt,
    tEncrypt,
    encrypt,
    decrypt,
    cbcDecipher,
    cbcCipher,
}