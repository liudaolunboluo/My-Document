# 如何让你的Java变成缝合怪

相信诸位Javaer眼馋别的语言的某些语法很久了吧，例如C#的扩展方法、python的元组、多返回值等等，虽然JDK已经更新到19.0.2了但是遗憾的是仍然不能实现这些语法，那么今天笔者手把手来让大家看看如何在Java中实现这些方便的语法。

## 事前准备

本次实验我们用到的工具是老外的一个java类库——Manifold。github地址：git@github.com:manifold-systems/manifold.git

Manifold 是一个 Java 编译器插件和框架，旨在扩展 Java 类型系统的范围，以类型安全的方式直接连接到信息源

官方网站地址：http://manifold.systems/

首先我们在maven中添加如下依赖：

```xml
<dependency>
  <groupId>systems.manifold</groupId>
  <artifactId>manifold-ext</artifactId>
  <version>2023.1.0</version>
</dependency>
<dependency>
  <groupId>systems.manifold</groupId>
  <artifactId>manifold-tuple-rt</artifactId>
  <version>2023.1.0</version>
</dependency>
```

并配置如下编译插件:

```xml
  				<plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                    <encoding>UTF-8</encoding>
                    <compilerArgs>
                        <arg>-Xplugin:Manifold no-bootstrap</arg>
                    </compilerArgs>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>systems.manifold</groupId>
                            <artifactId>manifold-ext</artifactId>
                            <version>2023.1.0</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.0</version>
                <configuration>
                    <source>11</source>
                    <target>11</target>
                    <encoding>UTF-8</encoding>
                    <compilerArgs>
                        <!-- Configure manifold plugin-->
                        <arg>-Xplugin:Manifold</arg>
                    </compilerArgs>
                    <!-- Add the processor path for the plugin -->
                    <annotationProcessorPaths>
                        <path>
                            <groupId>systems.manifold</groupId>
                            <artifactId>manifold-tuple</artifactId>
                            <version>2023.1.0<</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
```

如果你的项目中使用了 Lombok，需要把 Lombok 也加入 annotationProcessorPaths：

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>${lombok.version}</version>
    </path>
    <path>
        <groupId>systems.manifold</groupId>
        <artifactId>manifold-ext</artifactId>
        <version>${manifold.version}</version>
    </path>
</annotationProcessorPaths>
```



然后我们在idea中安装`Manifold`插件，直接搜索安装即可

看到这里，各位如果稍微熟悉lombok的同学就会嘴角微微上扬了，这不和lombok使用方法差不多嘛。事实上原理也的确差不多

## 1、多返回值

为什么我们需要多返回值？（why？）

考虑如下业务场景：业务方传给我们一个数组，里面包含同一个商品在不同平台的价格，我们需要返回数组中的最低价格和最高价格，我们自然就有如下写法：

```java
Integer max = List.of(1,2,3,4,5).stream().max(Comparator.comparing(Integer::valueOf)).get();
Integer min = List.of(1,2,3,4,5).stream().min(Comparator.comparing(Integer::valueOf)).get();
```

这样做的缺点是我们要排序两次，当然我们也可以先让List有序然后取出第一个元素和最后一个元素。那么我们没有办法能让一个排序的方法返回两个值：max和min，我们调用一次方法就可以解决，类似于python这样写：

```python
max, min = order_array()
```

那么如何实现？（how？）

我们用Manifold可以这样实现:

```java
var minMax = findMinMax(new int[] { 1,2,3,4,5 });
int max = minMax.max;
int min = minMax.min;

static auto findMinMax(int[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i : data) {
            if (i < min) {
                min = i;
            }
            if (i > max) {
                max = i;
            }
        }
        return min, max;
    }
```

`auto`类似于jdk11开始的关键字`var`就是自动联想类型，只不过var只能用于声明变量不能用于方法的返回值

这样我们就可以在java中实现多返回值了。



## 2、元组

为什么我们需要元组？（why？）

在python中有一种数据类型叫元组，可以这样创建和使用：

```python
tup1 = ('physics', 'chemistry', 1997, 2000)
tup2 = (1, 2, 3, 4, 5, 6, 7 )
 
