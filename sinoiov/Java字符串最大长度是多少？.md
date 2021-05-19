# Java字符串最大长度是多少？

众所周知Java有8大基本类型，在Jvm规范里也规定了这8大基本类型的定义和大小：

> - `byte`, whose values are 8-bit signed two's-complement integers, and whose default value is zero
> - `short`, whose values are 16-bit signed two's-complement integers, and whose default value is zero
> - `int`, whose values are 32-bit signed two's-complement integers, and whose default value is zero
> - `long`, whose values are 64-bit signed two's-complement integers, and whose default value is zero
> - `char`, whose values are 16-bit unsigned integers representing Unicode code points in the Basic Multilingual Plane, encoded with UTF-16, and whose default value is the null code point (`'\u0000'`)
>
> The floating-point types are:
>
> - `float`, whose values are elements of the float value set or, where supported, the float-extended-exponent value set, and whose default value is positive zero
> - `double`, whose values are elements of the double value set or, where supported, the double-extended-exponent value set, and whose default value is positive zero
>
> The values of the `boolean` type encode the truth values `true` and `false`, and the default value is `false`.
>
> - For `byte`, from -128 to 127 (-27 to 27 - 1), inclusive
> - For `short`, from -32768 to 32767 (-215 to 215 - 1), inclusive
> - For `int`, from -2147483648 to 2147483647 (-231 to 231 - 1), inclusive
> - For `long`, from -9223372036854775808 to 9223372036854775807 (-263 to 263 - 1), inclusive
> - For `char`, from 0 to 65535 inclusive

那我就好奇了，平时除了这八大基本类型之外我们使用最多的还有String类型，那String类型的最大长度是多少呢？？

于是我开始动手做实验来寻找String的最大长度。

ps：搜索String类型最大长度在中文搜索引擎上和在谷歌上的结果是不同的，所以笔者本着实事求是的态度自己试试。



## String最大长度究竟多少？

首先在Stackoverflow上搜索`Java String max length`的话会有答案，答案是2的31次方减一，因为String的length方法返回值是int类型，int类型的范围最大就是2的31次方减1，所以我写了如下代码：

```java
public class StringTest {

    public static void main(String[] args) {
        String b = "1...1";//2的31次方减2个1
        System.out.println(b.length());
    }
}
```

为了防止IDE的因素影响所以我们用Java自带的Javac命令来编译，得到结果：`StringTest.java:8: 错误: 常量字符串过长` 那不对啊，说明字符串最大长度的确不是这么多啊，那到底是多少呢？我们分析一下，首先这个类是在编译成class文件的时候报错的，然后我们编译用的是Javac命令，那是不是这个Javac命令有限制呢？那我们看Javac源码不就完了，三大JVM只有一个OpenJdk开源了所以我们可以去GitHub上下载一份OpenJdk源码。然后在下载的OpenJdk源码里找到javac源码的目录：`\langtools\src\share\classes\com\sun\tools\javac` 把这个目录拷贝到idea中新增的java项目里我们就可以打开javac的源码了,然后运行Main类的main方法一句一句的跟代码，在`com.sun.tools.javac.jvm.Gen`类中发现了如下代码：

```java
 /** Check a constant value and report if it is a string that is
     *  too large.
     */
    private void checkStringConstant(DiagnosticPosition pos, Object constValue) {
        if (nerrs != 0 || // only complain about a long string once
            constValue == null ||
            !(constValue instanceof String) ||
            ((String)constValue).length() < Pool.MAX_STRING_LENGTH)
            return;
        log.error(pos, "limit.string");
        nerrs++;
    }
```

这里会判断长度如果大于就报错字符串超长，这个`Pool.MAX_STRING_LENGTH`就是字符串的最大长度了吧？这个常量表示是`public static final int MAX_STRING_LENGTH = 0xFFFF`16进制的65535。那就是这里控制的吗？在这里稍作修改，然后修改上面的测试代码修改成65537个1，然后运行，很遗憾还是报错：`对于常量池来说, 字符串的 UTF8 表示过长`然后我们继续跟代码，发现`com.sun.tools.javac.jvm.ClassWriter`的`writePool`方法内还是有校验：

```java
 if (bs.length > Pool.MAX_STRING_LENGTH)
    throw new StringOverflow(value.toString());
```

