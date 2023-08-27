"use strict";

const os = require("os");
const L = require('list');

/**
 * 获取IP
 * @param {Number} type 类型0:全部 1:ip4 2:ip6
 * @returns 
 */
function getIpAddr(type = 0) {
    // 获取本机网络信息
    let iaddr = L.empty();
    const nfs = os.networkInterfaces();
    // console.log(nfs);
    // 查找 IPv4 地址
    for (const networkname in nfs) {
        // 网络名称
        // console.log(networkname);
        for (const v4v6 of nfs[networkname]) {
            // 打印IPv4 IPv6网络信息
            // console.log(v4v6);
            if (type == 1) {
                if (v4v6.family === 'IPv4') {
                    iaddr = L.append(v4v6.address, iaddr);
                    break
                }
            }
            else if (type == 2) {
                if (v4v6.family === 'IPv6') {
                    iaddr = L.append(v4v6.address, iaddr);
                    break
                }
            } else {
                iaddr = L.append(v4v6.address, iaddr);
            }
        }
    }
    // prlst(L.toArray(iaddr));
    return L.toArray(iaddr);
}

/**
 * 生成从minNum到maxNum的随机数
 * @param {Number} minNum 
 * @param {Number} maxNum 
 * @returns 
 */
function randomNum(minNum, maxNum) {
    switch (arguments.length) {
        case 1:
            return parseInt(Math.random() * minNum + 1, 10);
            break;
        case 2:
            return parseInt(Math.random() * (maxNum - minNum + 1) + minNum, 10);
            break;
        default:
            return 0;
            break;
    }
}


/**
 * 定时器 + Promise 实现 sleep
 * https://cloud.tencent.com/developer/article/1802782
// async await 的方式
async function test() {
  console.log(1);
  await promiseSleep(3000);
  console.log(2);
}

// Promise 的链式调用方式
async function test() {
  console.log(1);
  promiseSleep(3000).then(() => {
    console.log(2);
  });
}
 * @param {*} ms 
 * @returns 
 */
const promiseSleep = (ms) => {
    new Promise(resolve => setTimeout(resolve, ms));
}



/**
 * 真正的阻塞事件循环，阻塞线程直到超时，不要在主线程上使用 
 * https://cloud.tencent.com/developer/article/1802782
 * @param {Number} ms delay
 * @returns {String} ok|not-equal|timed-out
 */
function realSleep(ms) {
    const valid = ms > 0 && ms < Infinity;
    if (valid === false) {
        if (typeof ms !== 'number' && typeof ms !== 'bigint') {
            throw TypeError('ms must be a number');
        }
        throw RangeError('ms must be a number that is greater than 0 but less than Infinity');
    }
    return Atomics.wait(int32, 0, 0, Number(ms))
}

/**
 * 时间戳
 * @returns 
 */
function getTimestamp() {
    return Number(new Date().getTime());
}


module.exports = {
    getIpAddr,
    randomNum,
    getTimestamp,
}