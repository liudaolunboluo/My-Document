[jib-maven-plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin) 是谷歌的maven的docker插件，这个可以让你在你本地像打jar包或者war包一样执行命令就可以生产镜像包并且上传到镜像仓库。





先在pom里配置依赖

```
			<!-- geogle docker插件 -->
			<plugin>
				<groupId>com.google.cloud.tools</groupId>
				<artifactId>jib-maven-plugin</artifactId>
				<version>1.0.2</version>
				<configuration>
					<from>
						<!-- 基础镜像 -->
						<image>registry.thunisoft.com:5000/base/tas:2.7.3-jdk6-1</image>
					</from>
					<to>
						<!- 镜像标签->
						<image>registry.thunisoft.com:5000/jxjs/jxjs:latest</image>
					</to>
					<allowInsecureRegistries>true</allowInsecureRegistries>
					<container>
						<appRoot>/opt/tas/webapps</appRoot>
						<!-- 相当于dockerfile的 ENV -->
						<environment>
							<CONFIG_PATH>/opt/tas/webapps/jxjs/WEB-INF/classes/props/config.properties
								\</CONFIG_PATH>
							<CONFIG_LOGBACK_COMMON_LEVEL>error</CONFIG_LOGBACK_COMMON_LEVEL>
							<CONFIG_LOGBACK_ROOT_LEVEL>error</CONFIG_LOGBACK_ROOT_LEVEL>
							<CONFIG_LOGBACK_STDOUT_LEVEL>on</CONFIG_LOGBACK_STDOUT_LEVEL>
							<CONFIG_DEBUG>false</CONFIG_DEBUG>
						</environment>
						<!-- 相当于dockerfile的 LABEL -->
						<labels>
							<acloud.description>this is jxjs image,made by zhangyunfan.</acloud.description>
							<acloud.env.CONFIG_LOGBACK_COMMON_LEVEL>error</acloud.env.CONFIG_LOGBACK_COMMON_LEVEL>
							<acloud.env.CONFIG_LOGBACK_ROOT_LEVEL>error</acloud.env.CONFIG_LOGBACK_ROOT_LEVEL>
							<acloud.env.CONFIG_LOGBACK_STDOUT_LEVEL>off</acloud.env.CONFIG_LOGBACK_STDOUT_LEVEL>
							<acloud.env.JVM_XMS>1024m</acloud.env.JVM_XMS>
							<acloud.env.JVM_XMX>2048m</acloud.env.JVM_XMX>
							<acloud.env.JVM_MAXNEWSIZE>256m</acloud.env.JVM_MAXNEWSIZE>
							<acloud.env.JVM_PERMSIZE>64m</acloud.env.JVM_PERMSIZE>
							<acloud.env.JVM_MAXPERMSIZE>512m</acloud.env.JVM_MAXPERMSIZE>
							<acloud.env.appconfigServerUrl>http://tap-dev.thunisoft.com/appconfig</acloud.env.appconfigServerUrl>
							<acloud.env.corp>2400</acloud.env.corp>
							<acloud.env.appname>jxjs</acloud.env.appname>
							<acloud.env.pinpoint.applicationName>jxjs</acloud.env.pinpoint.applicationName>
						</labels>
						<volumes>
						<volume>/opt/tas/logs/</volume>
						</volumes>
					</container>
				</configuration>
			</plugin>
			
			
```
###### 然后再源码目录执行mvn clean install jib:build命令

##### 附录：以下是每个节点的意义和作用和配置

