# 记一次用Arthas排查线上环境慢的问题

现场反映贵州现场NP系统的的SPXT打开民事案件的案件编辑页面特别特别的慢，并且是前两天突然出现的，打开一个编辑页面大概要40秒，看到问题第一个想到的是是不是线程哪里卡住了，于是叫驻地dump了线程日志来分析，

![](http://bed.thunisoft.com:9000/ibed/2019/08/21/f6104802328a4c7f9c8247c4c7daf6b1.png)

没有死锁和等待锁的，看线程日志比较正常。头比较大了，于是求助顾森林同学帮忙看看，他看了之后给出了一个重要的信息，有个线程状态是runable在运行中并且是在调用接口![](http://bed.thunisoft.com:9000/ibed/2019/08/21/cf3c4ffc60734b58989242ad64dc85f2.png)

于是看代码

```java
private String request(String uri, Map<String, String> params, boolean post) throws Exception {
        String wsAddress = GyConfigUtils.getWritServerUrl();
        StringBuilder url = new StringBuilder(wsAddress); // TODO 配置
        url.append("/api").append(uri);
        String result = null;
        if (post) {
            result = ApacheHttpUtils.sendParamsToUrlPost(url.toString(), params);
        } else {
            result = ApacheHttpUtils.sendParamsToUrlGet(url.toString(), params);
        }
        return result;
    }
```

很正常的调用代码，并没有发现出什么问题，并且在没有看`ApacheHttpUtils.sendParamsToUrlPost`源码以后我想当然的觉得即使是调用接口慢但是也有个超时时间，一般不超过十秒，所以肯定有其他地方慢，在这里卡住了，我也打开过F12看过浏览器的网络调用并没有异常的地方，于是我决定做最后一次挣扎用Arthas试试。Spxt是在linux系统，于是我远程到服务器启动审判系统，既然可能是调用接口慢那么我就用arthas的[trace](https://alibaba.github.io/arthas/trace.html) 命令试试看下到底这个地方有多慢，

![](http://bed.thunisoft.com:9000/ibed/2019/08/21/220006a757204c8ca8d45a6ca8c72d61.png)

真是柳暗花明有一村，不看不知道越来这个request方法足足花了43秒，那么打开页面慢的原因基本上找到了就是这个request方法里调用接口的时候特别慢，那么为什么没有报错超时了，于是我这次聪明了去到了调用接口的地方看了下源码，`  client.getHttpConnectionManager().getParams().setSoTimeout(60000); ` 原来设置的超时时间是60秒，那就难怪了。为了验证我的想法叫现场的同事把这里的超时时间改到了10秒替换重启，然后试了一下果然比之前过多了现在打开页面的时间就是10秒左右了并且后台日志抛出`time out` 超时异常，那么我们可以确定百分之百是这里出问题了，那么下一步就是找下原因为什么这里，调用的接口是NP的`writ-server`这个服务，找到源码之后并没有看出来哪里可能会特别慢，于是还是用我们的可爱的Arthas来排查，结果出问题了，因为Write-server是windows服务器，在上面用`java -jar arthas-boot.jar`启动Arthas的时候报错了，先是提示jar目录下没有tools.jar然后把tools.jar放进去之后报错`com.sun.tools.attach.AttachNotSupportedException: no providers installed` 之前遇见过但是没有解决，谷歌之后原来是系统配置的java的目录下的bin目录下没有`attach.dll` 文件，把文件放进去Arthas就可以启动了，然后我还是用trace命令看下发现在接口里真正慢的地方是`writHandler.isSatisfiedPrecondition`这一行代码，但是writHandler是一个接口实现类若干，`这个对象是通过

```java
				clazz = (Class<WritHandler>) Class.forName(writ.getConditionHandler());
                WritHandler writHandler = clazz.newInstance();
```

这两句代码获取的，所以我们光看代码是不知道这里是用了哪个实现类，于是我用了Arthas的[watch](https://alibaba.github.io/arthas/watch.html) 命令，去找`writ`对象的`getConditionHandler`方法的返回值，这个返回值就是`WritHandler`的实现类的名称，找到了一堆实现类，没办法我只有一个一个的用trace命令来看到底是哪个实现类在运行`isSatisfiedPrecondition`方法的时候特别的慢，这个之前我突然想起来可以先看看write-server的jdbc日志看看是不是数据库慢，于是我看到了

![](http://bed.thunisoft.com:9000/ibed/2019/08/21/727d7ce99b45472daf0f9cbb438533d3.png)

这个，这个简单的sql居然花了17秒，当然了到这里了我们还不能宣布破案了，因为不能确定这个慢sql和NP页面打开慢有什么必然的联系万一是其他的呢，这个只能当作证据之一。然后我挨个挨个的trace之后发现了有两个实现类特别的慢，大概一个实现类是17秒，他们是`SsqlywgzsWithDsrWritTemplate`和`GgktyWritTemplate`，他们最耗时的地方都是`arteryDao.getJdbcTemplate().queryForList`这个地方都是查询数据库，然后他们查询的表都是`T_MS_DSR`这个表，那么这个时候都对上了，jdbc里打印的慢sql也是这张表，代码里慢的地方也是查的这张表，那么罪魁祸首就是这张表，一个简单的查询sql特别的慢。于是猜想是不是这个表的C_BH_AJ没有加索引，叫现场的同事验证之后发现这个表有索引的，然后经过同事提醒是不是没有走索引但是我分析这个就是一个简单的where查询不可能不走索引啊，于是抱着试试看看的心态让现场的同事看看syabse的执行计划看下走索引没有，![](http://bed.thunisoft.com:9000/ibed/2019/08/21/66c611adcac241dbba9c962165bd90bd.png)

居然没有走索引！用的是全表扫描，那就难怪了，于是让现场的同事重建索引，然后重新执行了下这个sql现在只要零点几秒了，然后又看了下SPXT的页面，一切正常了~，至此可以全部结案了。



**总结：遇见现场系统慢的问题先看下后台日志报错没有然后看下JDBC日志看下是不是数据库出问题了，在不行就dump线程日志分下是不是线程出问题了，然后根据线程日志定位下代码哪里可能会慢，然后在用Arthas之类的工具分析在运行时间**



## Arthas真的是很好用啊