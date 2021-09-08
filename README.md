 # TextSend_Desktop

 - Current Version: 3.1.0
 - 安卓端传送门：[Gitee](https://gitee.com/rmshadows/TextSend_Android) [Github](https://github.com/rmshadows/TextSend_Android)
 - 帮助你在安卓和电脑之间互传文字，告别传段文字还要打开QQ的生活。
 - 版本/测试平台：Java 11 Swing Linux （Windows没测试过，但应该也没问题。OSX应该用不了，要改代码）
 - 其实这个应用主要用法是：安卓语音输入 + TextSend = 电脑语音输入。

### 界面功能

服务端界面：

- 启动：主键启动并生成二维码。副键启动，但不生成二维码。（**注意**：这里生成的二维码是猜测的IP地址，实际IP如果不符合，请在**客户端** [比如你的手机客户端]中手动填写实际的服务端IP地址）
- 切换：切换到客户端模式。
- 发送：发送消息到客户端。

客户端界面：

- 连接：连接到服务端，需要在文本框中填写IP地址，格式：`192.168.1.1`或者`192.168.1.1:1234`
- 切换：切换到服务端模式。
- 发送：发送消息到服务端

 ### 使用方法

注意：客户端和服务端要在同个局域网（比如同一个Wifi）

打开TextSend电脑服务端，配置防火墙，允许外来流量、同意访问网络。

选择服务端口，默认54300端口，可以直接按回车跳过。

![setport](https://images.gitee.com/uploads/images/2020/0711/153035_c4690e50_7423713.png "屏幕截图.png")

进入主界面，一个小悬浮框。点击启动，启动Server，手机才能连接。发送是用来向手机发送文字的，发送到手机的文字保存在手机的剪贴板上。

![main](https://images.gitee.com/uploads/images/2021/0903/230318_b2442988_7423713.png "屏幕截图.png")

这时会跳出二维码窗口（可能是用浏览器打开的），手机客户端可以扫描二维码直接连接

![QR Code](https://images.gitee.com/uploads/images/2021/0903/230402_b574cc43_7423713.png "屏幕截图.png")



这是客户端界面

![client](https://images.gitee.com/uploads/images/2021/0903/230436_de1afbb4_7423713.png "屏幕截图.png")

**注意** ：只能传文字，采用AES加密文本信息。手机传Emoji表情需要在开头加个文字或者标点符号。强烈建议在家庭等相对安全的局域网内使用，因为即使文本信息我用AES加密处理了，但是服务端应用结构简单，没有鉴权、用户管理等功能，我不排除你的局域网下有人搞事情对吧，但是几率很小就是。

连接好手机后，打开一个Word文档，鼠标在Word文本输入的地方点一下，保证文本输入区域是焦点。这样手机发过来的文字会直接打在Word文档中。如果不小心失去焦点，没输入成功。你只需要右击粘贴就是，因为手机发送的文字也是存在电脑剪贴板上的。

### 更新日志

- 2021.09.06 - 3.1.1
  - 修复了服务端启动的部分小问题
  - 兼容了Windows
- 2021.09.03 - 3.1.0
  - 修复了3.0版本AES加密失败的Bug
- 2021.09.02 - 3.0.0
  - 解决了2.0版本中长文字发送出现数据丢失的问题
  - 重构了应用
  - 添加了客户端功能
  - 添加了服务端断开客户端功能
  - 限制了单客户端连接
  - 添加了移动端二维码扫描直连功能
  - 客户端模式添加了连接断开的检测
- 2021.07.24 - 2.1.1
  - 最初的版本

 ### 截屏

![ss](https://images.gitee.com/uploads/images/2020/0711/153143_1a0db9a6_7423713.png "屏幕截图.png")