| 领域                      | 类型                                                         | 默认                                                         | 描述                                                         |
| ------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| `to`                      | [`to`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#to-object) | *需要*                                                       | 配置目标映像以构建应用程序。                                 |
| `from`                    | [`from`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#from-object) | 看到 [`from`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#from-object) | 配置基本映像以构建应用程序。                                 |
| `container`               | [`container`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#container-object) | 看到 [`container`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#container-object) | 配置从您的映像运行的容器。                                   |
| `extraDirectories`        | [`extraDirectories`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#extradirectories-object) | 看到[`extraDirectories`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#extradirectories-object) | 配置用于向image添加任意文件的目录。                          |
| `allowInsecureRegistries` | boolean                                                      | `false`                                                      | 如果设置为true，Jib将忽略HTTPS证书错误，并可能作为最后的手段回退到HTTP。`false`强烈建议保留此参数设置，因为HTTP通信未加密且网络上的其他人可见，并且不安全的HTTPS并不比普通HTTP好。[如果使用自签名证书访问注册表，则将证书添加到Java运行时的可信密钥](https://github.com/GoogleContainerTools/jib/tree/master/docs/self_sign_cert.md)可能是启用此选项的替代方法。 |
| `skip`                    | boolean                                                      | `false`                                                      | 如果设置为true，则跳过Jib执行（对于多模块项目很有用）。这也可以通过`-Djib.skip`命令行选项指定。 |

`from` 是具有以下属性的对象：

| 属性         | 类型                                                         | 默认                     | 描述                                                         |
| ------------ | ------------------------------------------------------------ | ------------------------ | ------------------------------------------------------------ |
| `image`      | String                                                       | `gcr.io/distroless/java` | 基本image的image参考。                                       |
| `auth`       | [`auth`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#auth-object) | *没有*                   | 直接指定凭证（替代`credHelper`）。                           |
| `credHelper` | String                                                       | *没有*                   | 指定可以验证拉取基本映像的凭据帮助程序。此参数可以配置为凭证帮助程序可执行文件的绝对路径，也可以配置为凭据帮助程序后缀（后续`docker-credential-`）。 |

`to` 是具有以下属性的对象：

| 属性         | 类型                                                         | 默认   | 描述                                                         |
| ------------ | ------------------------------------------------------------ | ------ | ------------------------------------------------------------ |
| `image`      | String                                                       | *需要* | 目标image的image参考。这也可以通过`-Dimage`命令行选项指定。  |
| `auth`       | [`auth`](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#auth-object) | *没有* | 直接指定凭证（替代`credHelper`）。                           |
| `credHelper` | String                                                       | *没有* | 指定可以对推送目标映像进行身份验证的凭据帮助程序。此参数可以配置为凭证帮助程序可执行文件的绝对路径，也可以配置为凭据帮助程序后缀（后续`docker-credential-`）。 |
| `tags`       | list                                                         | *没有* | 要推送的附加标签。                                           |

`auth`是具有以下属性的对象（请参阅[使用特定凭据](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#using-specific-credentials)）：

| 属性       | 类型     |
| ---------- | -------- |
| `username` | `String` |
| `password` | `String` |

`container` 是具有以下属性的对象：

| 属性                  | 类型    | 默认      | 描述                                                         |
| --------------------- | ------- | --------- | ------------------------------------------------------------ |
| `appRoot`             | String  | `/app`    | 容器上放置应用程序内容的根目录。特别适用于WAR打包项目，通过指定放置爆炸WAR内容的位置来处理不同的Servlet引擎基础映像; 以[WAR用法](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#war-projects)为例。 |
| `args`                | List    | *没有*    | 附加到命令的附加程序参数以启动容器（类似于与[ENTRYPOINT](https://docs.docker.com/engine/reference/builder/#entrypoint)相关的Docker的[CMD](https://docs.docker.com/engine/reference/builder/#cmd)指令）。在未设置自定义的默认情况下，此参数实际上是Java应用程序的main方法的参数。`entrypoint` |
| `entrypoint`          | List    | *没有*    | 启动容器的命令（类似于Docker的[ENTRYPOINT](https://docs.docker.com/engine/reference/builder/#entrypoint)指令）。如果设置，然后`jvmFlags`和`mainClass`被忽略。您还可以设置`<entrypoint>INHERIT</entrypoint>`为指示`entrypoint`和`args`应从基础image继承。* |
| `environment`         | Map     | *没有*    | 用于在容器上设置环境变量的键值对（类似于Docker的[ENV](https://docs.docker.com/engine/reference/builder/#env)指令）。 |
| `extraClasspath`      | `list`  | *没有*    | 容器中的附加路径预先添加到计算的Java类路径中。               |
| `format`              | String  | `Docker`  | 使用`OCI`建立一个[OCI容器image](https://www.opencontainers.org/)。 |
| `jvmFlags`            | List    | *没有*    | 运行应用程序时传入JVM的其他标志。                            |
| `labels`              | Map     | *没有*    | 用于应用image元数据的键值对（类似于Docker的[LABEL](https://docs.docker.com/engine/reference/builder/#label)指令）。 |
| `mainClass`           | String  | *推断* ** | 从中启动应用程序的主类。                                     |
| `ports`               | List    | *没有*    | 容器在运行时公开的端口（类似于Docker的[EXPOSE](https://docs.docker.com/engine/reference/builder/#expose)指令）。 |
| `useCurrentTimestamp` | Boolean | `false`   | 默认情况下，Jib会擦除所有时间戳以保证可重复性。如果将此参数设置为`true`，则Jib会将image的创建时间戳设置为构建时间，这会牺牲再现性，以便能够轻松判断image的创建时间。 |
| `user`                | String  | *没有*    | 用于运行容器的用户和组。值可以是用户名或UID以及可选的组名或GID。以下都是有效的：`user`，`uid`，`user:group`，`uid:gid`，`uid:group`，`user:gid`。 |
| `volumes`             | List    | *没有*    | 指定容器上的装入点列表。                                     |
| `workingDirectory`    | String  | *没有*    | 容器中的工作目录。                                           |

`extraDirectories`是具有以下属性的对象（请参阅[向image添加任意文件](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin#adding-arbitrary-files-to-the-image)）：

| 属性          | 类型 | 默认                           | 描述                                                         |
| ------------- | ---- | ------------------------------ | ------------------------------------------------------------ |
| `paths`       | List | `[(project-dir)/src/main/jib]` | 额外目录列表。可以是项目根的绝对或相对。                     |
| `permissions` | List | *没有*                         | 将容器上的文件路径映射到Unix权限。（仅对从额外目录添加的文件有效。）如果未配置，则对于目录，权限默认为“755”，对于文件，权限默认为“644”。 |

**（jib:dockerBuild仅）** `dockerClient`是具有以下属性的对象：

| 属性          | 类型   | 默认     | 描述                                                         |
| ------------- | ------ | -------- | ------------------------------------------------------------ |
| `executable`  | String | `docker` | 设置被调用的Docker可执行文件的路径，以将映像加载到Docker守护程序中。 |
| `environment` | Map    | *没有*   | 设置Docker可执行文件使用的环境变量。                         |

#### 系统属性

这些参数中的每一个都可以通过命令行使用系统属性进行配置。Jib的系统属性遵循与配置参数相同的命名约定，每个级别由点（即`-Djib.parameterName[.nestedParameter.[...]]=value`）分隔。以下是一些例子：

```
mvn编译jib：build \
    -Djib.to.image = myregistry / myimage：latest \
    -Djib.to.auth.username = $ USERNAME \
    -Djib.to.auth.password = $ PASSWORD

mvn编译jib：dockerBuild \
    -Djib.dockerClient.executable = / path / to / docker \
    -Djib.container.environment = key1 = “ value1 ”，key2 = “ value2 ” \
    -Djib.container.args = ARG1，ARG2，ARG3
```

下表包含不可用作构建配置参数的其他系统属性：

| 属性                      | 类型     | 默认                                            | 描述                                                         |
| ------------------------- | -------- | ----------------------------------------------- | ------------------------------------------------------------ |
| `jib.httpTimeout`         | INT      | `20000`                                         | 注册表交互的HTTP连接/读取超时，以毫秒为单位。使用值为`0`无限超时。 |
| `jib.useOnlyProjectCache` | Boolean  | `false`                                         | 如果设置为true，则Jib不会在不同的Maven项目之间共享缓存（即`jib.baseImageCache`默认值`[project dir]/target/jib-cache`而不是`[user cache home]/google-cloud-tools-java/jib`）。 |
| `jib.baseImageCache`      | String   | `[user cache home]/google-cloud-tools-java/jib` | 设置用于缓存基础image层的目录。此缓存可以（并且应该）在多个image之间共享。 |
| `jib.applicationCache`    | String   | `[project dir]/target/jib-cache`                | 设置用于缓存应用程序层的目录。该缓存可以在多个image之间共享。 |
| `jib.console`             | `String` | *没有*                                          | 如果设置为`plain`，Jib将打印纯文本日志消息，而不是在构建期间显示进度条。 |

**如果配置argswhile entrypoint设置为'INHERIT'，则配置的args值将优先于从基本映像传播的CMD。*

