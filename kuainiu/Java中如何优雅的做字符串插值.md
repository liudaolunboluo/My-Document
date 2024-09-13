# Java中如何优雅的做字符串插值

其实笔者有点标题党了，严格来说java中并没有字符串插值的特性，但是笔者最近的工作会大量的去做字符串的拼接于是笔者就总结了一下如何在java中实现类似于字符串插值的方法

字符串插值是一种编程语言的特性，它可以在字符串中插入变量或表达式的值。插入的变量或表达式会被其实际值替换。这种特性在许多编程语言中都有实现，如Python、JavaScript、C#等。例如在typescript中：

```typescript
const name='张三'
const result=`我的名字是${name}`
```

Java语言本身并不支持字符串插值。这与Java的设计哲学有关，它倾向于明确性和简单性。字符串插值通常涉及到解析和执行字符串中的代码片段，这可能会引入复杂性和安全风险。但是在java中我们有其他办法可以间接实现字符串插值

## 1、字符串拼接或者StringBuilder

这个是最简单也是最常规的做法了，例如：

```java
String name = “张三”;
String result = "我的名字是" + name;
StringBuilder sb = new StringBuilder();
sb.append("我的名字是").append(name);
```

如果字符串较为简单，可以这样做，但是如果字符串比较复杂，比如我们需要拼接一个绝对路径:

```java
Long userId = 1L;
String userName = "张三";
Long schoolId = 2L;
String path = File.separator + "tmp" + File.separator + "school" + schoolId + File.separator + "student" + File.separator + userId + userName;
String path2 = sb.append(File.separator).append("tmp").append(File.separator).append("school").append(schoolId).append(File.separator)
                .append("student").append(File.separator).append(userId).append(userName).toString();
```

简单是确实简单，但是这样很难一眼看出我们的路径：`/tmp/school/schoolId/student/userId/userName`了并且如果需要再这个路径中间加一个变量或者修改一个变量将会很容易出错

## 2、String#format或者MessageFormat#format

String#format也是java的老演员了：

```java
Long userId = 1L;
String userName = "张三";
Long schoolId = 2L;
String template = "/tmp/school/s%/student/s%/s%";
String path = String.format(template, schoolId, userId, userName);
```

这样倒是可以一眼看出我们的路径结构，但是这个顺序也很容易出错，谁一眼看出这三个`s%`分别代表什么呢？如果有一天有人手动把参数顺序改了，是难以发现的

MessageFormat#format则是一个改良版：

```java
Long userId = 1L;
String userName = "张三";
Long schoolId = 2L;
String template = "/tmp/school/{0}/student/{1}/{2}";
String path = MessageFormat.format(template, schoolId, userId, userName);
```

把`s%`修改为了参数的下标，这样看上去比直接`s%`要直观一点，但是仍然不是太完美，因为他还是依赖于参数传递的顺序，并且没有注释的话也很难知道第一个参数代表什么第二个参数代表什么（因为一般这个模版都是一个常量），对于其他地方使用的话很容易出错，并且如果使用不习惯的话很容易把`{0}`写错，笔者就经常写成`${0}`，因为`{0}`这种写法实在是太小众了



## 3、Aapache的StringSubstitutor



`StringSubstitutor`是属于我们的老朋友`Apache Commons`的，它可以用来在字符串中替换变量

```java
Long userId = 1L;
String userName = "张三";
Long schoolId = 2L;
String template = "/tmp/school/${schoolId}/student/${userId}/${userName}";
Map<String, Object> valuesMap = new HashMap<>();
valuesMap.put("schoolId", schoolId);
valuesMap.put("userId", userId);
valuesMap.put("userName", userName);
StringSubstitutor sub = new StringSubstitutor(valuesMap);
String path = sub.replace(template);
System.out.println(path);
```

这样已经无限接近于我们的字符串插值的用法了，模版里是变量名称，所以我们可以一眼看出来我们的路径结构，唯一美中不足的是需要用一个Map来构造，略显啰嗦，如果对代码没有强迫症的同学可以使用这种解决方案

## 4、JDK21的StringTemplate



在java最近的一个LTS中，发布了StringTemplate:https://openjdk.org/jeps/430

在这个版本中新引入了——模板`STR`处理器：

```java
Long userId = 1L;
String userName = "张三";
Long schoolId = 2L;
String fullName  = STR."/tmp/school/\{schoolId}/student/\{userId}/\{userName}";
```

不过在JDK21中，需要开启预览功能（在jvm启动参数中新增`--enable-preview`参数）才能使用该功能

笔者很想深入研究一下这个很有意思的新功能，某种程度上来说他的出现填补了java字符串操作中的一大片空白，并且也是从纯编程角度来说笔者觉得是从jdk8以来最令人兴奋的升级（上一次笔者这么兴奋还是jdk8的stream确实让java变的不那么啰嗦了），但是在openjdk的2024年4月份的一封邮件上，openJdk表示在jdk23中将会暂时取消这个功能：


