## springBoot项目替换jar包下lib目录下的jar包的class文件

背景：某个springBoot项目在现场启动时报错，报错在uim里是加载缓存报错，然后打开uim代码发现报错信息被吃了

![](http://bed.thunisoft.com:9000/ibed/2019/06/17/e77abd8be06a4046aca5b842c0a4c763.png)

然后联系了uim的同志出了替换文件，然后就发到驻地叫驻地替换重启，因为之前的项目都是Artery项目都是一个war包，然后war包替换lib下的jar的步奏很简单嘛，然后jar包替换也用的这种方式就是直接拷进去，结果替换完了启动报错

![](http://bed.thunisoft.com:9000/ibed/2019/06/17/7ff44302cd654831964c418464b5f17a.png)

报错信息总结来说就是springboot认为这个jar包不是maven打出来的，拒绝启动，谷歌了很久之后找到解决方案：**替换springboot的jar下的lib目录下的jar包应该先用`jar -xf jar`把jar包解压缩，然后再BOOT-INF/lib下的jar包里的class文件替换之，然后在用`jar -cfM0 xxx.jar *`命令重新打成jar包这个时候的这个jar包就没问题了**