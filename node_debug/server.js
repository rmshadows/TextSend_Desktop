"use strict";
const net = require('net');
const { Message } = require('./message');
const profile = require("./profile");
const crypto = require("./crypto");
const Hashcode = require("./hashcode");
const { exit } = require('process');

/**
参考：
http://zhenhua-lee.github.io/node/socket.html
https://github.com/qufei1993/Nodejs-Roadmap/blob/master/docs/nodejs/net.md
 */
/**
 * 启动socket监听服务 只允许一个
 * @param {String} port 端口号 
 * @param {Number} maxConnections 最大连接数量
 * @param {boolean} overwrite 是否覆盖原有
 */
function createTsServer(port, maxConnections = 1, overwrite = false) {
    // IPC通信，未使用
    // const ubWindow = utools.createBrowserWindow('ui/index.html', {
    //     show: false,
    //     title: '测试窗口',
    //     webPreferences: {
    //         preload: 'tspreload.js'
    //     }
    //   }, () => {
    //     // 向子窗口传递数据
    //     const { ipcRenderer } = require('electron');
    //     let a = ubWindow.webContents.send("ping", 1);
    //     console.log("IPC Main: 发送Ping");
    //   })
    profile.startStatus = 0;
    let server = undefined;
    let clientId = undefined;
    // 服务端读取长消息的状态 -1 未读 0:数据未分片，直接读取 1:分片读取模式 2:分片读取到尾巴
    let serverReadStat = -1;
    // 分片数据的总和
    let json_parts = "";
    if (profile.SERVER_POOL.length == 0) {
        server = net.createServer();
    } else {
        if (overwrite) {
            closeAllServers();
            server = net.createServer();
        } else {
            console.log("createServer: 已有服务，不再启动");
        }
    }
    // 最大连接数
    server.maxConnections = maxConnections;
    // 创建好开始配置
    // 客户端链接
    server.on('connection', (socket) => {
        // 设置编码
        socket.setEncoding("utf-8");
        // socket.pipe(process.stdout);
        let remoteIP = socket.address().address;
        // hash即客户端ID
        let th = {
            "Socket": socket,
            "Time": new Date().getTime()
        };
        clientId = Hashcode.hashCodeObject(th);
        // let clientId = system.hashCode(String(new Date().getTime()));
        // console.log(clientId);
        console.log("<- client-profile.SOCKET_POOL   :" + remoteIP + "(" + clientId + ")");
        // 将id发给客户端(服务端发送ID到客户端，客户端发送支持的传输模式到服务端，由服务端决定使用什么模式)
        // node端使用JSON传输，Java直接传输类
        // [ '-200', '', '3566633025' ]
        let sendIdData = new Message(undefined, profile.MSG_LEN, profile.SERVER_ID, clientId).getJSON();
        console.log("服务端分配ID：" + sendIdData);
        socket.write(sendIdData);
        // 是否成功设置客户端模式
        let setClientMode = true;
        // 客户端确认ID 及返回支持的模式 确认格式：id(id:{random}):data(空):notes(SUPPORT-{$客户端模式☯☯{random})
        // 注意，随机数在加密解密时已经去除
        let clientConfirmId = false;

        // 设置客户端超时10秒（除非连接成功）
        socket.setTimeout(10000);

        // 客户端信息 data写在connection中
        socket.on('data', (data) => {
            // 首先使用JSON
            // 分配ID -> 客户端返回接收到ID的确认(包含模式) -> 没有确认就断开连接 -> 确认就告知客户端模式后继续
            if (setClientMode) {
                // id data notes
                data = crypto.decryptJSON(data);
                // 先识别客户端头部
                if (data != undefined && Number(data[0]) == clientId) {
                    try {
                        let tsp = data[2].split("-"); // 分隔成 CONFIRM:{$客户端支持的模式}
                        if (tsp[0] == "SUPPORT") {
                            // 客户端模式：0: java class 1:JSON
                            let clientMode = selectClientMode(tsp[1]);
                            if (clientMode != undefined) {
                                // 添加连接
                                profile.SOCKET_POOL[clientId] = [socket, clientMode];
                                console.log("客户端模式：" + clientMode);
                                // 打印当前状态 preload
                                // window.getConnectionStat();
                                // 告知客户端模式选择
                                socket.write(new Message(undefined, profile.MSG_LEN, profile.SERVER_ID, "CONFIRM-" + clientMode).getJSON());
                                clientConfirmId = true;
                                // 如果成功设置模式
                                setClientMode = false;
                            } else {
                                console.log("客户端似乎不支持JSON传输模式，断开...");
                            }
                        }
                    } catch (error) {
                        console.log("客户端ID确认失败或者客户端模式获取失败： " + error);
                    }
                    // 配置不成功就断开
                    if (!clientConfirmId) {
                        socket.end();
                        socket.destroy();
                    } else {
                        // 超时：1小时
                        socket.setTimeout(3600000);
                    }
                } else {
                    console.log("未收到正确的客户端模式和确认请求，等待超时后将断开。");
                }
            } else {
                // 正常读取JSON (长文本JSON会分多次传输，需要读取到罪末尾的notes方为结束，可以解密)
                // 长JSON读取流程：读取ID，没有ID就丢弃不读了 / 有ID就一直读取到notes
                console.log("Received: " + data);
                // 读取到末尾
                if (data.indexOf("}") != -1) {
                    if (serverReadStat != 1) {
                        // 说明没有分片
                        serverReadStat = 0;
                    } else {
                        // 分片模式末尾
                        serverReadStat = 2;
                    }
                } else {
                    // 没有读到末尾 但读取到id 进入分片模式
                    if (data.indexOf("{\"id\":") != -1) {
                        // 进入读取状态
                        serverReadStat = 1;
                    }
                }
                // 无分片模式
                if (serverReadStat == 0) {
                    json_parts = data;
                } else {
                    // 分片模式
                    json_parts += data;
                }
                // 解密
                if (serverReadStat == 0 || serverReadStat == 2) {
                    // 恢复
                    serverReadStat = -1;
                    // 这里json_part => data !!
                    data = json_parts;
                    json_parts = "";
                    console.log("解密前：" + data);
                    data = crypto.decryptJSON(data);
                    if (data != undefined && Number(data[0]) == clientId) {
                        if (data[2] == profile.FB_MSG) {
                            // 设置清空文本框
                            profile.clearText = true;
                        } else {
                            console.log("解密后：" + data);
                            // 直接粘贴
                            // utools.hideMainWindowPasteText(data);
                            // 使用utools API CTRL + V
                            // utools.hideMainWindowTypeString(data[1])
                            // 收到消息 放入剪贴板 
                            // utools.copyText(data[1]);
                            // 发送反馈(反馈写在notes)
                            serverFeedback();
                        }
                    } else {
                        console.log("Server drop:" + data);
                    }
                }
            }
        });

        socket.on('end', () => {
            // 断开复原
            profile.clearText = false;
            // 过滤断开的
            delete profile.SOCKET_POOL[clientId];
            console.log('-> client-disprofile.SOCKET_POOL');
        });

        socket.on('timeout', () => {
            console.log(remoteIP + ' timeout');
            socket.end();
            socket.destroy();
        });

        socket.on('error', (err) => {
            console.log("Socket error: " + err);
        });

    });

    server.on('error', (err) => {
        console.log("Server error: " + err);
    });

    // 会在所有连接断开后执行
    server.on('close', () => {
        // 断开复原
        profile.clearText = false;
        console.log('服务器已关闭: SERVER SHUTDOWN');
    })

    // 超出连接数量限制
    server.on('drop', (data) => {
        console.log('服务器拒绝连接: ' + JSON.stringify(data));
    });

    // 启动监听
    server.listen(port, () => {
        console.log(`server is on ${JSON.stringify(server.address())}`);
        console.log(`服务已开启在 ${port}`);
        // 设置成功启动
        profile.startStatus = 1;
    });
    // 错误
    server.on('error', (e) => {
        console.log(e);
        profile.startStatus = 2;
        // 出现错误才会从列表中移除
        closeAllServers();
        // if (e.code === 'EADDRINUSE') {
        //     console.error('Address in use, retrying...');
        // } else {
        //     console.log(e);
        // }
    });
    // 添加到服务器列表 (不管是否成功启动，先添加)
    profile.SERVER_POOL.push(server);
}