再次修改这个地方的代码，然后main方法编译，发现并未报错，然后用`java`命令运行这个class发现也没问题，那么我们可以得出结论：`Java中字符串最大长度是65534`吗？我们再来换个方法试试：

```java
public class StringTest {

    public static void main(String[] args) {
        String a = "";
        for (int i = 1; i < 65540; i++) {
            a += "1";
        }
        System.out.println(a);
    }
}
```

以上程序用未修改过的javac编译是能通过的并且能运行输出结果的。那这个和我们上面的结论不是矛盾了吗？？？

其实上面javac的校验代码里我们可以看到出现频率很高的词语——"Constant、pool "，这就引申出了下一个问题——String常量池；了解了常量池以后也许我们就会得到这个问题的答案。



## Java String常量池

>
>
>在 JAVA 语言中有8中基本类型和一种比较特殊的类型String。这些类型为了使他们在运行过程中速度更快，更节省内存，都提供了一种常量池的概念。常量池就类似一个JAVA系统级别提供的缓存。
>
>8种基本类型的常量池都是系统协调的，String类型的常量池比较特殊。它的主要使用方法有两种：
>
>1、直接使用双引号声明出来的String对象会直接存储在常量池中。
>2、如果不是用双引号声明的String对象，可以使用String提供的intern方法。intern 方法会从字符串常量池中查询当前字符串是否存在，若不存在就会将当前字符串放入常量池中

以上是美团技术团队对String常量池的一个简单总结，我觉得说得很好所以直接搬过来了，可以直接用上面的结论：用双引号声明出来的对象直接存储在常量池中（相信大家面试的时候都遇到过："String a=new String("a")这个语句创建了几个对象"的问题），上文的例子中，例子1我们是直接双引号新建的字符串所以是直接存储在常量池中的，例子2中我们是采用创建了一个对象的方式来新建字符串的所以生成的字符串不会直接存储在常量池里除非显式地调用`intern`方法手动存入常量池，并且javac只会去校验放入常量池的字符串，所以例子1的字符串直接放入常量池以前会做校验，例子2的压根不会放入常量池所以不会去校验，所以上文得到的结论实际上并不冲突，只是需要修改一下：

1、直接放入常量池的字符串简称常量池字符串是有长度限制的即小于65535，这个限制是在编译期间Javac限制的。

2、如果是创建对象的方式新建字符串的话的长度限制理论上来说的确是2的31次方减1，也就是大概4GB，当然了也和你的堆有关系如果你的堆太小就会OOM。

按理来说本文到这里就应该结束了，但是本着打破沙锅问到底的精神，笔者还有两个疑问：

1、65535这个数字是怎么来的，为什么是这么多

2、如果例子2中调用了intern的话会报错吗？

## 为什么是65535

众所周知，Java虚拟机是有一个JVM规范的，我们的Java是必须要遵守这个规范的，这个规范叫`Java Virtual Machine Specification`，就在Oracle的官网上就可以找到，所以这个答案也许就在JVM规范里。在规范：https://docs.oracle.com/javase/specs/jvms/se8/html/index.html 里，可以看到第4.4节`The Constant Pool`就是讲常量池的，然后我们在4.4节的第3小节中可以看到`The CONSTANT_String_info Structure`字符串常量结构：

>The `CONSTANT_String_info` structure is used to represent constant objects of the type `String`:
>
>```
>CONSTANT_String_info {
>    u1 tag;
>    u2 string_index;
>}
>```
>
>The items of the `CONSTANT_String_info` structure are as follows:
>
>- tag
>
>  The `tag` item of the `CONSTANT_String_info` structure has the value `CONSTANT_String` (8).
>
>- string_index
>
>  The value of the `string_index` item must be a valid index into the `constant_pool` table. The `constant_pool` entry at that index must be a `CONSTANT_Utf8_info` structure ([§4.4.7](https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.4.7)) representing the sequence of Unicode code points to which the `String` object is to be initialized.d

可以看到字符串常量比如遵从`CONSTANT_Utf8_info`的一个结构规范，这个结构规范如下：

> The `CONSTANT_Utf8_info` structure is used to represent constant string values:
>
> ```
> CONSTANT_Utf8_info {
>     u1 tag;
>     u2 length;
>     u1 bytes[length];
> }
> ```
>
> The items of the `CONSTANT_Utf8_info` structure are as follows:
>
> - tag
>
>   The `tag` item of the `CONSTANT_Utf8_info` structure has the value `CONSTANT_Utf8` (1).
>
> - length
>
>   The value of the `length` item gives the number of bytes in the `bytes` array (not the length of the resulting string).
>
> - bytes[]
>
>   The `bytes` array contains the bytes of the string.No byte may have the value `(byte)0`.No byte may lie in the range `(byte)0xf0` to `(byte)0xff`.