print "tup1[0]: ", tup1[0]
print "tup2[1:5]: ", tup2[1:5]
```

可以非常简洁和快速的访问，java中并没有类似的数据结构，java中比较类似的是List，如果没有guava的Lists和JDK9的List.of话创建List并且插入元素是一件很啰嗦的事情。并且在python中还有一个数据结构叫字典，可以如下使用：

```python
>>> tinydict = {'a': 1, 'b': 2, 'b': '3'}
>>> tinydict['b']
'3'
>>> tinydict
{'a': 1, 'b': '3'}
```

java中类似的是Map，虽然说jdk9开始我们可以快速的创建Map:

```java
Map.of("Hello", 1, "World", 2);
```

或者用guava：

```java
Map<String, Integer> myMap = ImmutableMap.of("a", 1, "b", 2, "c", 3); 
```

但是如果用的不是 Java9 及以上版本（Java8：直接报我身份证就行），或者你不用guava—— 然而 ImmutableList.of 用起来终究是比不上 List.of 这样的正统来的自然，并且相较于python来说仍然不够简洁

那么如何实现？（how？）

我们用Manifold可以这样实现:

```java
auto t = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
auto td = (name: "Bob", age: "35");
System.out.println(t);
System.out.println("Name: " + td.name + " Age: " + td.age);
```

也可以简单的copy:

```java
var t = (name, age);
var t2 = t.copy(); 
out.println(t2.name);
```

不过这也需要注意的是：为了类型安全，元组类型基于项目类型和项目名称。这意味着元组表达式中名称/值对的顺序与其类型无关紧要：

```java
var t1 = (name: "Milo", age: 88);
var t2 = (age: 77, name: "Twila");

 //it's true!
