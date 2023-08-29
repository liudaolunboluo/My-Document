# 让你和前端感情迅速升温的工具——JavaTypescriptConverter

## 前言

作为一个后端，平时除了和测试之外打交道最多的就是前端了（当然也有可能自己本身就又是后端又是前端），回忆一下上古时代后端是怎么和前端打交道：

- 上古时代

  大概也就是六七年前，笔者刚刚毕业，那会儿后端是用的word，自己写接口文档发给前端，一个接口文档很有可能代代相传，直到某一天一个不长眼的后端把他删了，于是接口文档这个东西就不存在了，接口变为了口口相传（口口是指在办公IM上）

- 马车时代

​	后来大家都用上了springboot，于是有了一个新的接口工具——swagger，可以在代码中用注解的方式来书写接口文档，swagger自己会有页面显示当前的所有接口，当然了swagger也有很多问题，比如：有时候swagger的页面会404这就需要去配置哪个webConfig、线上环境会关闭swagger所以要看接口只能去测试环境看，但是因为可能一个前端对接多个服务也就是意味着前端这里会有若干个swagger地址，如果前端整理能力差一点那就每对接一次就需要后端给一个swagger地址，并且swagger对代码有侵入性，如果有哪个不开眼的后端那一次迭代嫌麻烦没有加swagger的注解那么就。。。

​	并且swagger虽然有其他版本的swagger支持导出接口文档但是原生swagger我记得是不支持的，也就说如果有一个外部团队对接需要你提供接口文档的话又要回到最初的word写接口文档的时代了。

- 油车时代

  再后来，市面上有了很多强大的接口管理工具比如：yapi、apifox等，都支持团队纬度协同管理接口，这里不赘述这些工具，但是这些工具完美避开了以前的很多问题，例如yapi有上传插件可以直接读取java注释来生成接口参数的说明，比如yapi可以支持项目纬度来管理接口，笔者也一直在用yapi，但是这里有个小小的问题，从前端角度来说，有了接口文档但是还是需要在代码里定义参数，那么有没有办法后端能给提供出入参的ts文件呢？

从后端角度来说，如果把前端服务当成是一个微服务，那么后端和前端的交互就是微服务中的RPC，那么参考后端的RPC框架——feign、dubbo等，他们都可以服务提供者提供一个jar包，调用者直接用这个jar包就可以了并不用操心去定义参数和返回值。那么是不是可以像对接java一样后端来生成接口出入参数的代码？

笔者最近因为工作原因前端后端都需要写，于是自己开发了一个可以把Java等pojo类转换成ts文件的maven插件：JavaTypescriptConverter

## JavaTypescriptConverter介绍

### 使用

GitHub地址：github.com/liudaolunboluo/JavaTypescriptConverter

clone项目

在源码路径下执行:`mvn clean install`

如果远端构建需要请自行deploy到公司的maven仓库

（主要是原因本项目还没有上传到maven中央仓库，预计后面有人用的话会上传到中央仓库，目前算是阿尔法测试阶段，反正都是本地行为）

然后在需要使用此插件的项目的pom文件里配置：

```xml
            <plugin>
                <groupId>com.liudaolunhuibl</groupId>
                <artifactId>java-typescrpt-converter-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>TypescriptConverter</goal>
                        </goals>
                        <phase>package</phase>
                    </execution>
                </executions>
                <configuration>
                    <javaPackages>需要翻译的java文件的包路径，多个用英文分号隔开，例如:com.a.b.dto;com.d.r.bo</javaPackages>
                </configuration>
            </plugin>
```

然后可以在idea的右侧的Maven菜单里指定项目的Plugins里找到该插件，然后双击执行。

也可以在命令行中执行：

````shell
mvn com.liudaolunhuibl:java-typescrpt-converter-maven-plugin:1.0-SNAPSHOT:TypescriptConverter
````

在控制台中可以看到如下打印:

```shell
[INFO] begin to compile java to typescript!
```

就是开始翻译了（这里用的编译的单词并不正确，因为这不是编译，编译是将高级语言代码转换为低级语言代码的过程，例如将 Java 代码编译为字节码。而将 Java 代码转换为 TypeScript 代码只是将一种高级语言代码转换为另一种高级语言代码的过程，这种转换通常被称为代码转换或代码翻译。所以这里用编译只是作者装一下逼而已）

转换之前的java代码：

