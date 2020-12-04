# 日志拆分—LogSplit

你是否感觉你系统的日志太混乱

你是否感觉你的驻地永远不会看日志

你是否拿到上完行的日志不知道从何下手

那么也许你的日志需要拆分啦。

## 传统做法：![image.png](http://bed.thunisoft.com:9000/ibed/2020/03/19/9YNRjoTSa.png)

根据包名给logback配置若干个logger。好处是简单易懂。缺点就是难于配置，logback会变得又臭又长不利于维护。并且日志也是不完整的，公用的代码产生的日志还是不会产生在这个logger下面。



实际上我们希望的是日志能和方法栈绑定，这个类下面所有的方法产生的日志都会在一个日志里

## 现在你可以这样做：





版本要求：springboot 2.1.6或以上、Artery4 jdk6/spring2.5.6

集成方法：1、在pom文件里加入以下依赖

```xml
			<dependency>
				<groupId>com.thunisoft</groupId>
				<artifactId>logspliter</artifactId>
				<version>0.0.1-RELEASE</version>
			</dependency>
```

Artery3、4在加上如下依赖：

```xml
		<dependency>
			<groupId>com.thunisoft</groupId>
			<artifactId>logspliter4artery345</artifactId>
			<version>0.0.2-RELEASE</version>
		</dependency>
```

2、在需要拆分的类或者方法里加上注解`@LogSplit`，value就是这个类或者方法的所占日志的key，也就是3中的sysId。如果在Controller的类和方法上同时加了的话那么方法上的注解的值优先。

3、在logback.xml里新增如下appender

```xml
<appender name="testsplit" class="ch.qos.logback.classic.sift.LogSplitAppender">
		<splitConfig>
			<defaultValue>stdout</defaultValue>
			<File>${LOG_HOME}/#{sysId}/${APP_NAME}_#{sysId}_${IP}_${PORT}.log
			</File>
			<FileNamePattern>${LOG_HOME}/#{sysId}/${APP_NAME}_#{sysId}_${IP}_${PORT}.%d{yyyy-MM-dd}.log
			</FileNamePattern>
		</splitConfig>
	</appender>
```

`defaultValue` 是你默认的系统日志的key，如果没有在代码里指定那么这个类生成的日志就会产生在这个目录下。

`File`和`FileNamePattern`  就是日志文件的所在地方和名字内容表达式。这个和以前用的`RollingFileAppender`里的保持一致就行了，需要特殊说明的是为了让加了注解的类生成的日志和其他日志区分可以在文件名上加上`#{sysId}` 占位符，这个最后的值就是在2中注解里设置的值。`${LOG_HOME}`和`${APP_NAME}`是在logback.xml上面定义的变量和本例无关。



4、Springboot项目暂时只支持标识了`RestController`、`Controller`、`WebService`、`Scheduled` 的类实现日志拆分。



5、效果图：

![image.png](http://bed.thunisoft.com:9000/ibed/2020/03/19/9YNWdsFgO.png)

![image.png](http://bed.thunisoft.com:9000/ibed/2020/03/19/9YNX6FBHk.png)

![image.png](http://bed.thunisoft.com:9000/ibed/2020/03/19/9YNXOymOm.png)