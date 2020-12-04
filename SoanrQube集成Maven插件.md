## SoanrQube集成Maven插件

背景：前端时间集成了高至荣高工的插件之后用gitlabCI扫描出来的soanr问题突然暴增，本来sonarQube上只有14个主要的，结果CI执行之后显示有600多个主要，十多个严重和阻断，在水哥的帮助下排查了半天之后发现是jekins上构建脚本有问题，jekins上使用的是sonar scan进行的扫描，而gitlabci使用的是maven命令进行的扫描，所以得出结论jekins上要换成maven命令进行扫描。

## 集成Maven

实际上soanr插件已经自动集成到Maven中了，现在默认版本是3.0.2好像，如果对版本有要求也可以在pom文件里配置

> ```
> <plugin>
>     <groupId>org.codehaus.mojo</groupId>
>     <artifactId>sonar-maven-plugin</artifactId>
>     <version>3.0.2</version>
> </plugin>
> ```
>
> 

在 Maven 本地库中的 settings.xml 配置文件中的节点中添加如下配置：

```
 <profile>
            <id>sonar</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <!-- EXAMPLE FOR MYSQL -->
                <sonar.jdbc.url>
                  jdbc:mysql://localhost:3306/sonar?useUnicode=true&amp;characterEncoding=utf8
                </sonar.jdbc.url>
                <sonar.jdbc.driverClassName>com.mysql.jdbc.Driver</sonar.jdbc.driverClassName>
                <sonar.jdbc.username>sonar</sonar.jdbc.username>
                <sonar.jdbc.password>sonar</sonar.jdbc.password>
 
                <!-- optional URL to server. Default value is http://localhost:9000 -->
                <sonar.host.url>
                  http://localhost:9000
                </sonar.host.url>
            </properties>
        </profile>

```

主要是配置soanrQube的数据库连接配置。

然后再代码的目录下执行mvn clean install sonar:sonar 就可以了，执行完之后打开soanrQube就可以看到刚刚构建的结果了。

如果不想在setting里配置也可以在命令里加参数`mvn clean install sonar:sonar -Dsonar.host.url 等等这样的方式，参数可以在[sonarQube的官方网站](https://docs.sonarqube.org/latest/analysis/analysis-parameters/)上找到。

#### 使用jenkins构建soanr 

打开jenkins配置界面，JDK选择1.8，源码管理等不再赘述，主要说说怎么配置构建。

![](http://bed.thunisoft.com:9000/ibed/2019/06/03/290116cd88fc4e8ca137c850fad49fde.png)

属性这一栏就是刚刚的参数，也可以在刚刚的[sonarQube的官方网站](https://docs.sonarqube.org/latest/analysis/analysis-parameters/)上找到。***这里我没有配置数据库地址的原因是在我jenkins所在的服务器上的maven的setting文件里已经有了sonarQube相关的配置了。******

#### 然后就可以在jenkins上构建sonar啦，是不是这样构建之后的soanr问题会比之前用scan方式的多得多呢？