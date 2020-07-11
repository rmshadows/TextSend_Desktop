 ### TextSend_Desktop

 - 安卓端传送门：[Gitee](https://gitee.com/rmshadows/TextSend_Android) [Github](https://github.com/rmshadows/TextSend_Android)

 - 帮助你在安卓和电脑之间互传文字，告别传段文字还要打开QQ的生活。

 - 版本/测试平台：Java 11 Swing Linux （Windows没测试过，但应该也没问题。OSX应该用不了，要改代码）

 - 其实这个应用主要用法是：安卓语音输入 + TextSend = 电脑语音输入。

 ### 使用方法：

 1.打开TextSend电脑服务端，配置防火墙，允许外来流量、同意访问网络。

 1.选择服务端口，默认54300端口，可以直接按回车跳过。如不确认，8秒后自动退出程序。

![setport](https://images.gitee.com/uploads/images/2020/0711/153035_c4690e50_7423713.png "屏幕截图.png")

 1.进入主界面，一个小悬浮框。点击启动，启动Server，手机才能连接。发送是用来向手机发送文字的，发送到手机的文字保存在手机的剪贴板上。

![main](https://images.gitee.com/uploads/images/2020/0711/153100_b5a4ae9c_7423713.png "屏幕截图.png")

 1. **注意** ：只能传文字，采用AES加密文本信息。手机传Emoji表情需要在开头加个文字或者标点符号。强烈建议在家庭等相对安全的局域网内使用，因为即使文本信息我用AES加密处理了，但是服务端应用结构简单，没有鉴权、用户管理等功能，我不排除你的局域网下有人搞事情对吧，但是几率很小就是。

 1.连接好手机后，打开一个Word文档，鼠标在Word文本输入的地方点一下，保证文本输入区域是焦点。这样手机发过来的文字会直接打在Word文档中。如果不小心失去焦点，没输入成功。你只需要右击粘贴就是，因为手机发送的文字也是存在电脑剪贴板上的。

 ### 截屏

![ss](https://images.gitee.com/uploads/images/2020/0711/153143_1a0db9a6_7423713.png "屏幕截图.png")


