# 换种语言写单元测试：Spock



## 背景

单元测试的重要性无需赘述，大多数缺陷在单元测试中暴露出来的成本是最低的，XML之父Tim Bray在博客里有个好玩的说法：“代码不写测试就像上了厕所不洗手……单元测试是对软件未来的一项必不可少的投资。”具体来说，单元测试有哪些收益呢？

- 适应变更

单元测试允许程序员在未来重构代码，并且确保模块依然工作正确（复合测试）。这个过程就是为所有函数和方法编写单元测试，一旦变更导致错误发生，借助于单元测试可以快速定位并修复错误。

可读性强的单元测试可以使程序员方便地检查代码片断是否依然正常工作。良好设计的单元测试案例覆盖程序单元分支和循环条件的所有路径。

在连续的单元测试环境，通过其固有的持续维护工作，单元测试可以延续用于准确反映当任何变更发生时可执行程序和代码的表现。借助于上述开发实践和单元测试的覆盖，可以分分秒秒维持准确性。

- 简化集成

单元测试消除程序单元的不可靠，采用自底向上的测试路径。通过先测试程序部件再测试部件组装，使集成测试变得更加简单。

业界对于人工集成测试的必要性存在较大争议。尽管精心设计的单元测试体系看上去实现了集成测试，因为集成测试需要人为评估一些人为因素才能证实的方面，单元测试替代集成测试不可信。一些人认为在足够的自动化测试系统的条件下，人力集成测试组不再是必需的。事实上，真实的需求最终取决于开发产品的特点和使用目标。另外，人工或手动测试很大程度上依赖于组织的可用资源

- 文档记录

  单元测试提供了系统的一种文档记录。借助于查看单元测试提供的功能和单元测试中如何使用程序单元，开发人员可以直观的理解程序单元的基础API。

  单元测试具体表现了程序单元成功的关键特点。这些特点可以指出正确使用和非正确使用程序单元，也能指出需要捕获的程序单元的负面表现（译注：异常和错误）。尽管很多软件开发环境不仅依赖于代码做为产品文档，在单元测试中和单元测试本身确实文档化了程序单元的上述关键特点。

  另一方面，传统文档易受程序本身实现的影响，并且时效性难以保证（如设计变更、功能扩展等在不太严格时经常不能保持文档同步更新）。

- 表达设计

  在测试驱动开发的软件实践中，单元测试可以取代正式的设计。每一个单元测试案例均可以视为一项类、方法和待观察行为等设计元素。

尽管单元测试有如此的收益，但在我们日常的工作中，仍然存在不少项目它们的单元测试要么是不完整要么是缺失的。常见的原因总结如下：1、时间紧任务重，我们后面再来补（当然了这个后面也没有下文了）；2、代码逻辑过于复杂，100行的代码我要写300行的单元测试，但是预估的工期了可不含单元测试时间啊，不写好了；3、根本没意识到要写单元测试，post man一把梭、main方法一把梭、界面上自己点点点；

其实以上原因除了工期这个客观原因之外几乎都是主观原因，其实也能理解，因为java写业务代码已经够啰嗦的了，我还要写单元测试，那不是更啰嗦了吗？而且java写单元测试一半以上的代码都是在构造测试数据，实在是太麻烦了，那笔者今天介绍一种新的单元测试框架——Spock，让单元测试更加简洁更加简单，让你爱上写单元测试。

Dıf tor heh susma！（生生不息，繁荣昌盛）