t1.getClass() == t2.getClass()
```

那么元组具体可以怎么在业务场景中帮助我们呢？

我们想象一个场景：我们有一个对象person，这个对象有若干个属性，但是我们某个需求只要求我们打印出这个对象的姓名和年龄属性，如果我们用传统的java写法可能直接foreach即可，但是这个person对象中有某个字段特别的大比如说他的头像图片base64之后的字符串，那么为了不内存泄露和节约内存空间我们可以复制一个Person对象，只set他的name和age字段然后传递给下层调用，这样就不会内存泄露了，但是如果这个name和age换成其他需要特殊处理的字段呢？我们是不是只能新建一个对象来接收了，这里我们就可以用元组了：

```java
 List<Person> persons = List.of( new Person("Bob",18),new Person("Tom",19),new Person("Joe",20));
        var result = nameAge(persons);
        for (var person : nameAge(persons)) {
            System.out.println("name: " + person.name + " age: " + person.age);
        }

	public static auto nameAge(List<Person> list) {
        return list.stream().map(p -> (p.name, p.age)//这里用了元组
                .collect(Collectors.toList());
    }
```

这样就让我们的java代码简洁了许多，不用去创建太多的DTO、VO了



## 3、扩展方法

为什么我们需要扩展方法？（why？）

我们假设有如下业务场景：其他业务方会通过MQ消息给我们传递若干个用户的邮箱或者手机号并且只会传一个字符串格式是多个之间用分号隔开（为什么不用List？因为之前的需求是只传一个手机号或者邮箱后面业务迭代改成多个，为了改动最小就不新增topic和修改消息体），然后我们需要把这个字符串转换成List，我们很自然的就有如下代码:

```java
String param = "932907369@qq.com;932907369@163.com;932907369@geogle.com;932907369@github.com";
List<String> mailList = List.of(param.split(";"));
```

这个其他地方有类似逻辑我们就会很自然的封装成一个`StringUtils`来搞定，但是Util的问题在于：1、新来的同学或者不了解项目代码的同学可能不知道有这个util仍然会书写大量的重复代码2、后面util可能会越来越多越来越膨胀，那么有没有办法我们直接在String里面书写这个方法，如果可以的话那代码就可以简洁成:

```java
 List<String> strings = "932907369@qq.com;932907369@163.com;932907369@geogle.com;932907369@github.com".splitByDelimiter(";");
```

所以总结起来，我们需要扩展方法的原因是：

- 可以对现有的类库，进行**直接**增强，而不是使用工具类

- 相比使用工具类，使用类型本身的方法写代码更流畅更舒适

- 代码更容易阅读，因为是链式调用，而不是用静态方法套娃

那么如何实现？（how？）

我们基于Manifold这样书写：

```java
package com.zyf.extensions.java.lang.String;

import manifold.ext.rt.api.Extension;
import manifold.ext.rt.api.This;

import java.util.List;

/**
 * @author zhangyunfan@fiture.com
 * @version 1.0
 * @ClassName: StringExt
 * @Description: String 扩展方法
 */
@Extension
public final class StringExt {

    public static List<String> splitByDelimiter(@This String str, String delimiter) {
        return List.of(str.split(delimiter));

    }
}
```

可以发现本质上还是工具类的静态方法，但是有一些要求：

1.工具类需要使用 Manifold 的 @Extension 注解

2.静态方法中，目标类型的参数，需要使用 @This 注解

3.工具类所在的包名，需要以 **extensions.目标类型全限定类名** 结尾

—— 用过 C# 的同学应该会会心一笑，这就是模仿的 C# 的扩展方法。

关于第 3 点，之所以有这个要求，是因为 Manifold 希望能快速找到项目中的扩展方法，避免对项目中所有的类进行注解扫描，提升处理的效率。

具备了扩展方法的能力，现在我们就可以这样像上文一样调用了：

```java
List<String> strings = "932907369@qq.com;932907369@163.com;932907369@geogle.com;932907369@github.com".splitByDelimiter(";");
System.out.println(strings);
```

此时idea不仅不会报错，还可以正确的打印出结果。

### 3.1、数组的扩展方法

别急，Manifold还有一个彩蛋，考虑如下代码：

```java
String str = "123,456, 123, 456,789";
String[] numStrs = str.split(",");
System.out.println(numStrs.toString());
```

你的程序会打印什么？我相信诸位的结果都应该是：`[Ljava.lang.String;@566776ad`，一个内存地址（我相信大家都有被日志里出现这种内存地址告崩溃过——“tmd，日志白打了”）

那么如果你集成了Manifold的话结果会不一样吗？还真不一样，他的输出结果是：`[123, 456,  123,  456, 789]`,不信你可以试试，那么他是怎么做到的呢？我们反编译刚刚的代码可以看到：

```java
String str ="123, 456,123,456,789";
String[] numStrs = str.split(",");
System.out.printIn(ManArrayExt.toString(numStrs));
```

这里自动把数组的toString切换成了Manifold自己的数组扩展类的toString方法，从此以后告别内存地址。

那么这里替换之后还有一个好处是避免了NPE，比如说我们 的代码换成如下：

```java
String[] numStrs = null;
System.out.println(numStrs.toString());
```

这不肯定NPE吗？但是如果你用了Manifold的话就不会NPE而是会输出null，原因就是因为null变成了方法的入参而不是被调用的对象。

那么这个ManArrayExt到底是啥呢？

由于JDK 中，数组并没有一个具体的对应类型，那为数组定义的扩展类，要放到什么包中呢？看下 ManArrayExt 的源码，发现 Manifold 专门提供了一个类 manifold.rt.api.Array，用来表示数组，这里面也就存放了很多数组的扩展方法。比如 ManArrayExt 中为数组提供的 toList和copy 的方法：

```java
@Extension
public class ManArrayExt
{
  /**
   * Returns a fixed-size list backed by the specified array.  (Changes to
   * the returned list "write through" to the array.)  This method acts
   * as bridge between array-based and collection-based APIs, in
   * combination with {@link Collection#toArray}.  The returned list is
   * serializable and implements {@link RandomAccess}.
   *
   * @return a list view of the specified array
   */
  public static List<@Self(true) Object> toList( @This Object array )
  {
    if( array.getClass().getComponentType().isPrimitive() )
    {
      int len = Array.getLength( array );
      List<Object> list = new ArrayList<Object>( len );
      for( int i = 0; i < len; i++ )
      {
        list.add( Array.get( array, i ) );
      }
      return list;
    }
    return Arrays.asList( (Object[])array );
  }
  
    public static @Self Object copy( @This Object array, int newLength )
  {
    int length = Array.getLength( array );
    Object dest = Array.newInstance( array.getClass().getComponentType(), newLength < 0 ? length : newLength );
    //noinspection SuspiciousSystemArraycopy
    System.arraycopy( array, 0, dest, 0, length );
    return dest;
  }

```

这样的好处就是我们在使用java中的数组的时候就更加的简单了，再也不用去手动转List或者用ArraysStream了



## 4、Manifold原理

上文说过，Manifold的原理和lombok是一样的，是在我们java编译的时候动手脚的，具体是在注解解析的时候生成的代码也就是用的JSR 269: Pluggable Annotation Processing API (编译期的注解处理器)。

在编译期阶段，当 Java 源码被抽象成语法树 (AST) 之后，Lombok和Manifold一样 会根据自己的注解处理器动态的修改 AST，增加新的代码 (节点)，在这一切执行之后，再通过分析生成了最终的字节码 (.class) 文件，说白了就是动态的去修改AST语法树，至于什么是JSR 269以及什么是AST语法树以及Manifold底层原理后面笔者有时间的话会写文章一一道来。



## 5、总结

现在笔者已经尝试在新项目中使用Manifold了，由于笔者公司的项目部分老项目是JDK8，新项目都是JDK11，所以对于部分特性不是特别的需要，如果诸位有低于JDK8的，真的可以试试，可以对写代码的简洁性带来质的提升，不过笔者建议大家谨慎使用不能太依赖了，毕竟只是第三方工具包，有的公司lombok都会禁止使用更别说这个了。其次，这个工具对开发者的要求也特别高，比如上文的扩展方法，如果有的同学不管这个方法是不是可以放在这个类的扩展方法中就直接放进去就会很有可能产生不可预想的后果，比如把一些参数的校验方法直接放到String里，那就麻烦了，所以这也依赖大家所在的项目组有良好的code review流程以及代码规范。总之可以尝试一下。