```java
/**
 * @author zhangyunfan@fiture.com
 * @version 1.0
 * @ClassName: TrainProjectCreateParam
 * @Description: 训练项目创建
 * @date 2022/11/11
 */
@Data
public class TrainProjectSaveParam implements Serializable {

    private static final long serialVersionUID = 707453049845155076L;

    /**
     * 训练项目ID，为空新增不为空更新
     */
    private Long id;

    /**
     * 训练任务名称
     */
    @NotNull(message = "训练任务名称不能为空")
    private String name;

    /**
     * 训练类型Id
     */
    @NotNull(message = "训练类型Id不能为空")
    private Long trainTypeId;

    private String trainTypeName;

    /**
     * 训练基础环境ID
     */
    @NotNull(message = "训练基础环境ID不能为空")
    private Long trainEnvironmentId;

    private String trainEnvironmentName;

    /**
     * 数据集id
     */
    private List<Long> datasetId;

    /**
     * 数据集格式
     */
    private String datasetFormat;

    /**
     * notebook版本
     */
    private String noteBookVersion;

    /**
     * 描述
     */
    private String description;

    /**
     * 创建者
     */
    private Long creatorId;

    /**
     * 磁盘信息
     */
    private String disk;

}
```

生成的typescript代码：

````typescript
/**
 * @author zhangyunfan@fiture.com
 * @version 1.0
 * @ClassName: TrainProjectCreateParam
 * @Description: 训练项目创建
 * @date 2022/11/11
 */
 class TrainProjectSaveParam {
    /**
     * 训练项目ID，为空新增不为空更新
     */
    id:number
    /**
     * 训练任务名称
     */
    name:string
    /**
     * 训练类型Id
     */
    trainTypeId:number
    /**
     * 训练类型名称
     */
    trainTypeName:string
    /**
     * notebook版本
     */
    noteBookVersion:string
    /**
     * 训练基础环境ID
     */
    trainEnvironmentId:number
    /**
     * 训练基础环境名称
     */
    trainEnvironmentName:string
    /**
     * 数据集id
     */
    datasetId:number[]
    /**
     * 数据集格式
     */
    datasetFormat:string
    /**
     * 描述
     */
    description:string
    /**
     * 创建者ID
     */
    creatorId:number
    /**
     * 磁盘信息
     */
    disk:string
}
export default TrainProjectSaveParam
````

执行完毕之后,ts文件会生成在项目的target目录下的typescript目录：

```shell
└── target
    └── typescript
        └── com
            └── zyf
                └── ai
                    └── platform
                        └── train
                            └── pojo
                                └── param
                                    ├── DeleteProjectMemberParam.ts
                                    ├── ProjectInviteMemberParam.ts
                                    ├── TrainMemberProjectPageParam.ts
                                    ├── TrainProfileCreateOrUpdateParam.ts
                                    ├── TrainProjectPageParam.ts
                                    ├── TrainProjectSaveParam.ts
                                    └── TrainProjectUpdateParam.ts
```



注意：

- pojo类应该遵循规范，例如都用privat修饰属性、List和Map都是接口声明而不是HashMap或者ArrayList声明。

- **目前暂不支持静态内部类**，如果有内部类则不会生成到ts文件中，非内部类的属性不影响，后续可能会支持。

- 如果是嵌套对象，那么会生成一样的类型，例如:`private Student a`转换出来就是：`a:Student`所以需要把这个类型也拷贝到前端项目里或者自己手动改成`any`

- 不支持有继承关系的属性自动映射到子类中，继承关系会原封不动的到生成的ts代码里，也就是说你的父类必须也在你的前端项目里，如果不想可以手动拷贝父类属性到子类中。



### 原理介绍

这里原理就是相当简单的对java代码的字符串做正则解析，剥离出属性、类名、属性名称等然后写到ts文件中，之所以没有用Java Parser来将Java代码解析为AST抽象语法树是因为ASR抽象语法树比较复杂，并且把java代码解析成AST也需要相当的时间，性能来说远远比不上纯字符串解析，但是缺点就是非常不灵活如果情况多一点就非常非常复杂，后期考虑会用AST语法树重构。

目前只支持出入参的代码转换，不支持接口定义的代码转换，主要是考虑到前端接口请求代码并不统一，后期如果有人提出的话可以考虑加上接口定义代码，不过就目前来说有了出入参前端自己定义接口是非常简单的事情只用写URL即可。



这样的话以后只需要把出入参的ts文件给前端就可以了，然后前端根据yapi上的接口文档自己定义访问请求代码，大大加快了研发效率，减少了加班时间。