节选了一部分，可以length属性表明了该类型存储数据的长度，length属性是u2类型的，众所周知u2是无符号的16位整数，最大长度是2^16=65536，null 值使用两个字节来表示，因此只剩下 65536-2 = 65534个字节，所以javac中是65535，65534是满足规范的最大值，所以javac中是小于65535。

## 调用了intern会报错吗

解决了第一个疑问，接着来看第二个疑问，我们稍微改造一下例子2：

```java
public class StringTest {

    public static void main(String[] args) {
        String a = "";
        for (int i = 1; i < 65540; i++) {
            a += "1";
        }
        a.intern();
        System.out.println(a.intern().length());
        System.out.println(a.intern());
    }
}
```

运行一下是可以运行，并不报错。所以问题2的答案是不会报错。那么为什么呢？？

这个就要从inern方法下手了，intern方法是一个Native方法，我们需要用到刚刚下载下来的OpenJdk的源码，继续去源码里寻找答案。在`jdk\src\share\native\java\lang`目录下的String.c中：

```c
Java_java_lang_String_intern(JNIEnv *env, jobject this)  
{  
    return JVM_InternString(env, this);  
} 
```

然后在`hotspot\src\share\vm\prims\jvm.h`中：

```c
/* 
* java.lang.String 
*/  
JNIEXPORT jstring JNICALL  
JVM_InternString(JNIEnv *env, jstring str);  
```

`\hotspot\src\share\vm\prims\jvm.cpp`中:

```c
// String support ///////////////////////////////////////////////////////////////////////////  
JVM_ENTRY(jstring, JVM_InternString(JNIEnv *env, jstring str))  
  JVMWrapper("JVM_InternString");  
  JvmtiVMObjectAllocEventCollector oam;  
  if (str == NULL) return NULL;  
  oop string = JNIHandles::resolve_non_null(str);  
  oop result = StringTable::intern(string, CHECK_NULL);
  return (jstring) JNIHandles::make_local(env, result);  
JVM_END 
```

最后是`hotspot\src\share\vm\classfile\symbolTable.cpp`中：

```c++
oop StringTable::intern(Handle string_or_null, jchar* name,
                        int len, TRAPS) {
  unsigned int hashValue = hash_string(name, len);
  int index = the_table()->hash_to_index(hashValue);
  oop found_string = the_table()->lookup(index, name, len, hashValue);
  // Found
  if (found_string != NULL) return found_string;

  debug_only(StableMemoryChecker smc(name, len * sizeof(name[0])));
  assert(!Universe::heap()->is_in_reserved(name),
         "proposed name of symbol must be stable");
  Handle string; 
  // try to reuse the string if possible
  if (!string_or_null.is_null()) {
    string = string_or_null;
  } else {
    string = java_lang_String::create_from_unicode(name, len, CHECK_NULL);
  }
  // Grab the StringTable_lock before getting the_table() because it could
  // change at safepoint.
  MutexLocker ml(StringTable_lock, THREAD);
  // Otherwise, add to symbol to table
  return the_table()->basic_add(index, string, name, len,
                                hashValue, CHECK_NULL);
}
```

虽然笔者的C++早就还给了老师但是还是能勉强看出来这里的intern方法是没有去校验长度的，这个方法的大概意思就是先用hash计算一个值然后根据这个值获取下标，然后先去常量池里看这个下标有没有值有的话直接返回没有的话就存入（basic_add方法笔者也看了这里不贴出来了的确是没有校验的），所以问题的答案就在于intern方法底层并未做校验所以存入的时候是不会报错的。



## 总结

最后总结一下：**在Javac编译器下，字符串String的最大长度限制也即是U2类型所能表达的最大长度65534。避开javac最大长度是65535**



参考：

[字符串String的最大长度]: https://segmentfault.com/a/1190000020381075
[java 基础篇-05-String 字符串又长度限制吗？常量池详解]: https://houbb.github.io/2020/07/19/java-basic-05-string
[深入解析String#intern]: https://tech.meituan.com/2014/03/06/in-depth-understanding-string-intern.html

