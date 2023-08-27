"use strict";
// 加密用的Token
const AES_TOKEN = "cn.rmshadows.TS_TOKEN";
// 服务器消息自带的ID
const SERVER_ID = -200;
// 服务器成功接收的反馈信息
const FB_MSG = "cn.rmshadows.TextSend.ServerStatusFeedback";
// 单个Msg拆分的长度
const MSG_LEN = 1000;
// 服务器列表
let SERVER_POOL = [];
// Socket连接列表（服务端+客户端） [socket, clientMode]
let SOCKET_POOL = {};
// 是否成功启动（客户端也能使用） -1初始化 0正在启动 1启动成功 2启动失败
let startStatus = -1;
// 是否清空消息（对方收到消息会反馈，收到反馈就删除） 
let clearText = false;

module.exports = {
    AES_TOKEN,
    SERVER_ID,
    FB_MSG,
    MSG_LEN,
    SERVER_POOL,
    SOCKET_POOL,
    startStatus,
    clearText,
}

