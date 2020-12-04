# 减刑假释ApiDoc使用文档

用npm安装apidoc

`npm install apidoc -g`



在源码目录下，有一个`apidoc.json` 里面有定义项目的信息：

```
{
    "name": "JXSJ API",
    "version": "1.0.0",
    "description": "JXJS API DOC",
    "title": "JXJS",
    "url": "http://172.25.16.229:8082/jxjs/mvc"
}
```

 前面的不用解释看名字就知道了，最后一个url是项目路径，因为在后面的注释里写的接口路径都是相对路径，最后展示在页面上的路径就是这个url拼上相对路径就是接口的绝对路径。

然后再代码中接口的注释里加上：

```java
 	/**
     * @api {get} /ak/writ/save 获取人名章
     * @apiName getRmz
     * @apiGroup 安可
     * @apiVersion 1.0.0
     * 
     * @apiParam {String} userid 要获取人名章的用户ID.
     * 
     * @apiSuccess {String} is 图片章的流
     * 
     * @apiSampleRequest http://localhost:8081/jxjs/mvc/ak/hello
     * 
     */
```

注释意思详见[ApiDoc官方文档](https://www.jianshu.com/p/9353d5cc1ef8)中的“三、 apidoc注释参数”一节，很简答的看名字也能猜个大概意思。



特别的`apiSampleRequest` 加上了这个注解可以在生成的页面加上可以测试这个接口的表单![image.png](http://bed.thunisoft.com:9000/ibed/2019/12/10/30bc6c9bd8784844a2feefca474656bc.png)

这个地址可以暂时写开发环境的地址的在页面上调用者可以手动修改这个地址的



在代码里加了注释之后打开cmd，执行一下命令(以我自己的电脑路径为例) `apidoc  -o E:/jxjsYM/CD_FY_PRD_JXJS/40_源码/com-thunisoft-jxjs/web/apidoc` 
 `-o`是输出路径，这里我是输出到了web目录下的apidoc因为放在web上才能通过tas访问，以后大家生成的文档都放在这里，默认是扫描整个项目的文件是否有注解，**如果大家指定了要扫描的目录就是加了-i参数，那么-o参数后面的路径一定要修改，要不然你就会把整个项目生产的文档给覆盖掉，那个文档就只有你的接口的内容** 

效果如题![image.png](http://bed.thunisoft.com:9000/ibed/2019/12/10/fa796235aa704dbc9e07f447d8f2f8c6.png)

所以以后大家新增了接口的话，就按照文档在注释里加上apidoc的注解，然后用命令生成doc文档网站就可以了。