>The time has come for us to decide what to do about this feature with respect to JDK 23. Given that there is support for a change in the design but a lack of clear consensus on what that new design might look like, the prudent course of action is to (i) NOT ship the current design as a preview feature in JDK 23, and (ii) take our time continuing the design process. We all agree that our favourite language deserves us taking whatever time is needed to perfect our design! Preview features are exactly intended for this - for trying out mature designs before we commit to them for all time. Sometimes we are going to want to change our minds.

https://mail.openjdk.org/pipermail/amber-spec-experts/2024-April/004106.html

具体的原因简单来说是openjdk的作者们认为目前的StringTemplate因为引入了 模板`STR`处理器从而变的更复杂了，他们探索以更简单的方式来使用,比如：

```java
  StringTemplate st = “Hello \{name}”; 
```

然后让StringTemplate和String来做转换，但是：" but they caused more trouble than they solved"，带来问题更多了，于是作者决定干脆下掉这个还不成熟并且作者也不太满意的功能，最后作者也在文末表达了未来的构想，也就是说用静态方法或者实例方法来做字符串插值，不过目前还没有想到合适的：


>The remaining question that everyone is probably asking is: “so how do we do interpolation.”  The answer there is “ordinary library methods”.  This might be a static method (String.join(StringTemplate)) or an instance method (template.join()), shed to be painted (but please, not right now.).This is a sketch of direction, so feel free to pose questions/comments on the direction.  We’ll discuss the 



## 5、使用groovy



那么如果有代码强迫症又不能使用jdk21的同学应该如何优雅的在java中实现字符串插值呢？

答案是在你的java项目中使用groovy来实现。

说来惭愧，groovy能实现字符串插值笔者一开始是不知道的，是某次在写单元测试（没错用spock写）的时候偶然发现的，因为笔者发现在字符串中写${}居然不会报错，单元测试还通过了，于是笔者就进一步的想到了是不是可以在springboot项目中引入groovy，然后在groovy中完成字符串的生成。

首先这样做是完全可行的，在springboot中使用groovy是完全可以的，因为groovy也是运行在jvm上的语言，也可以被javac编译成class文件。

我们只需要在springboot中加入以下依赖：

```xml
<dependency>
    <groupId>org.codehaus.groovy</groupId>
    <artifactId>groovy-all</artifactId>
    <version>3.0.9</version>
    <type>pom</type>
</dependency>
```

这个依赖在之前引入spock的时候是添加了的，不过当时我们的`type`节点的值是test，意思是只有测试的时候使用，这里需要修改为`pom`。然后这个版本，jdk11对应的是3.0.9，如果你是jdk8那么2.4.15也足够了。

因为groovy的编译和java不太一样，所以我们还需要在pom中引入一个插件来编译我们的groovy代码：

```xml
   <plugin>
                <groupId>org.codehaus.gmavenplus</groupId>
                <artifactId>gmavenplus-plugin</artifactId>
                <version>1.7.1</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>addSources</goal>
                            <goal>addTestSources</goal>
                            <goal>generateStubs</goal>
                            <goal>compile</goal>
                            <goal>generateTestStubs</goal>
                            <goal>compileTests</goal>
                            <goal>removeStubs</goal>
                            <goal>removeTestStubs</goal>

                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <sources>
                        <!-- 在此节点下配置源码目录,可配置多个 -->
                        <source>
                            <directory>${project.basedir}/src/main/groovy</directory>
                            <includes>
                                <include>**/*.groovy</include>
                            </includes>
                        </source>
                    </sources>
                </configuration>
            </plugin>
```

`gmavenplus-plugin`就是groovy官方推出的插件便于在java中使用groovy。

然后我们需要在main目录下也就是和java目录同级下新建一个groovy目录，然后我们的groovy代码就写在这个目录下，包结构都和java一样，但是需要用根目录来区分两个语言以免混淆。

然后在groovy中我们一行代码就可以实现刚刚的例子：

```groovy
static String test(Long schoolId, Long userId, String userName) {
    return "/tmp/school/${schoolId}/student/${userId}/${userName}";
}
```

非常非常的简单和直观。

另外在grovvy中操作csv和解析json也远远比java简单，笔者之前操作csv都是放到python中去做的，虽然python中有panda这个神器，但是架不住groovy可以直接放到java里啊不用java又去调用python啊。并且groovy的类也可以直接注入到spring里的，非常的方便。

不过这并不代表我们要去滥用groovy，比如这里是java类哪里又突然变成了groovy那样会给项目带来很大的 维护难度，得不偿失，并且groovy是动态语言，很多报错不会在编译的时候报错只会在运行的时候报错，所以如果你不好好写单元测试可能有些问题只能在测试环境发现了。目前笔者只是把json、字符串插值相关的工具类替换为了groovy。
