// https://github.com/m3talstorm/hashcode/tree/master

// Used to check objects for own properties
const hasOwnProperty = Object.prototype.hasOwnProperty

// Hashes a string
const hash = (string) => {
    let hash = 0
    string = string.toString()
    for (let i = 0; i < string.length; i++) {
        hash = (((hash << 5) - hash) + string.charCodeAt(i)) & 0xFFFFFFFF
    }
    return hash
}

// Deep hashes an object
const object = (obj) => {
    if (typeof obj.getTime == 'function') {
        return obj.getTime()
    }
    let result = 0
    for (let property in obj) {
        // 下面这一句可能会陷入死循环 Uncaught RangeError: Maximum call stack size exceeded
        if (hasOwnProperty.call(obj, property)) {
            result += hash(property + hashCodeObject(obj[property]))
        }
    }
    return result
}

const hashCodeObject = (value) => {
    const type = value == undefined ? undefined : typeof value
    // Does a type check on the passed in value and calls the appropriate hash method
    return MAPPER[type] ? MAPPER[type](value) + hash(type) : 0
}

const MAPPER =
{
    string: hash,
    number: hash,
    boolean: hash,
    object: object
    // functions are excluded because they are not representative of the state of an object
    // types 'undefined' or 'null' will have a hash of 0
}


/**
 * https://stackoverflow.com/questions/194846/is-there-hash-code-function-accepting-any-object-type
 * @param {*} string 
 * @returns 
 */
function hashCodeString(string) {
    var hash = 0;
    for (var i = 0; i < string.length; i++) {
        var code = string.charCodeAt(i);
        hash = ((hash << 5) - hash) + code;
        hash = hash & hash; // Convert to 32bit integer
    }
    return Number(hash);
}


module.exports = {
    hashCodeObject,
    hashCodeString,
}