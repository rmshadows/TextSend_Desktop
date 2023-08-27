/**
 * 二维码模块
 * 
<!-- index.html -->
<html>
  <body>
    <canvas id="canvas"></canvas>
    <script src="bundle.js"></script>
  </body>
</html>

// index.js -> bundle.js
var QRCode = require('qrcode')
var canvas = document.getElementById('canvas')

QRCode.toCanvas(canvas, 'sample text', function (error) {
  if (error) console.error(error)
  console.log('success!');
})


 */
const QRCode = require('qrcode');
const jsQR = require("jsqr");
const jpeg = require('jpeg-js');
const PNG = require("pngjs");
const fs = require('fs');

// 依赖msystem
// const * as ms =require("../msystem/msystem.mjs";
const path = require('path');

// 二维码生成
/**
 * 内部函数，生成二维码 With async/await
 * @param {*} text 
 * @param {*} width 
 * @param {*} type 
 */
async function asyncGenerateQR(text, width, type) {
  try {
    let data = await QRCode.toDataURL(text, { type: type, width: width });
    console.log(data)
  } catch (err) {
    console.error(err)
  }
}

/**
 * 生成二维码图像并保存
 * createQR("123", "1.svg", 1000);
 * @param {*} text 
 * @param {*} savepath 
 * @param {*} width 
 * @param {*} type 文件类型 svg png txt
 */
function createQRSync(text, savepath, width = "500", type = "png") {
  QRCode.toFile(savepath, text, { type: type, width: width });
}

/**
 * With promises 返回Base64图片
 * @param {*} text 
 * @param {*} width 500
 * @param {*} type image/png
 */
function generateQRBase64Promises(text, width = 500, type = "image/png") {
  QRCode.toDataURL(text, { type: type, width: width })
    .then(url => {
      console.log(url);
      return url;
    })
    .catch(err => {
      console.error(err)
    });
}

/**
 * With async/await 返回Base64图片
 * @param {*} text 
 * @param {*} width 
 * @param {*} type 
 */
async function generateQRBase64Async(text, width = 500, type = "image/png") {
  return await asyncGenerateQR(text, width, type);
}

// 二维码读取

/**
 * 读取jpg图片转为Uint8Array
 * To decode directly into a Uint8Array
{
  width: 1000,
  height: 1000,
  exifBuffer: Uint8Array(12347) [
      0, 73, 73,  42, 0, 8, 0,   0, 0, 7, 0,  18,
      1,  3,  0,   1, 0, 0, 0,   1, 0, 0, 0,  26,
      1,  5,  0,   1, 0, 0, 0,  98, 0, 0, 0,  27,
      1,  5,  0,   1, 0, 0, 0, 106, 0, 0, 0,  40,
      1,  3,  0,   1, 0, 0, 0,   3, 0, 0, 0,  49,
      1,  2,  0,  13, 0, 0, 0, 114, 0, 0, 0,  50,
      1,  2,  0,  20, 0, 0, 0, 128, 0, 0, 0, 105,
    135,  4,  0,   1, 0, 0, 0, 148, 0, 0, 0, 166,
      0,  0,  0, 248,
    ... 12247 more items
  ],
  data: Uint8Array(4000000) [
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    255, 255, 255, 255,
    ... 3999900 more items
  ]
}
 * @param {*} jpgFile 
 * @returns 
 */
function decodejpg2Uint8Array(jpgFile) {
  /*
    { width: 320,
      height: 180,
      data: { '0': 91, '1': 64, ... } } // typed array
    */
  try {
    let jpegData = fs.readFileSync(jpgFile);
    let rawImageData = jpeg.decode(jpegData, { useTArray: true }); // return as Uint8Array
    // console.log(rawImageData);
    return rawImageData;
  } catch (error) {
    console.log(error);
  }
  return undefined;
}


/**
 * 读取png图片转为Uint8Array
 * @param {*} pngFile 
{
  width: 1000,
  height: 1000,
  depth: 8,
  interlace: false,
  palette: false,
  color: true,
  alpha: true,
  bpp: 4,
  colorType: 6,
  data: <Buffer ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ff ... 3999950 more bytes>,
  gamma: 0
}
 */
function decodepng2Uint8Array(pngFile) {
  try {
    let data = fs.readFileSync(pngFile);
    // console.log(data);
    let png = PNG.sync.read(data);
    // console.log(png);
    return png;
  } catch (error) {
    console.log(error);
  }
  return undefined;
}


/**
 * 读取二维码 目前仅支持JPG和PNG
 * @param {*} filepath 
 * @param {*} width 二维码宽 默认不指定 undefined
 * @param {*} height 二维码高 默认不指定 undefined
{
  binaryData: [ 49, 50, 51 ],
  data: '123',
  chunks: [ { type: 'numeric', text: '123' } ],
  version: 1,
  location: {
    topRightCorner: { x: 862.25, y: 137.74999999999997 },
    topLeftCorner: { x: 137.92861167852368, y: 137.92861167852368 },
    bottomRightCorner: { x: 863.1457082303077, y: 863.1457082303077 },
    bottomLeftCorner: { x: 137.74999999999997, y: 862.25 },
    topRightFinderPattern: { x: 741.5, y: 258.5 },
    topLeftFinderPattern: { x: 258.5, y: 258.5 },
    bottomLeftFinderPattern: { x: 258.5, y: 741.5 },
    bottomRightAlignmentPattern: { x: 638.2142857142858, y: 638.2142857142858 }
  }
}
 */
async function readQRCodeFromFileSync(filepath, width = undefined, height = undefined) {
  try {
    let decode = undefined;
    let ex = path.extname(filepath);
    if (ex == ".png") {
      decode = decodepng2Uint8Array(filepath);
    }
    else if (ex == ".jpg" || ex == ".jpeg") {
      decode = decodejpg2Uint8Array(filepath);
    }
    else {
      throw TypeError("readQRCodeFromFile: " + filepath + " 不是JPG或PNG");
    }
    let uint8Array = decode['data'];
    if (width == undefined) {
      width = decode['width'];
    }
    if (height == undefined) {
      height = decode['height'];
    }

    let code = jsQR(uint8Array, width, height);

    if (code) {
      console.log("Found QR code", code);
    }
    return code;
  } catch (error) {
    console.log(error);
  }
  return undefined;
}

module.exports = {
  readQRCodeFromFileSync,
  decodepng2Uint8Array,
  decodejpg2Uint8Array, 
  generateQRBase64Async,
  generateQRBase64Promises,
  createQRSync,
  asyncGenerateQR,
}
