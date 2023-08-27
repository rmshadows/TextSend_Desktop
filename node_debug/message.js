const L = require('list');
const AES_Util = require("./crypto");
const { randomNum } = require('./system');

/**
 * 加密的Msg类 解密请在App中实现，此类不包含解密的任何功能
 */
class Message {
    constructor(stringText, length, id, notes) {
        // 文字
        this.stringText = stringText;
        // id
        this.id = id;
        // 留言
        this.notes = notes;
        if (stringText == "") {
            this.stringText == undefined;
        }
        if (id == "") {
            this.id = undefined;
        }
        if (notes == "") {
            this.notes = undefined;
        }
        // 加密的数据
        this.encrypt_data = L.empty();
        // 要传输的JSON
        /**
         * 格式：{id, data, notes}
         */
        this.json = {};
        this.setId(id);
        if (notes != undefined) {
            this.setNotes(notes);
        }
        // 处理文字数据
        if (stringText != undefined) {
            // 需要截取的长度
            let r_len = length; // 10
            let t_len = stringText.length; // 15
            let start = 0;
            let end = 0;
            // 000 000 000 0 3 10 0,3 3,6 6,9
            while (t_len > r_len) { // 15 > 10
                end += r_len;
                this.addData(this.encryptData(stringText.substring(start, end)));
                start += r_len;
                t_len -= r_len;
            }
            let e = stringText.substring(start);
            if (e != "") {
                this.addData(this.encryptData(e));
            }
        }
        // 处理ID和note (加随机数，冒号后面)
        let id2e = String(this.getId() + "☯☯" + randomNum(0, 5000));
        let notes2e = String(this.getNotes() + "☯☯" + randomNum(0, 5000));
        this.json['id'] = this.encryptData(id2e);
        this.json['data'] = this.getDataArray();
        this.json['notes'] = this.encryptData(notes2e);
        this.json = JSON.stringify(this.json);
        let cj = {}
        cj['id'] = this.id;
        cj['data'] = this.stringText == undefined ? "" : this.stringText;
        cj['notes'] = this.notes == undefined ? "" : this.notes;
        console.log("  ==>>  Clear JSON: " + JSON.stringify(cj));
        console.log("  <<==  Generate JSON: " + this.json);
    }

    // 返回用于传输的JSON
    getJSON() {
        return this.json;
    }

    // 加密(解密功能定义在接受端)
    encryptData(msg) {
        return AES_Util.encrypt(msg);
    }

    // 打印 : foreach log无效
    printData() {
        console.log(this.getData());
    }

    getNotes() {
        return this.notes;
    }
    setNotes(notes) {
        this.notes = notes;
    }
    getData() {
        return this.encrypt_data;
    }
    getDataArray() {
        return L.toArray(this.encrypt_data);
    }
    addData(string) {
        this.encrypt_data = L.append(string, this.encrypt_data);
    }
    getId() {
        return this.id;
    }
    setId(id) {
        this.id = String(id);
    }
}

// let msg = new Message("12345678901234567890123", 10, 2, undefined);
// msg.printData();
// console.log(msg.getDataArray());

module.exports = {
    Message,
}