/**
 * 服务端反馈
 */
function serverFeedback() {
    for (let key in profile.SOCKET_POOL) {
        try {
            // 因为NODE端只能发送JSON所以不用考虑参数2
            const el = profile.SOCKET_POOL[key][0];
            let toSend = new Message(undefined,
                profile.MSG_LEN,
                profile.SERVER_ID,
                profile.FB_MSG).getJSON();
            // console.log(toSend);
            el.write(toSend);
        } catch (error) {
            console.log("server feedback: " + error);
        }
    }
}

/**
 * 发送
 * @param {String} msgString 
 */
function ssend(msgString) {
    // 会发送给所有连接的客户端
    for (let key in profile.SOCKET_POOL) {
        try {
            // 因为NODE端只能发送JSON所以不用考虑参数2
            const el = profile.SOCKET_POOL[key][0];
            let toSend = new Message(msgString, profile.MSG_LEN, profile.SERVER_ID, undefined).getJSON();
            console.log("Server send message(" + key + "): " + msgString + " => " + toSend);
            el.write(toSend);
        } catch (error) {
            console.log("serverSend: " + error);
        }
    }
}

/**
 * 关闭所有服务
 */
function closeAllServers() {
    let s = true;
    // 首先得关闭所有socket
    closeAllSockets();
    // 再关闭server
    for (let i = 0; i < profile.SERVER_POOL.length; i++) {
        try {
            const el = profile.SERVER_POOL[i];
            el.close();
        } catch (error) {
            console.log("关闭Server出错: " + error);
            s = false;
        }
    }
    // 没出错再清零
    if (s) {
        profile.SERVER_POOL = [];
    }
}

/**
 * 关闭所有socket
 */
function closeAllSockets() {
    let s = true;
    for (let key in profile.SOCKET_POOL) {
        // console.log("key: " + key + " ,value: " + dic[key]);
        try {
            const el = profile.SOCKET_POOL[key][0];
            console.log("Closing socket: " + key);
            el.end();
            el.destroy();
        } catch (error) {
            console.log("关闭Socket出错: " + error);
            s = false;
        }
    }
    if (s) {
        profile.SOCKET_POOL = [];
    }
}


/**
 * 返回首选的传输方式
 * @param {*} supportMode {supportMode: [1]} 客户端提供的支持的模式
 */
function selectClientMode(supportMode) {
    // 查看客户端是否支持JSON模式
    let json = JSON.parse(supportMode);
    let sm = json['supportMode']
    for (const i in sm) {
        sm[i] = Number(sm[i]);
    }
    if (sm.indexOf(1) == -1) {
        // 不支持JSON模式的返回undefined
        return undefined
    } else {
        // 因为Node端仅支持JSON传输，所以直接返回1
        return 1;
    }
}


module.exports = {
    createTsServer,
    closeAllServers,
    closeAllSockets,
    ssend,
}

