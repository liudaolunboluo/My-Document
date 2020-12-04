# 手把手教你修改Cocall样式

你是不是对Cocall的样式感觉到了厌倦，是不是已经看腻了系统自带的三种皮肤，也许你可以自己修改Cocall皮肤。效果图：

![image.png](http://bed.thunisoft.com:9000/ibed/2019/12/02/37f7f8f4da81467faa51f055319f8aa3.png)

![image.png](http://bed.thunisoft.com:9000/ibed/2019/12/02/1e62bb4d1dce464d917e4a695be80258.png)

打开你CC的安装目录下的resources目录，然后打开命令行输入`asar extract app.asar  app` 对asar文件解压缩，然后在同目录下会生成一个App文件夹，在App目录下的app\dist\electron\styles.css 下的这个css文件就是cocal的核心样式文件。没有安装asar可以用npm在线下载`npm install -g asar` 

然后接下来要怎么改就不用我多说了吧，为了给大家指条路我总结了下可能要改的样式：

对方的聊天气泡：`.fd-chat-message-item .fd-user-message>.fd-message-content>.fd-message-bubble`

我的聊天气泡：`.fd-chat-message-item.right .fd-user-message>.fd-message-content>.fd-message-bubble`

左侧侧边框样式：`.fd-cacall-contain .fd-frame-aside`


聊天背景：`.fd-chat-message-contain .fd-chat-message-content`