![](http://n.sinaimg.cn/transform/20150309/d5fy-avxeafs1615246.jpg)

## 什么是Spock

>
>
>Spock is a testing and specification framework for Java and Groovy applications. What makes it stand out from the crowd is its beautiful and highly expressive specification language. Thanks to its JUnit runner, Spock is compatible with most IDEs, build tools, and continuous integration servers. Spock is inspired from [JUnit](http://junit.org/), [jMock](http://www.jmock.org/), [RSpec](http://rspec.info/), [Groovy](http://groovy-lang.org/), [Scala](https://scala-lang.org/), [Vulcans](https://en.wikipedia.org/wiki/Vulcan_(Star_Trek)), and other fascinating life forms.

在Spock官网上他们这样介绍自己（他们官网：https://spockframework.org/spock/docs/2.1/introduction.html ）（介绍上就一个瓦肯人的logo，作者应该是一个星际迷航粉丝）

简单来说，Spock是基于BDD（行为驱动开发）思想实现，功能非常强大。Spock结合Groovy动态语言的特点，提供了各种标签，并采用简单、通用、结构化的描述语言，让编写测试代码更加简洁、高效。Spock是用Groovy实现的，用Spock写单元测试也是用Groovy来写的，由于Groovy也是运行在JVM上的语言所以我们的java项目使用这个是没有门槛和成本的。

简单来讲，Spock主要特点如下：

- 让测试代码更规范，内置多种标签来规范单元测试代码的语义，测试代码结构清晰，更具可读性，降低后期维护难度。
- 提供多种标签，比如：`given`、`when`、`then`、`expect`、`where`、`with`、`thrown`……帮助我们应对复杂的测试场景。
- 使用Groovy这种动态语言来编写测试代码，可以让我们编写的测试代码更简洁，适合敏捷开发，提高编写单元测试代码的效率。
- 遵从BDD（行为驱动开发）模式，有助于提升代码的质量。
- IDE兼容性好，自带Mock功能。

## 如何使用Spock

首先Spock已经很好的集成到了我们的spring框架里了，我们只需要在pom文件里引入如下依赖：

```xml
 				<dependency>
            <groupId>org.spockframework</groupId>
            <artifactId>spock-core</artifactId>
            <version>1.2-groovy-2.4</version>
            <scope>test</scope>
        </dependency>
        <!-- Spock需要的groovy依赖 -->
        <dependency>
            <groupId>org.codehaus.groovy</groupId>
            <artifactId>groovy-all</artifactId>
            <version>2.4.15</version>
            <scope>test</scope>
        </dependency>
```

然后在test目录下和java目录同级新建一个`groovy`目录，来存放Spock的单元测试

接下来我们根据不同的场景来看看Spoc写单元测试有什么优点



### 1、简单场景

首先我们有一个service类：

```java
@Service
public class StudentService {

    @Autowired
    private StudentDao studentDao;

    public StudentDTO getStudentById(Long studentId) {
        StudentBO studentBO = studentDao.findStudentByStudentId(studentId);
        StudentDTO studentDTO = StudentDTO.convert(studentBO);

        if (studentDTO.getRegion().equals("shanghai")) {
            studentDTO.setRegion("上海");
        }
        if (studentDTO.getRegion().equals("beijing")) {
            studentDTO.setRegion("北京");
        }
        return studentDTO;
    }
}
```



就是根据id查询学生，然后数据库保存的是地区的拼音，我们要翻译成中文返回给调用方。我们首先来看看传统的junit的写法：

```java
@RunWith(MockitoJUnitRunner.class)
public class StudentServiceJunitTest {

    @InjectMocks
    private StudentService studentService;

    @Mock
    private StudentDao studentDao;

    @Test
    public void testGetStudentById() {
        StudentBO studentBO=new StudentBO();
        studentBO.setStudentId(1L);
        studentBO.setName("test");
        studentBO.setRegion("shanghai");
        when(studentDao.findStudentByStudentId(any())).thenReturn(studentBO);
        StudentDTO studentDTO = studentService.getStudentById(1L);
        assertNotNull(studentDTO);
        assertEquals(1L, studentDTO.getStudentId());
        assertEquals("上海", studentDTO.getRegion());
    }
}
```

好像不那么复杂对不对，我们再来看看Spoc是怎么写的，由于是第一次写，笔者从头带大家来看看spock是怎么写单元测试的:

首先我们新建一个test类，名字就是我们被测试类加上test，然后需要继承`Specification`，我们Spock的核心类：

```groovy
class StudentServiceTest extends Specification {

   
}
```

然后我们需要来构造我们的被测试类和被测试类的成员类，在junit中因为我们有Mockito所以也很简单加两个注解就是了，那么在Spock中也不复杂， 我们只需要：

```groovy
class StudentServiceTest extends Specification {

		def studentDao = Mock(StudentDao.class)
		def StudentService studentService = new StudentService(studentDao: studentDao)
   
}
```

这样写就可以了，`studentDao: studentDao`表面这个StudentService的成员类studentDao是由我刚刚Mock出来的StudentDao。当然了我们也可以这样写：

```groovy
void setup() {
        studentService.studentDao = studentDao
    }
```

然后我们接下来就先把Spock的格式打好：

```groovy
class StudentServiceTest extends Specification {

    def studentDao = Mock(StudentDao.class)
    def StudentService studentService = new StudentService(studentDao: studentDao)
    
    def "test_getStudentById"() {
        given: "准备参数"

        and: "mock数据"

        when: "调用"

        then: "验证数据"

    }
}
```

这就是Spock单元测试的格式，每个关键字代表一个步骤

- `given`：输入条件（前置参数）。
- `when`：执行行为（`Mock`接口、真实调用）。
- `then`：输出条件（验证结果）。
- `and`：衔接上个标签，补充的作用。

注意这里，given和and其实是都可以来mock数据的，只是笔者个人习惯喜欢在and里mock数据，有场景下不用准备参数的话就可以把mock步骤放在given里这样就没有and了，

我们来填充好步骤就可以了：

```groovy
def "test_getStudentById"() {
        given: "准备参数"
        StudentBO studentBO = new StudentBO();
        studentBO.setStudentId(1L);
        studentBO.setName("test");
        studentBO.setRegion("shanghai");

        and: "mock数据"
        studentDao.findStudentByStudentId(_) >> studentBO

        when: "调用"
        def response = studentService.getStudentById(1L)

        then: "验证数据"
        with(response) {
            studentId == 1L
            region == "上海"
        }

    }
```

`def studentDao = Mock(StudentDao)` 这一行代码使用Spock自带的Mock方法，构造一个`studentDao`的Mock对象，如果要模拟`studentDao`方法的返回，只需`studentDao.方法名() >> "模拟值"`的方式，两个右箭头的方式即可，如果要指定返回多个值的话，可以使用`3`个右箭头`>>>`。`(_)`代表任何参数，相当于`any()`

因为被测试类的代码就很简单，所以测试代码比起junit来说没有少多少，但是是不是更容易读懂和容易写呢？Spock会强制要求使用`given`、`when`、`then`这样的语义标签（至少一个），否则编译不通过，这样就能保证代码更加规范，结构模块化，边界范围清晰，可读性强，便于扩展和维护。而且使用了自然语言描述测试步骤，让非技术人员也能看懂测试代码





### 2、单元测试void方法

有如下代码：

```java
 public void saveStudent(StudentDTO studentDTO) {
        if (studentDTO.getName() == null) {
            throw new IllegalArgumentException("学生姓名不能为空");
        }
        if (studentDTO.getSex() == null) {
            throw new IllegalArgumentException("学生性别不能为空");
        }
        studentDao.saveStudent(StudentBO.convert(studentDTO));
    }
```



我们来用Spock来写一下这个单元测试：

```groovy
  def "test_saveStudent"() {

        given: "准备参数"
        StudentDTO studentDTO = StudentDTO.builder().name("test").sex("boy").build()

        when: "调用"
        studentService.saveStudent(studentDTO)

        then: "验证"
        1 * studentDao.saveStudent(_)
    }
```

六行代码就搞定了，`1 * studentDao.saveStudent(_)`代表校验这个方法是否执行了1次的意思。



### 3、测试异常

还是用上文的save方法的例子，我们现在要测试返回的异常是否正常，我们可以这样来写：

```groovy
		@Unroll
    def "test_saveStudent_exception"() {
        when: "调用"
        studentService.saveStudent(studentDTO)

        then: "验证"
        def exception = thrown(expectedException)
        exception.message == message

        where: "测试数据"
        studentDTO                                || expectedException        | message
        StudentDTO.builder().sex("boy").build()   || IllegalArgumentException | "学生姓名不能为空"
        StudentDTO.builder().name("test").build() || IllegalArgumentException | "学生性别不能为空"
    }
```

很简单对吗？junit中的异常测试有多难写就不用笔者多说了。这里主要是用了Spock的分支判断语法，where语句中有这种表格的写法来对每种情况的异常做出校验。

其实where这种表格写法除了可以校验异常之外还可以用于我们的多if-else分支的单元测试。



## 4、多分支测试

1中的代码：

```java
@Service
public class StudentService {

    @Autowired
    private StudentDao studentDao;

    public StudentDTO getStudentById(Long studentId) {
        StudentBO studentBO = studentDao.findStudentByStudentId(studentId);
        StudentDTO studentDTO = StudentDTO.convert(studentBO);

        if (studentDTO.getRegion().equals("shanghai")) {
            studentDTO.setRegion("上海");
        }
        if (studentDTO.getRegion().equals("beijing")) {
            studentDTO.setRegion("北京");
        }
        return studentDTO;
    }
}
```

我们刚刚试了一下Spock的写法，现在我们要对这两个if写单元测试，就是不同的入参返回不同的值，如果是按照上文的junit的写法的话我们需要写两次差不多一样的代码，但是在Spock中，一切都很简单了：

```groovy
 @Unroll
    def "test_getStudentById_multiple"() {

        given: "mock数据"
        studentDao.findStudentByStudentId(_) >> studentBO

        when: "调用"
        def response = studentService.getStudentById(id)

        then: "验证返回结果"
        with(response) {
            region == region
        }

        where: "经典之处：表格方式验证学生信息的分支场景"
        id | studentBO                                                                 || region
        1  | StudentBO.builder().studentId(1L).name("test").region("shanghai").build() || "上海"
        2  | StudentBO.builder().studentId(2L).name("test2").region("beijing").build() || "北京"

    }
```

`where`模块第一行代码是表格的列名，多个列使用`|`单竖线隔开，`||`双竖线区分输入和输出变量，即左边是输入值，右边是输出值。格式如下：

```
输入参数1 | 输入参数2 || 输出结果
```

而且`idea`支持`format`格式化快捷键，因为表格列的长度不一样，手动对齐比较麻烦。表格的每一行代表一个测试用例，即被测方法执行了2次，每次的输入和输出都不一样，刚好可以覆盖全部分支情况。比如`id`、`students`都是输入条件，其中studentDao每次返回不同的student对象，每次测试业务代码传入不同的`id`值，`postCodeResult`、`region`表示对返回的`response`对象的属性判断是否正确。只需要写上我们的预期值就可以了。这个就是`where`+`with`的用法，更符合我们实际测试的场景，既能覆盖多种分支，又可以对复杂对象的属性进行验证，而且这里可以用Groovy的字面值特性:

```groovy
@Unroll
def "input 学生ID：#id,返回的地区值 #region"() {
```

这样的好处就是就可以把有占位符的动态替换掉

![WX20220326-151030](/Users/zhangyunfan/Desktop/WX20220326-151030.png)

当然了上文的这个例子太简单了，不能太体现出这种表格写法的优越性，我们来看一个书上的复杂的例子：

```java
public double calc(double income) {
        BigDecimal tax;
        BigDecimal salary = BigDecimal.valueOf(income);
        if (income <= 0) {
            return 0;
        }
        if (income > 0 && income <= 3000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.03);
            tax = salary.multiply(taxLevel);
        } else if (income > 3000 && income <= 12000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.1);
            BigDecimal base = BigDecimal.valueOf(210);
            tax = salary.multiply(taxLevel).subtract(base);
        } else if (income > 12000 && income <= 25000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.2);
            BigDecimal base = BigDecimal.valueOf(1410);
            tax = salary.multiply(taxLevel).subtract(base);
        } else if (income > 25000 && income <= 35000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.25);
            BigDecimal base = BigDecimal.valueOf(2660);
            tax = salary.multiply(taxLevel).subtract(base);
        } else if (income > 35000 && income <= 55000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.3);
            BigDecimal base = BigDecimal.valueOf(4410);
            tax = salary.multiply(taxLevel).subtract(base);
        } else if (income > 55000 && income <= 80000) {
            BigDecimal taxLevel = BigDecimal.valueOf(0.35);
            BigDecimal base = BigDecimal.valueOf(7160);
            tax = salary.multiply(taxLevel).subtract(base);
        } else {
            BigDecimal taxLevel = BigDecimal.valueOf(0.45);
            BigDecimal base = BigDecimal.valueOf(15160);
            tax = salary.multiply(taxLevel).subtract(base);
        }
        return tax.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
    }
```

一个复杂的计算个人所得税的方法，能够看到上面的代码中有大量的`if-else`语句，如果用传统的junit来写单元测试，有多复杂我是想都不敢想，那么我们用Spock来写是怎么样的呢？

```groovy
@Unroll
def "个税计算,收入:#income, 个税:#result"() {
  expect: "when + then 的组合"
  CalculateTaxUtils.calc(income) == result

  where: "表格方式测试不同的分支逻辑"
  income || result
  -1     || 0
  0      || 0
  2999   || 89.97
  3000   || 90.0
  3001   || 90.1
  11999  || 989.9
  12000  || 990.0
  12001  || 990.2
  24999  || 3589.8
  25000  || 3590.0
  25001  || 3590.25
  34999  || 6089.75
  35000  || 6090.0
  35001  || 6090.3
  54999  || 12089.7
  55000  || 12090
  55001  || 12090.35
  79999  || 20839.65
  80000  || 20840.0
  80001  || 20840.45
}
```

是不是非常简单？甚至这种测试代码叫一个非技术人员都可以轻松写出来。



### 5、测试静态方法

很遗憾的是Spock和Mockito一样本身不支持mock静态方法、私有方法，这个时候我们可以集成第三方工具来实现，网上很多资料介绍的是powerMock，不过笔者这里介绍一个新工具——testableMock（官网地址：https://alibaba.github.io/testable-mock/#/），号称可以Mock Everything，的确也很好用。所以本篇先介绍一下testableMock的用法。

首先我们在POM文件里加入如下依赖：

```xml
 			<dependency>
            <groupId>com.alibaba.testable</groupId>
            <artifactId>testable-all</artifactId>
            <version>0.6.8</version>
            <scope>test</scope>
        </dependency>
```

因为testableMock的原理是基于java agent来的，所以我们也需要在POM文件里加入如下插件：

```xml
 <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>-javaagent:${settings.localRepository}/com/alibaba/testable/testable-agent/${testable.version}/testable-agent-${testable.version}.jar</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

注意在官网上最新版本是0.7.5，但是笔者在使用过程中发现这个版本在jdk版本大于等于9的时候有mock失效的情况：https://github.com/alibaba/testable-mock/issues/272，是的笔者又发现bug啦。所以如果你的项目jdk版本大于等于9的话建议使用0.6.8版本。

然后使用的话就是在我们的测试类里新建一个静态类：

```groovy
 static class Mock {

       
    }
```

注意这里Spock和传统的junit都是这种写法。testableMock支持多种单元测试框架。

我们写一个工具类：

```java
public class StudentRegionUtil {

    public static String convertRegion(String regionCode) {
        if (regionCode.equals("shanghai")) {
            return "上海";
        }
        if (regionCode.equals("beijing")) {
            return "北京";
        }
        return regionCode;
    }
}
```

然后在刚刚的get方法里改成由工具类来翻译我们的地区代码：

```java
   public StudentDTO getStudentByIdUtil(Long studentId) {
        StudentBO studentBO = studentDao.findStudentByStudentId(studentId);
        StudentDTO studentDTO = StudentDTO.convert(studentBO);
        studentDTO.setRegion(StudentRegionUtil.convertRegion(studentDTO.getRegion()));
        return studentDTO;
    }
```

然后由于是单元测试我们只关注被测试的方法，理论上说被测试的方法内部所有的调用其他类的方法都应该被Mock，所以我们在单元测试里`StudentRegionUtil.convertRegion`是需要被mock的，我们在刚刚的静态类mock里这样写：

```groovy
    static class Mock {
        @MockMethod(targetClass = StudentRegionUtil.class)
        private String convertRegion(String regionCode) {
            return "成都";
        }
    }
```

`MockMethod`注解表示这个方法是要mock的，注解的属性`targetClass`表示被mock的类，`private String convertRegion(String regionCode)`则是完全按照被mock的方法来写的，只是把`public static`替换成private`即可，并且也可以在注解里设置`targetMethod`来说明mock的是哪个方法，这样写之后后面只要是有被测试类调用了`StudentRegionUtil.convertRegion`的地方都是会走这个静态类里的这个方法了，这样我们就可以mock静态和私有方法啦。



### 6、数据库、redis等中间件单元测试

在单元测试中要用到数据库、redis的地方建议使用testcontainers，虽然H2数据库也是一个选择，说到testcontainers就不得不提笔者的`easytestContainers`啦，很遗憾的是笔者的`easytestContainers`暂时不支持Spock框架，等笔者研(摸)究(鱼)

之后再更新。这里先记一个todo。



## 总结

Spock是一个简洁、简单的单元测试框架，他能让你的单元测试变得更易读也更能节省写单元测试的时间，希望大家能亲手试试！

参考：

Spock单元测试框架介绍以及在美团优选的实践:  https://tech.meituan.com/2021/08/06/spock-practice-in-meituan.html

Spock 测试框架的介绍和使用详解: https://cloud.tencent.com/developer/article/1503474